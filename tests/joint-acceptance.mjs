// Local-only real participants. No mock HTTP servers and no other repository edits.
import {spawnSync} from 'node:child_process';
import {randomBytes,randomUUID,generateKeyPairSync} from 'node:crypto';
import {readFileSync,writeFileSync,mkdirSync,rmSync} from 'node:fs';
import {resolve} from 'node:path';
import assert from 'node:assert/strict';

const root=resolve(import.meta.dirname,'..'),owner=`customers-joint-${randomUUID().replaceAll('-','')}`;
const dir=resolve(root,'.test-run',owner);mkdirSync(dir,{recursive:true,mode:0o700});
const network=owner,containers=[],images=[],secrets=[];
const secret=()=>{const value=randomBytes(24).toString('hex');secrets.push(value);return value;};
const passwords={admin:secret(),customers:secret(),auth:secret(),products:secret(),workflow:secret(),sales:secret(),human:'Synthetic-'+secret()+'!'};
const sources={auth:resolve(root,'.local-reference/code-corhuila-synkro-auth-api-04ae6ab'),products:resolve(root,'.local-reference/code-corhuila-synkro-products-api-b6b379b'),sales:resolve(root,'.local-reference/code-corhuila-synkro-sales-api-70bdbab'),workflow:resolve(root,'.local-reference/code-corhuila-synkro-workflow-45aa244')};
const revisions={auth:'04ae6ab92daf474fb5c38ae37842d1690e7584b0',products:'b6b379b314716e201833be0d854953a69452559f',sales:'70bdbaba3783bfb974c338b8a460e6e9b7e5c8dd',workflow:'45aa244251851fda46212c5b6338668daaa683be',customersDB:'a23ba04f94ff0f150c5bafd9801cc0c6d9e98bd7',authDB:'5e481dba156db6f2febb00427efba637002d9a0c',productsDB:'0beae79920d9af83865bdebaf6b316a2b2a57592',salesDB:'9cfbde7db4bea7bacad70370c60f91f8eb917c04'};
function command(program,args,{input,timeout=300000,allowFailure=false}={}){
    const r=spawnSync(program,args,{input,encoding:'utf8',timeout,maxBuffer:32*1024*1024});
    if(!allowFailure&&(r.status!==0||r.error)){
        let output=(r.stdout??'')+(r.stderr??'');for(const value of secrets)output=output.replaceAll(value,'[REDACTED]');
        writeFileSync(resolve(root,'.test-cache/joint-diagnostic.log'),output);
        throw Error(`${program} ${args[0]} failed; sanitized diagnostic retained locally`);
    }return r;
}
const docker=(args,options)=>command('docker',args,options);
function env(name,values){const path=resolve(dir,name+'.env');writeFileSync(path,Object.entries(values).map(([k,v])=>`${k}=${v}`).join('\n')+'\n',{mode:0o600});return path;}
function build(name,source,file='deploy/Dockerfile'){
    console.log('Building actual '+name+' snapshot');const image=owner+':'+name;images.push(image);
    docker(['build','--label','synkro.execution='+owner,'-t',image,'-f',resolve(source,file),source],{timeout:900000});return image;
}
function launch(name,image,settings={},args=[],run=[]){
    const container=owner+'-'+name;containers.push(container);
    docker(['run','-d','--name',container,'--label','synkro.execution='+owner,'--network',network,'--network-alias',name,'--env-file',env(name,settings),...args,image,...run]);return container;
}
function sql(query){return docker(['exec','-i',pg,'psql','-X','-U','postgres','-d','synkro','-qAt','-v','ON_ERROR_STOP=1'],{input:query,timeout:30000}).stdout.trim();}
function mongo(query,authenticated=true){
    const auth=authenticated?`await db.getSiblingDB('admin').auth('admin','${passwords.admin}');`:'';
    const evaluate='const AsyncFunction=Object.getPrototypeOf(async function(){}).constructor; (new AsyncFunction("db","rs","print",require("fs").readFileSync(0,"utf8")))(db,rs,print)';
    return docker(['exec','-i',mongodb,'mongosh','--quiet','--eval',evaluate],{input:auth+query,timeout:15000}).stdout.trim();
}
async function wait(check,seconds=90){const deadline=Date.now()+seconds*1000;while(Date.now()<deadline){try{const r=await check();if(r)return r;}catch{}await new Promise(r=>setTimeout(r,1000));}throw Error('Participant readiness/state deadline exceeded');}
const urls={};
function bind(name,container){const value=JSON.parse(docker(['inspect',container]).stdout)[0].NetworkSettings.Ports['8080/tcp'][0];urls[name]=`http://127.0.0.1:${value.HostPort}`;}
async function request(name,path,method='GET',body,key,token){
    const headers={'X-Correlation-Id':'joint-'+owner};if(token)headers.Authorization='Bearer '+token;if(body!==undefined)headers['Content-Type']='application/json';if(key)headers['Idempotency-Key']=key;
    const r=await fetch(urls[name]+path,{method,headers,body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(15000)});return {status:r.status,body:await r.json(),location:r.headers.get('Location')};
}
const publicKey=resolve(dir,'public.pem'),privateKey=resolve(dir,'private.pem'),humanPassword=resolve(dir,'human-password');
const keys=generateKeyPairSync('rsa',{modulusLength:2048});writeFileSync(publicKey,keys.publicKey.export({type:'spki',format:'pem'}));writeFileSync(privateKey,keys.privateKey.export({type:'pkcs8',format:'pem'}));writeFileSync(humanPassword,passwords.human);
writeFileSync(resolve(dir,'workflow-password'),passwords.workflow);
const mount=(file,target)=>['--mount',`type=bind,source=${file},target=${target},readonly`];
let pg,mongodb,failed=false;const effects={};
try{
    revisions.customers=command('git',['rev-parse','HEAD']).stdout.trim();sources.customers=resolve(dir,'customers');mkdirSync(sources.customers);
    const archive=resolve(dir,'customers.tar');command('git',['archive','HEAD','-o',archive]);command('tar',['-xf',archive,'-C',sources.customers]);
    docker(['network','create','--label','synkro.execution='+owner,network]);
    pg=launch('db','postgres:16.6-alpine',{POSTGRES_PASSWORD:passwords.admin,POSTGRES_DB:'synkro'},['--tmpfs','/var/lib/postgresql/data']);
    await wait(()=>docker(['exec',pg,'pg_isready','-U','postgres'],{allowFailure:true}).status===0);
    sql('REVOKE CREATE ON SCHEMA public FROM PUBLIC; '+['customers','auth','products','workflow'].map(name=>`CREATE ROLE ${name}_app LOGIN INHERIT PASSWORD '${passwords[name]}' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;`).join(' ')+'ALTER ROLE customers_app SET search_path=customers_schema;');
    const flyway=env('migrations',{FLYWAY_URL:'jdbc:postgresql://db:5432/synkro',FLYWAY_USER:'postgres',FLYWAY_PASSWORD:passwords.admin});
    for(const source of [resolve(root,'.local-reference/code-corhuila-synkro-auth-db-5e481db'),resolve(root,'.local-reference/code-corhuila-synkro-products-db-0beae79'),resolve(sources.workflow,'db')])
        docker(['run','--rm','--network',network,'--env-file',flyway,...mount(source,'/flyway/owner'),'flyway/flyway:11.20.3-alpine','-workingDirectory=/flyway/owner','-configFiles=flyway.toml','migrate'],{timeout:180000});
    docker(['run','--rm','--network',network,'--env-file',flyway,...mount(resolve(root,'tests/fixtures/customers-db'),'/workspace'),'--entrypoint','/bin/sh','flyway/flyway:10.20.1','/workspace/scripts/migrate.sh','migrate'],{timeout:180000});
    mongodb=launch('mongo','mongo:7.0',{},['--tmpfs','/data/db','--tmpfs','/data/configdb','--entrypoint','bash'],['-c','openssl rand -base64 48 > /tmp/keyfile && chmod 400 /tmp/keyfile && chown mongodb /tmp/keyfile && exec docker-entrypoint.sh mongod --replSet rs0 --bind_ip_all --keyFile /tmp/keyfile']);
    await wait(()=>docker(['exec',mongodb,'mongosh','--quiet','--eval','db.adminCommand({ping:1})'],{allowFailure:true}).status===0);
    mongo(`await rs.initiate({_id:'rs0',members:[{_id:0,host:'mongo:27017'}]})`,false);await wait(()=>mongo('print((await db.hello()).isWritablePrimary)',false).includes('true'));
    mongo(`await db.getSiblingDB('admin').createUser({user:'admin',pwd:'${passwords.admin}',roles:['root']})`,false);mongo(`await db.getSiblingDB('admin').createUser({user:'sales_app',pwd:'${passwords.sales}',roles:[]})`);
    const salesDB=resolve(root,'.local-reference/code-corhuila-synkro-sales-db-9cfbde7');const salesRunner=build('sales-runner',salesDB,'deploy/liquibase.Dockerfile');
    docker(['run','--rm','--network',network,'--env-file',env('sales-migrate',{LIQUIBASE_COMMAND_URL:`mongodb://admin:${passwords.admin}@mongo:27017/sales?replicaSet=rs0&authSource=admin`,LIQUIBASE_COMMAND_CHANGELOG_FILE:'changelog/changelog-master.yaml'}),...mount(salesDB,'/liquibase/changelog-root'),'-w','/liquibase/changelog-root',salesRunner,'update'],{timeout:300000});
    const authImage=build('auth',sources.auth);const authEnv={SPRING_DATASOURCE_URL:'jdbc:postgresql://db:5432/synkro?currentSchema=auth_schema',SPRING_DATASOURCE_USERNAME:'auth_app',SPRING_DATASOURCE_PASSWORD:passwords.auth,JWT_PRIVATE_KEY_FILE:'/run/private.pem',SYNKRO_INIT_OPERATOR:'customers-joint-disposable'};
    docker(['run','--rm','--network',network,'--env-file',env('initial-admin',authEnv),...mount(humanPassword,'/run/human-password'),'--entrypoint','java',authImage,'-cp','/app/auth-app.jar','-Dloader.main=co.edu.corhuila.synkro.auth.app.InitialAdminCli','org.springframework.boot.loader.launch.PropertiesLauncher','--name','Synthetic Admin','--email','joint-admin@example.test','--password-file','/run/human-password']);
    const auth=launch('auth',authImage,authEnv,[...mount(privateKey,'/run/private.pem'),'--publish','127.0.0.1::8080']);bind('auth',auth);await wait(async()=> (await request('auth','/health')).status===200);
    const login=await request('auth','/api/v1/auth/login','POST',{email:'joint-admin@example.test',password:passwords.human});assert.equal(login.status,200);const human=login.body.accessToken;secrets.push(human,login.body.refreshToken);
    const issued=await request('auth','/api/v1/auth/service-tokens','POST',{service:'synkro-workflow'},randomUUID(),human);assert.equal(issued.status,201);const service=issued.body.token;secrets.push(service);
    for(const name of ['customers','products','sales']){
        const image=build(name,sources[name]);let settings={JWT_PUBLIC_KEY_FILE:'/run/public.pem'};
        if(name==='customers')Object.assign(settings,{SPRING_DATASOURCE_URL:'jdbc:postgresql://db:5432/synkro',SPRING_DATASOURCE_USERNAME:'customers_app',SPRING_DATASOURCE_PASSWORD:passwords.customers});
        if(name==='products')Object.assign(settings,{DATABASE_URL:`postgres://products_app:${passwords.products}@db:5432/synkro?sslmode=disable`});
        if(name==='sales')Object.assign(settings,{MONGODB_URI:`mongodb://sales_app:${passwords.sales}@mongo:27017/sales?replicaSet=rs0&authSource=admin&retryWrites=false`});
        const container=launch(name,image,settings,[...mount(publicKey,'/run/public.pem'),'--publish','127.0.0.1::8080']);bind(name,container);await wait(async()=> (await request(name,'/health')).status===200);
    }
    const customer=await request('customers','/api/v1/customers','POST',{name:'Synthetic Joint Customer',identityDocument:randomUUID().slice(0,25)},randomUUID(),human);assert.equal(customer.status,201);
    assert.equal((await request('customers','/api/v1/customers/'+customer.body.customerId,'GET',undefined,undefined,service)).status,200);
    assert.equal((await request('customers','/api/v1/customers','GET',undefined,undefined,service)).status,403);
    const category=randomUUID(),product=randomUUID();sql(`INSERT INTO products_schema.category(category_id,name) VALUES ('${category}','Joint synthetic');INSERT INTO products_schema.product(product_id,name,price_cents,stock,category_id) VALUES ('${product}','Joint product',125,20,'${category}')`);
    const workflowImage=build('workflow',sources.workflow);const wf=launch('workflow',workflowImage,{WORKFLOW_DATABASE_URL:'jdbc:postgresql://db:5432/synkro',WORKFLOW_APP_PASSWORD_FILE:'/run/password',JWT_PUBLIC_KEY_FILE:'/run/public.pem',SERVICE_TOKEN:service,CUSTOMERS_BASE_URL:'http://customers:8080',PRODUCTS_BASE_URL:'http://products:8080',SALES_BASE_URL:'http://sales:8080'},[...mount(publicKey,'/run/public.pem'),...mount(resolve(dir,'workflow-password'),'/run/password'),'--publish','127.0.0.1::8080']);
    bind('workflow',wf);await wait(async()=> (await request('workflow','/health')).status===200);
    async function saga(customerId,expected){const r=await request('workflow','/api/v1/sagas/register-sale','POST',{customerId,lines:[{productId:product,quantity:2}]},randomUUID(),human);assert.equal(r.status,201);return wait(async()=>{const state=await request('workflow',r.location,'GET',undefined,undefined,human);return state.body.status===expected?state.body:false;},90);}
    const saleCount=()=>Number(mongo("print(await db.getSiblingDB('sales').sale.countDocuments({}))").split('\n').at(-1));
    const completed=await saga(customer.body.customerId,'COMPLETED');assert.ok(completed.saleId);assert.equal(sql(`SELECT stock FROM products_schema.product WHERE product_id='${product}'`),'18');assert.equal(saleCount(),1);
    assert.equal(sql("SELECT count(*) FROM products_schema.stock_reservation WHERE status='RESERVED'"),'1');effects.completed={stock:18,sales:1,reserved:1};console.log('Real Auth → Customers → Workflow → Products → Sales: PASS');
    await request('customers','/api/v1/customers/'+customer.body.customerId,'DELETE',undefined,undefined,human);
    for(const customerId of [customer.body.customerId,randomUUID()]){const result=await saga(customerId,'COMPENSATED');assert.equal(result.failedStep,'validate-customer');assert.equal(sql(`SELECT stock FROM products_schema.product WHERE product_id='${product}'`),'18');assert.equal(saleCount(),1);assert.equal(sql('SELECT count(*) FROM products_schema.stock_reservation'),'1');}effects.rejectedCustomers={stock:18,sales:1,reservations:1};console.log('Inactive/missing real Customers: no stock or sale side effects: PASS');
    const second=await request('customers','/api/v1/customers','POST',{name:'Compensation synthetic',identityDocument:randomUUID().slice(0,25)},randomUUID(),human);assert.equal(second.status,201);
    sql(`UPDATE products_schema.product SET price_cents=9223372036854775807 WHERE product_id='${product}'`);
    const compensated=await saga(second.body.customerId,'COMPENSATED');assert.equal(compensated.failedStep,'register-sale');assert.equal(sql(`SELECT stock FROM products_schema.product WHERE product_id='${product}'`),'18');assert.equal(saleCount(),1);assert.equal(sql("SELECT count(*) FROM products_schema.stock_reservation WHERE status='RELEASED'"),'1');effects.compensation={stock:18,sales:1,released:1};console.log('Actual Sales confirmed overflow rejection and Products release compensation: PASS');
    writeFileSync(resolve(root,'.test-cache/joint-result.json'),JSON.stringify({revisions,effects,scope:'All actual HTTP/database participants. Products revision retains bearer-shape authentication; this is functional interoperability, not platform security approval.'},null,2));
}catch(e){failed=true;console.error('Joint acceptance FAILED/BLOCKED: '+e.message);}
finally{
    let cleanupFailed=false;
    for(const name of containers.reverse()){
        const info=docker(['inspect',name],{allowFailure:true});if(info.status!==0)continue;
        if(JSON.parse(info.stdout)[0].Config.Labels?.['synkro.execution']!==owner){cleanupFailed=true;console.error('Unowned cleanup refused');continue;}if(docker(['rm','-fv',name],{allowFailure:true}).status!==0)cleanupFailed=true;
    }
    const info=docker(['network','inspect',network],{allowFailure:true});if(info.status===0){if(JSON.parse(info.stdout)[0].Labels?.['synkro.execution']!==owner)cleanupFailed=true;else if(docker(['network','rm',network],{allowFailure:true}).status!==0)cleanupFailed=true;}
    for(const image of images){const info=docker(['image','inspect',image],{allowFailure:true});if(info.status!==0)continue;if(JSON.parse(info.stdout)[0].Config.Labels?.['synkro.execution']!==owner){cleanupFailed=true;continue;}if(docker(['image','rm',image],{allowFailure:true}).status!==0)cleanupFailed=true;}
    rmSync(dir,{recursive:true,force:true});console.log('Joint owned resource cleanup: '+(cleanupFailed?'FAIL':'PASS')+' ('+owner+')');if(cleanupFailed)failed=true;
}
process.exitCode=failed?1:0;
