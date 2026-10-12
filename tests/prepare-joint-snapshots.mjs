import {spawnSync} from 'node:child_process';
import {mkdirSync,writeFileSync} from 'node:fs';
import {resolve} from 'node:path';

const root=resolve(import.meta.dirname,'../.local-reference');mkdirSync(root,{recursive:true});
const revisions={
    'synkro-auth-api':'04ae6ab92daf474fb5c38ae37842d1690e7584b0',
    'synkro-products-api':'b6b379b314716e201833be0d854953a69452559f',
    'synkro-sales-api':'70bdbaba3783bfb974c338b8a460e6e9b7e5c8dd',
    'synkro-workflow':'45aa244251851fda46212c5b6338668daaa683be',
    'synkro-auth-db':'5e481dba156db6f2febb00427efba637002d9a0c',
    'synkro-products-db':'0beae79920d9af83865bdebaf6b316a2b2a57592',
    'synkro-sales-db':'9cfbde7db4bea7bacad70370c60f91f8eb917c04'
};
for(const [repo,sha] of Object.entries(revisions)){
    const result=spawnSync('gh',['api',`repos/code-corhuila/${repo}/tarball/${sha}`],{timeout:60000,maxBuffer:32*1024*1024});
    if(result.status!==0)throw Error(`Read access unavailable for ${repo}@${sha}; joint acceptance BLOCKED`);
    const archive=resolve(root,repo+'.tar.gz');writeFileSync(archive,result.stdout);
    const extract=spawnSync('tar',['-xzf',archive,'-C',root],{timeout:60000});if(extract.status!==0)throw Error(`Snapshot extraction failed: ${repo}`);
    console.log(`Read-only approved snapshot: ${repo}@${sha}`);
}
