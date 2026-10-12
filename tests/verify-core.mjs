// First review boundary: full reactor and original HTTP skeleton, not lifecycle acceptance.
import {spawnSync} from 'node:child_process';
import {randomUUID} from 'node:crypto';
import {mkdirSync,writeFileSync} from 'node:fs';
import {resolve} from 'node:path';
const root=resolve(import.meta.dirname,'..'),owner=`customers-core-review-${randomUUID().replaceAll('-','')}`;
function docker(args,allowFailure=false){
    const r=spawnSync('docker',args,{encoding:'utf8',timeout:900000,maxBuffer:32*1024*1024});
    if(!allowFailure&&(r.status!==0||r.error))throw Error(`Core review operation failed: ${args[0]}`);
    return r;
}
try {
    docker(['create','--name',owner,'--label',`synkro.execution=${owner}`,'-w','/src','maven:3.9-eclipse-temurin-21','mvn','-B','-Dmaven.repo.local=/tmp/m2','clean','verify']);
    for(const name of ['pom.xml','customers-core','customers-adapters','customers-app'])docker(['cp',resolve(root,name),`${owner}:/src/${name}`]);
    docker(['cp',resolve(root,'.test-cache/m2'),`${owner}:/tmp/m2`],true);
    const r=docker(['start','-a',owner],true);
    mkdirSync(resolve(root,'.test-cache'),{recursive:true});writeFileSync(resolve(root,'.test-cache/review-core.log'),r.stdout+r.stderr);
    console.log((r.stdout+r.stderr).split('\n').filter(line=>!line.includes('Downloading from')&&!line.includes('Downloaded from')).slice(-100).join('\n'));
    docker(['cp',`${owner}:/tmp/m2/.`,resolve(root,'.test-cache/m2')],true);
    for(const name of ['customers-core','customers-adapters','customers-app'])docker(['cp',`${owner}:/src/${name}/target`,resolve(root,name)]);
    if(r.status!==0||r.error)throw Error('Core reactor verification failed');
    await import('./check-quality.mjs');
} finally {
    const r=docker(['inspect',owner],true);
    if(r.status===0){if(JSON.parse(r.stdout)[0].Config.Labels?.['synkro.execution']!==owner)throw Error('Unowned cleanup refused');docker(['rm','-fv',owner]);}
    console.log('Owned core review fixture cleanup: PASS');
}
