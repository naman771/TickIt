#!/usr/bin/env node
// Read-only public release checks. No tokens, private data, catalog writes or provider mutations.
const [customer,organizer]=process.argv.slice(2);
if(!customer||!organizer)throw new Error('Usage: node scripts/verify-release.mjs CUSTOMER_ORIGIN ORGANIZER_ORIGIN');
for(const value of [customer,organizer]){
  const u=new URL(value);if(u.username||u.password||u.search||u.hash||u.pathname!=='/' || !['https:','http:'].includes(u.protocol))throw new Error('Pass origins without credentials or paths');
  if(u.protocol==='http:'&&!['localhost','127.0.0.1'].includes(u.hostname))throw new Error('Remote origins require HTTPS');
}
let failed=0;
async function check(label,base,path,validate){
  try{
    const res=await fetch(new URL(path,base),{redirect:'error',signal:AbortSignal.timeout(45000)});
    if(!await validate(res))throw new Error(`unexpected HTTP ${res.status}`);
    console.log(`PASS ${label}`);
  }catch(error){failed++;console.log(`FAIL ${label}: ${error.message}`);}
}
await check('backend health',customer,'/actuator/health',async r=>r.ok&&(await r.json()).status==='UP');
await check('connected identity',customer,'/api/public/config',async r=>r.ok&&(await r.json()).authProvider==='clerk');
await check('customer ticket client',customer,'/js/tickets.js',async r=>r.ok&&(await r.text()).includes('data-ticket-print'));
await check('organizer operations',organizer,'/operations.js',async r=>r.ok&&(await r.text()).includes('data-staff-revoke'));
await check('entry workspace',organizer,'/entry.html',async r=>r.ok&&(await r.text()).includes('entryWorkspace'));
for(const path of ['/api/tickets/22222222-2222-4222-8222-222222222222','/api/entry/events','/api/organizer/events/1/operations','/api/developer/notifications','/api/developer/payments/refunds'])
  await check(`anonymous rejection ${path}`,customer,path,r=>r.status===401);
console.log(`${failed?'INCOMPLETE':'PASS'}: public assets and anonymous access checks only; authenticated acceptance is separate.`);
process.exitCode=failed?1:0;
