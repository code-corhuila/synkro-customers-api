import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {resolve} from 'node:path';
import {pathToFileURL} from 'node:url';

export function verifyRoleReport(xml){
    for(const attribute of ['failures','errors','skipped'])
        assert.match(xml,new RegExp(`\\b${attribute}="0"`),`Lifecycle suite ${attribute} must be zero`);
    const cases=[...xml.matchAll(/<testcase\b([^>]*?)(?:\/>|>([\s\S]*?)<\/testcase>)/g)];
    for(const name of ['inventoryCannotListCustomers','inventoryCannotReplaceCustomer','workflowReadPermissionCannotListOrModifyCustomers','signedAuthenticationAndFullRoleServiceMatrix']){
        const executed=cases.filter(([,attributes])=>attributes.includes(`name="${name}"`));
        assert.equal(executed.length,1,`Required lifecycle test missing or duplicated: ${name}`);
        assert.doesNotMatch(executed[0][2]??'',/<(?:skipped|failure|error)\b/,`Required test did not pass: ${name}`);
    }
}
if(process.argv[1]&&import.meta.url===pathToFileURL(resolve(process.argv[1])).href){
    const report=new URL('../customers-app/target/surefire-reports/TEST-co.edu.corhuila.synkro.customers.app.LifecycleHttpTest.xml',import.meta.url);
    verifyRoleReport(readFileSync(report,'utf8'));
    console.log('Customers role gate: INVENTORY list/update, SERVICE restrictions and existing signature/role matrix executed together without skips');
}
