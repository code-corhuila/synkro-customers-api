import { Verifier } from '@pact-foundation/pact'
import { readdirSync } from 'node:fs'
import { resolve } from 'node:path'
import { createServer } from 'node:http'
import assert from 'node:assert/strict'
import { randomUUID } from 'node:crypto'
import './provenance.mjs'

const base=process.env.PROVIDER_BASE_URL
const token=process.env.PACT_ADMIN_TOKEN
assert.ok(base && token,'Owned provider URL and token are required')
const files=readdirSync('contracts').filter(n=>n.endsWith('.json')).map(n=>resolve('contracts',n))
assert.equal(files.length,2,'Both consumer artifacts are required')
async function request(path,method='GET',body) {
  const controller=new AbortController(), timer=setTimeout(()=>controller.abort(),20000)
  try {
    const response=await fetch(base+path,{method,signal:controller.signal,headers:{Authorization:`Bearer ${token}`,
      'Content-Type':'application/json','Idempotency-Key':randomUUID()},body:body===undefined?undefined:JSON.stringify(body)})
    return {status:response.status,body:await response.json()}
  } finally {clearTimeout(timer);controller.abort()}
}
async function ensure(name,document) {
  for(const status of ['ACTIVE','INACTIVE']) {
    const found=await request('/api/v1/customers/search','POST',{query:document,status})
    assert.equal(found.status,200)
    const item=found.body.data.find(x=>x.identityDocument===document)
    if(item)return item
  }
  const created=await request('/api/v1/customers','POST',{name,identityDocument:document,email:'pact@example.test',phone:'3001234567',address:'Synthetic street'})
  assert.equal(created.status,201)
  return created.body
}
async function inactive(name,document) {
  const customer=await ensure(name,document)
  if(customer.active)assert.equal((await request('/api/v1/customers/'+customer.customerId,'DELETE')).status,200)
  return {customerId:customer.customerId}
}
const states={
  'two folded exact identities exist':async()=>{
    await ensure('Synthetic Alpha','ABC123');await ensure('Synthetic Beta','abc123')
    // Earlier HTTP regression tests may leave weaker matches in this disposable database.
    const found=await request('/api/v1/customers/search','POST',{query:'abc123',status:'ACTIVE'})
    for(const item of found.body.data.filter(x=>x.match!=='DOCUMENT_EXACT'))
      assert.equal((await request('/api/v1/customers/'+item.customerId,'DELETE')).status,200)
  },
  'six folded exact identities exist':async()=>{for(const [i,p] of ['DEF','DEf','DeF','Def','dEF','def'].entries())await ensure('Candidate '+i,p+'789')},
  'one exact identity exists':async()=>{await ensure('Unique Synthetic','ONE456')},
  'no customer matches the query':async()=>{assert.equal((await request('/api/v1/customers/search','POST',{query:'zzpactnone',status:'ACTIVE'})).body.data.length,0)},
  'no customer has PACT-PORTAL-NEW':async()=>{},
  'DUP-PACT123 is already registered':async()=>{await ensure('Existing Duplicate','DUP-PACT123')},
  'one inactive pact customer exists':()=>inactive('Pact Reactivation','PACT-INACTIVE'),
  'a distinct inactive search customer exists':()=>inactive('Pact Inactive Lookup','PACT-LOOKUP-INACTIVE'),
}
const options={pactUrls:files,provider:'CustomersAPI',logLevel:'error'}
// The deliberately incompatible provider must fail. Its responses are never substituted for the real verification.
let wrongRequests=0
const wrong=createServer((req,res)=>{wrongRequests++;req.resume();res.setHeader('Content-Type','application/json');res.end(JSON.stringify({wrongContract:true}))})
await new Promise(r=>wrong.listen(0,'127.0.0.1',r))
try {
  await assert.rejects(()=>new Verifier({...options,providerBaseUrl:'http://127.0.0.1:'+wrong.address().port,
    stateHandlers:Object.fromEntries(Object.keys(states).map(s=>[s,async()=>({customerId:'550e8400-e29b-41d4-a716-446655440001'})]))}).verifyProvider())
  assert.ok(wrongRequests>=8,'Negative control must reach the incompatible HTTP provider, not fail during setup')
  console.log('Pact negative control: incompatible provider rejected')
} finally {await new Promise(r=>wrong.close(r))}
await new Verifier({...options,providerBaseUrl:base,stateHandlers:states,
  requestFilter:(req,res,next)=>{req.headers.authorization=`Bearer ${token}`;next()}}).verifyProvider()
console.log('Pact provider: both generated consumer contracts verified against real Customers API/PostgreSQL')
