import {test} from 'node:test';
import assert from 'node:assert/strict';
import {verifyRoleReport} from './check-role-boundaries.mjs';

const names=['inventoryCannotListCustomers','inventoryCannotReplaceCustomer','workflowReadPermissionCannotListOrModifyCustomers','signedAuthenticationAndFullRoleServiceMatrix'];
const report=(cases=names.map(name=>`<testcase name="${name}"/>`))=>`<testsuite failures="0" errors="0" skipped="0">${cases.join('')}</testsuite>`;
test('accepts every executed role regression',()=>assert.doesNotThrow(()=>verifyRoleReport(report())));
test('rejects a missing role regression',()=>assert.throws(()=>verifyRoleReport(report(names.slice(1).map(name=>`<testcase name="${name}"/>`)))));
for(const failure of ['skipped','failure','error']) test(`rejects ${failure} in a required test`,()=>{
    const cases=names.map(name=>`<testcase name="${name}"><${failure}/></testcase>`);
    assert.throws(()=>verifyRoleReport(report(cases)));
});
test('rejects a failed suite',()=>assert.throws(()=>verifyRoleReport(report().replace('failures="0"','failures="1"'))));
