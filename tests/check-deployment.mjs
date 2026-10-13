import {spawnSync} from 'node:child_process';
import {randomUUID} from 'node:crypto';
import {resolve} from 'node:path';
import {tmpdir} from 'node:os';
import assert from 'node:assert/strict';

const root=resolve(import.meta.dirname,'..');
const owner=`customers-image-test-${randomUUID().replaceAll('-','')}`, image=owner+':review';
function docker(args,options={}) {
    const result=spawnSync('docker',args,{cwd:root,encoding:'utf8',timeout:900000,maxBuffer:32*1024*1024,...options});
    if(result.status!==0||result.error)throw Error(`Deployment verification failed: docker ${args[0]}`);
    return result.stdout;
}
const environment={...process.env,SPRING_DATASOURCE_URL:'jdbc:postgresql://synkro-db:5432/synkro',
    SPRING_DATASOURCE_PASSWORD:'synthetic-config-only',JWT_PUBLIC_KEY:'synthetic-config-only',
    CUSTOMERS_APP_PASSWORD_FILE:resolve(tmpdir(),'synthetic-customers-password'),
    JWT_PUBLIC_KEY_HOST_FILE:resolve(tmpdir(),'synthetic-auth-public-key'),PLATFORM_NETWORK:'platform'};
function compose(files) {
    return JSON.parse(docker(['compose',...files.flatMap(file=>['-f',file]),'config','--format','json'],{env:environment,timeout:30000}));
}
function internal(config) {
    const service=config.services['synkro-customers-api'];
    assert.equal(service.environment.SPRING_DATASOURCE_USERNAME,'customers_app');
    assert.equal(service.build.dockerfile,'deploy/Dockerfile');
    assert.ok(!service.ports?.length);assert.ok(!service.volumes?.length);assert.ok(!service.privileged);
    assert.deepEqual(service.expose.map(String),['8080']);
    assert.equal(config.networks.platform.external,true);assert.equal(config.networks.platform.name,'platform');
    assert.ok(!Object.keys(service.environment).some(key=>/PRIVATE|ADMIN|MIGRAT/i.test(key)));
    return service;
}
internal(compose(['deploy/compose.yml']));
const protectedConfig=compose(['deploy/compose.yml','deploy/compose.secrets.yml']);
const protectedService=internal(protectedConfig);
assert.equal(protectedService.environment.SPRING_DATASOURCE_PASSWORD_FILE,'/run/secrets/customers_app_password');
assert.equal(protectedService.environment.JWT_PUBLIC_KEY_FILE,'/run/secrets/jwt_public_key');
assert.equal(protectedService.secrets.length,2);
assert.equal(protectedConfig.secrets.customers_app_password.file,environment.CUSTOMERS_APP_PASSWORD_FILE);
assert.equal(protectedConfig.secrets.jwt_public_key.file,environment.JWT_PUBLIC_KEY_HOST_FILE);
console.log('Internal deployment and protected-file composition: PASS (synthetic configuration only)');
try {
    docker(['build','--label',`synkro.execution=${owner}`,'-t',image,'-f','deploy/Dockerfile','.']);
    const built=JSON.parse(docker(['image','inspect',image]))[0];
    assert.equal(built.Config.User,'synkro');
    assert.deepEqual(built.Config.Entrypoint,['java','-jar','/app/customers-app.jar']);
    assert.ok(!built.Config.Env.some(value=>/PASSWORD=|PRIVATE_KEY=/.test(value)));
    console.log('Owning Dockerfile image build and non-root runtime: PASS');
} finally {
    const result=spawnSync('docker',['image','inspect',image],{encoding:'utf8',timeout:30000});
    if(result.status===0) {
        assert.equal(JSON.parse(result.stdout)[0].Config.Labels?.['synkro.execution'],owner,'Unowned image cleanup refused');
        docker(['image','rm',image],{timeout:30000});
    }
    console.log(`Owned deployment image cleanup: PASS (${owner})`);
}
