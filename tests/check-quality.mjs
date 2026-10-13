import {readFileSync,readdirSync} from 'node:fs';
import {resolve,join} from 'node:path';
import {createHash} from 'node:crypto';
const root=resolve(import.meta.dirname,'..');
function files(dir){return readdirSync(dir,{withFileTypes:true}).flatMap(e=>e.isDirectory()?files(join(dir,e.name)):[join(dir,e.name)]);}
const core=readFileSync(resolve(root,'customers-core/pom.xml'),'utf8');
for(const match of core.matchAll(/<dependency>([\s\S]*?)<\/dependency>/g))if(!match[1].includes('<scope>test</scope>'))throw Error('Core must have no framework/database compile dependency');
for(const file of files(resolve(root,'customers-core/src/main/java')))if(/import\s+(org\.springframework|jakarta\.|javax\.sql|java\.sql|org\.postgresql)/.test(readFileSync(file,'utf8')))throw Error('Core architecture violation');
const manifest=JSON.parse(readFileSync(resolve(root,'tests/fixtures/customers-db/SHA256.json'),'utf8'));
for(const [file,hash] of Object.entries(manifest))if(createHash('sha256').update(readFileSync(resolve(root,'tests/fixtures/customers-db',file))).digest('hex')!==hash)throw Error('Approved DB snapshot changed: '+file);
const xml=readFileSync(resolve(root,'customers-app/target/site/jacoco-aggregate/jacoco.xml'),'utf8');
const totals={domain:[0,0],application:[0,0],infrastructure:[0,0],global:[0,0]};
for(const match of xml.matchAll(/<package name="([^"]+)">([\s\S]*?)<\/package>/g)){
    const counts=[...match[2].matchAll(/<counter type="LINE" missed="(\d+)" covered="(\d+)"\/>/g)].at(-1);if(!counts)continue;
    const missed=Number(counts[1]),covered=Number(counts[2]),layer=match[1].includes('/domain/')?'domain':match[1].includes('/application/')?'application':'infrastructure';
    for(const key of [layer,'global']){totals[key][0]+=covered;totals[key][1]+=missed;}
}
for(const [layer,min] of Object.entries({domain:.9,application:.8,infrastructure:.6,global:.8})){
    const [covered,missed]=totals[layer];const ratio=covered/(covered+missed);if(!Number.isFinite(ratio)||ratio<min)throw Error(`${layer} coverage below ${min*100}%`);
    console.log(`${layer}: ${covered}/${covered+missed} lines (${(ratio*100).toFixed(2)}%), threshold ${min*100}%: PASS`);
}
console.log('Core architecture and immutable Customers DB fixture: PASS');
