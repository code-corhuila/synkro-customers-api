import {generateKeyPairSync,sign,randomUUID} from 'node:crypto';
import {writeFileSync} from 'node:fs';
import {resolve} from 'node:path';
import assert from 'node:assert/strict';

export async function verifyRuntime({root,dir,prefix,network,db,password,docker}){
    const name=`${prefix}-runtime`;
    const keys=generateKeyPairSync('rsa',{modulusLength:2048});
    const pem=resolve(dir,'public.pem');writeFileSync(pem,keys.publicKey.export({type:'spki',format:'pem'}));
    const env=resolve(dir,'runtime.env');writeFileSync(env,`SPRING_DATASOURCE_URL=jdbc:postgresql://db:5432/synkro\nSPRING_DATASOURCE_USERNAME=customers_app\nSPRING_DATASOURCE_PASSWORD=${password}\nJWT_PUBLIC_KEY_FILE=/run/public.pem\n`,{mode:0o600});
    const input=Buffer.from(JSON.stringify({alg:'RS256'})).toString('base64url')+'.'+Buffer.from(JSON.stringify({sub:randomUUID(),roles:['ADMIN'],permissions:[],iat:Math.floor(Date.now()/1000)-1,exp:Math.floor(Date.now()/1000)+3600,jti:randomUUID()})).toString('base64url');
    const token=input+'.'+sign('RSA-SHA256',Buffer.from(input),keys.privateKey).toString('base64url');
    try{
        docker(['create','--name',name,'--label',`synkro.execution=${prefix}`,'--network',network,'--env-file',env,'--publish','127.0.0.1::8080','--mount',`type=bind,source=${pem},target=/run/public.pem,readonly`,'eclipse-temurin:21-jre-alpine','java','-jar','/app.jar'],{quiet:true});
        docker(['cp',resolve(root,'customers-app/target/customers-app-0.1.0-SNAPSHOT.jar'),`${name}:/app.jar`],{quiet:true});docker(['start',name],{quiet:true});
        const binding=JSON.parse(docker(['inspect',name],{quiet:true}).stdout)[0].NetworkSettings.Ports['8080/tcp'][0];let base=`http://127.0.0.1:${binding.HostPort}`;
        async function request(path,options={}){const r=await fetch(base+path,{signal:AbortSignal.timeout(20000),...options});return {status:r.status,body:await r.json()};}
        async function ready(stage){for(let i=0;i<60;i++){try{if((await request('/health')).status===200)return;}catch{}await new Promise(r=>setTimeout(r,1000));}throw Error('Runtime readiness failed: '+stage);}
        await ready('initial startup');console.log('Runtime initial startup: PASS');
        const created=await request('/api/v1/customers',{method:'POST',headers:{Authorization:`Bearer ${token}`,'Content-Type':'application/json','Idempotency-Key':randomUUID()},body:JSON.stringify({name:'Restart synthetic',identityDocument:randomUUID().slice(0,25)})});assert.equal(created.status,201);
        docker(['restart','--time','30',name],{quiet:true});
        // Docker reassigns an ephemeral published port on restart; inspect it again.
        const restarted=JSON.parse(docker(['inspect',name],{quiet:true}).stdout)[0].NetworkSettings.Ports['8080/tcp'][0];
        base=`http://127.0.0.1:${restarted.HostPort}`;await ready('API restart');console.log('Runtime API restart: PASS');
        const read=await request('/api/v1/customers/'+created.body.customerId,{headers:{Authorization:`Bearer ${token}`}});assert.equal(read.status,200);assert.deepEqual(read.body,created.body);
        docker(['network','disconnect',network,db],{quiet:true});
        try{const health=await request('/health');assert.equal(health.status,503);assert.equal(health.body.status,'degraded');assert.equal(health.body.dependencies.database,'down');
            const failed=await request('/api/v1/customers/'+created.body.customerId,{headers:{Authorization:`Bearer ${token}`,'X-Correlation-Id':'runtime-storage-failure'}});assert.equal(failed.status,500);assert.equal(failed.body.error,'INTERNAL_ERROR');assert.equal(failed.body.traceId,'runtime-storage-failure');}
        finally{docker(['network','connect','--alias','db',network,db],{quiet:true});}
        await ready('database reconnection');assert.equal((await request('/api/v1/customers/'+created.body.customerId,{headers:{Authorization:`Bearer ${token}`}})).status,200);
        console.log('Actual executable API: restart persistence, connectivity loss/503, safe storage error and recovery: PASS');
    }catch(e){const logs=docker(['logs',name],{quiet:true,allowFailure:true});writeFileSync(resolve(root,'.test-cache/runtime-diagnostic.log'),(logs.stdout+logs.stderr).replaceAll(password,'[REDACTED]'));throw e;}
    finally{const result=docker(['inspect',name],{quiet:true,allowFailure:true});if(result.status===0){if(JSON.parse(result.stdout)[0].Config.Labels?.['synkro.execution']!==prefix)throw Error('Unowned runtime cleanup refused');if(docker(['rm','-fv',name],{quiet:true,allowFailure:true}).status!==0)throw Error('Runtime cleanup failed');}}
}
