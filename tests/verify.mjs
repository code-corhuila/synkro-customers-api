import {spawnSync} from 'node:child_process';
import {randomBytes,randomUUID} from 'node:crypto';
import {mkdirSync,writeFileSync,rmSync} from 'node:fs';
import {resolve} from 'node:path';
import {waitUntilReady} from './readiness.mjs';
import {cleanupOwned,runWithCleanup} from './harness.mjs';

const root=resolve(import.meta.dirname,'..');
const prefix=`customers-api-test-${randomUUID().replaceAll('-','')}`;
const db=`${prefix}-db`, build=`${prefix}-build`, network=prefix;
const admin=randomBytes(24).toString('hex'), password=randomBytes(24).toString('hex');
const dir=resolve(root,'.test-run',prefix);mkdirSync(dir,{recursive:true});
const envfile=resolve(dir,'fixture.env');
writeFileSync(envfile,`POSTGRES_PASSWORD=${admin}\nPOSTGRES_DB=synkro\nFLYWAY_USER=postgres\nFLYWAY_PASSWORD=${admin}\nFLYWAY_URL=jdbc:postgresql://db:5432/synkro?connectTimeout=3&socketTimeout=10\nTEST_DATABASE_URL=jdbc:postgresql://db:5432/synkro\nTEST_DATABASE_PASSWORD=${password}\nTEST_ADMIN_PASSWORD=${admin}\n`,{mode:0o600});
function docker(args,{input,timeout=300000,quiet=false,allowFailure=false}={}){
    const r=spawnSync('docker',args,{input,encoding:'utf8',timeout,maxBuffer:16*1024*1024});
    if(!quiet){process.stdout.write(r.stdout??'');process.stderr.write(r.stderr??'');}
    if(!allowFailure&&(r.status!==0||r.error))throw new Error(`Docker operation failed (${args[0]}); see sanitized output`);
    return r;
}
// The exit code is decided by runWithCleanup (tests/harness.mjs): non-zero when any phase or the cleanup fails; the cleanup
// of this run's own resources always runs, in success and in failure (it removes only resources labelled with this prefix).
process.exitCode=await runWithCleanup({
    run:async()=>{
        docker(['network','create','--label',`synkro.execution=${prefix}`,network],{quiet:true});
        docker(['run','-d','--name',db,'--label',`synkro.execution=${prefix}`,'--network',network,'--network-alias','db','--env-file',envfile,'--tmpfs','/var/lib/postgresql/data','postgres:16.6-alpine'],{quiet:true});
        // Explicit, bounded wait (60 s); each probe is one short pg_isready; only the probe is retried.
        await waitUntilReady({label:'PostgreSQL',deadlineMs:60000,attemptTimeoutMs:10000,intervalMs:1000,
            probe:async()=>docker(['exec',db,'pg_isready','-U','postgres'],{quiet:true,allowFailure:true,timeout:8000}).status===0});
        docker(['exec','-i',db,'psql','-U','postgres','-d','synkro','-v','ON_ERROR_STOP=1'],{quiet:true,input:`REVOKE CREATE ON SCHEMA public FROM PUBLIC; CREATE ROLE customers_app LOGIN INHERIT PASSWORD '${password}' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS; ALTER ROLE customers_app SET search_path=customers_schema; CREATE SCHEMA sentinel; REVOKE ALL ON SCHEMA sentinel FROM PUBLIC; CREATE TABLE sentinel.private(id int);`});
        for(const operation of ['migrate','validate'])docker(['run','--rm','--network',network,'--env-file',envfile,'--mount',`type=bind,source=${resolve(root,'tests/fixtures/customers-db')},target=/workspace,readonly`,'--entrypoint','/bin/sh','flyway/flyway:10.20.1','/workspace/scripts/migrate.sh',operation],{timeout:180000});
        docker(['create','--name',build,'--label',`synkro.execution=${prefix}`,'--network',network,'--env-file',envfile,'-w','/src','maven:3.9-eclipse-temurin-21','mvn','-B','-Dmaven.repo.local=/tmp/m2','clean','verify',...process.argv.slice(2)],{quiet:true});
        // Copy only implementation inputs; run from Linux storage, not a Windows bind-mounted classpath.
        for(const name of ['pom.xml','customers-core','customers-adapters','customers-app'])docker(['cp',resolve(root,name),`${build}:/src/${name}`],{quiet:true});
        docker(['cp',resolve(root,'.test-cache/m2'),`${build}:/tmp/m2`],{quiet:true,allowFailure:true});
        const r=docker(['start','-a',build],{timeout:900000,allowFailure:true,quiet:true});
        const output=(r.stdout+r.stderr).replaceAll(admin,'[REDACTED]').replaceAll(password,'[REDACTED]');
        mkdirSync(resolve(root,'.test-cache'),{recursive:true});writeFileSync(resolve(root,'.test-cache/last-verification.log'),output);
        console.log(output.split('\n').filter(line=>!line.includes('Downloading from')&&!line.includes('Downloaded from')).slice(-130).join('\n'));
        // Cache is optional; failures do not affect tests or correctness.
        docker(['cp',`${build}:/tmp/m2/.`,resolve(root,'.test-cache/m2')],{quiet:true,allowFailure:true});
        for(const name of ['customers-core','customers-adapters','customers-app'])docker(['cp',`${build}:/src/${name}/target`,resolve(root,name)],{quiet:true,allowFailure:true});
        if(r.status!==0)throw new Error('Maven verification failed');
        await import('./check-quality.mjs');
        if(!process.argv.some(arg=>arg.startsWith('-Dtest=')))await (await import('./runtime.mjs')).verifyRuntime({root,dir,prefix,network,db,password,docker});
        console.log('Disposable PostgreSQL, approved runner, build and tests: PASS');
    },
    cleanup:()=>{
        const cleaned=cleanupOwned({prefix,containers:[build,db],networks:[network],docker});
        rmSync(dir,{recursive:true,force:true});console.log(`Fixture cleanup: ${cleaned?'PASS':'CHECK REQUIRED'} (${prefix})`);
        return cleaned;
    }
});
