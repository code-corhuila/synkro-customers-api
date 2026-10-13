// Conservative production count includes build/configuration; test code and prose are reported separately.
import {spawnSync} from 'node:child_process';
const [base,head='HEAD']=process.argv.slice(2);if(!base)throw Error('Usage: node tests/measure-review.mjs BASE [HEAD]');
const r=spawnSync('git',['diff','--numstat',base,head],{encoding:'utf8'});if(r.status!==0)throw Error('Review diff unavailable');
const counts={production:{added:0,deleted:0},tests:{added:0,deleted:0},documentation:{added:0,deleted:0}};
for(const row of r.stdout.trim().split('\n').filter(Boolean)){
    const [added,deleted,path]=row.split('\t');if(added==='-'||deleted==='-')throw Error('Binary diff requires explicit review: '+path);
    const category=path.startsWith('tests/')||path.includes('/src/test/')?'tests':path.startsWith('docs/')||path==='README.md'?'documentation':'production';
    counts[category].added+=Number(added);counts[category].deleted+=Number(deleted);
}
const total=counts.production.added+counts.production.deleted;
console.log(JSON.stringify({base,head,counts,productionTotal:total,limit:400,pass:total<=400},null,2));
if(total>400)process.exitCode=1;
