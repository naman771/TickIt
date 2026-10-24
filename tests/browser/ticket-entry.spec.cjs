const {test,expect}=require('@playwright/test');
const origin='http://127.0.0.1:4180';
const order='11111111-1111-4111-8111-111111111111';
const ids=[1,2,3].map(n=>`22222222-2222-4222-8222-22222222222${n}`);
const event={id:901,name:'An Evening Together',slug:'an-evening-together-mumbai',status:'PUBLISHED',venueName:'The Assembly Hall',city:'Mumbai',startTime:'2099-06-01T19:00:00',startInstant:'2099-06-01T13:30:00Z',timezone:'Asia/Kolkata'};
const png=Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aX1cAAAAASUVORK5CYII=','base64');
async function setup(page){
  const errors=[];page.on('pageerror',e=>errors.push(e.message));
  await page.route('**/*',r=>new URL(r.request().url()).origin===origin?r.continue():r.abort());
  await page.route('**/js/auth.js',r=>r.fulfill({contentType:'text/javascript',body:`window.TickItAuth={user:{id:101,name:'Aditi'},config:{checkoutEnabled:false},token:async()=>'fixture',onChange(fn){(this.listeners??=[]).push(fn)},init:async()=>{},open(){},async signOut(){this.user=null;this.listeners.forEach(f=>f(null))}};`}));
  await page.route('**/organizer/config.js',r=>r.fulfill({contentType:'text/javascript',body:'window.TICKIT_API_BASE="";'}));
  return errors;
}
function admission(id,n,status='VALID'){return {id,orderId:order,eventId:901,label:'Floor · Regular',ordinal:n,status,checkedInAt:null};}
async function customer(page,status='VALID',delay=0){
  const errors=await setup(page);let qrRequests=0;
  await page.route('**/api/**',async r=>{
    const p=new URL(r.request().url()).pathname;
    if(p==='/api/events'||p==='/api/bookings/me')return r.fulfill({json:[]});
    if(p==='/api/payments/orders/me')return r.fulfill({json:[{id:order,eventId:901,status:'CONFIRMED',amount:150000,currency:'INR',event,items:[{label:'Floor · Regular',quantity:3,unitAmount:50000}],tickets:ids.map((id,n)=>admission(id,n+1,status))}]});
    if(p.endsWith('/qr')){qrRequests++;if(delay)await new Promise(resolve=>setTimeout(resolve,delay));return r.fulfill({contentType:'image/png',body:png});}
    if(p.startsWith('/api/tickets/'))return r.fulfill({json:{ticket:admission(p.split('/')[3],1,status),event,credential:status==='VALID'?'tickit:v1:'+'A'.repeat(43):null}});
    return r.fulfill({status:404,json:{error:'Unknown fixture route'}});
  });
  await page.goto('/#tickets');return {errors,qrRequests:()=>qrRequests};
}
test('standing admissions have separate QR tickets, downloads and printable tickets',async({page})=>{
  const f=await customer(page);
  await expect(page.locator('.ticket-card')).toHaveCount(3);
  await page.getByRole('button',{name:'View ticket'}).first().click();
  await expect(page.locator('.admission-dialog .admission-qr')).toBeVisible();
  await expect(page.locator('.admission-dialog')).toContainText('One guest · One entry');
  await expect(page.getByRole('link',{name:'Download QR'})).toHaveAttribute('href',/^blob:/);
  const popup=page.waitForEvent('popup');await page.getByRole('button',{name:'Print / save PDF',exact:true}).click();
  const print=await popup;await expect(print.getByRole('heading',{name:event.name})).toBeVisible();await expect(print.locator('img')).toBeVisible();await print.close();
  expect(page.url()).not.toContain('tickit:v1:');expect(f.qrRequests()).toBe(1);expect(f.errors).toEqual([]);
});
test('inactive admission retains history without presenting a usable QR',async({page})=>{
  const f=await customer(page,'INACTIVE');await page.getByRole('button',{name:'View ticket'}).first().click();
  await expect(page.locator('.admission-dialog')).toContainText('This admission is inactive');await expect(page.locator('.admission-qr')).toHaveCount(0);expect(f.qrRequests()).toBe(0);expect(f.errors).toEqual([]);
});
test('signing out during ticket loading removes the private ticket dialog',async({page})=>{
  const f=await customer(page,'VALID',300);await page.getByRole('button',{name:'View ticket'}).first().click();
  await expect(page.locator('.admission-dialog')).toBeVisible();await page.evaluate(()=>TickItAuth.signOut());
  await expect(page.locator('.admission-dialog')).toHaveCount(0);await page.waitForTimeout(400);await expect(page.locator('.admission-dialog')).toHaveCount(0);expect(f.errors).toEqual([]);
});
async function entry(page,failure=false){
  const errors=await setup(page);let scans=0;
  await page.route('**/api/**',r=>{
    const p=new URL(r.request().url()).pathname;
    if(p==='/api/entry/events')return r.fulfill({json:[event]});
    if(p.endsWith('/check-in')||p.endsWith('/check-in/image')){scans++;return failure?r.fulfill({status:503,json:{error:'Entry service unavailable. Do not admit until validation succeeds.'}}):r.fulfill({json:{outcome:scans===1?'ADMITTED':'ALREADY_USED',label:'Floor · Regular',checkedInAt:new Date().toISOString()}});}
    return r.fulfill({status:404,json:{error:'Unknown fixture route'}});
  });
  await page.goto('/organizer/entry.html#event/901');await expect(page.getByRole('heading',{name:'Welcome them in.'})).toBeVisible();return {errors,scans:()=>scans};
}
test('entry staff validate a ticket and see a clear repeat-scan rejection',async({page})=>{
  const f=await entry(page);const input=page.locator('#entryCode input');
  await input.fill('tickit:v1:'+'A'.repeat(43));await page.getByRole('button',{name:'Validate and admit'}).click();
  await expect(page.locator('#scanResult')).toContainText('Entry confirmed');await expect(input).toHaveValue('');
  await input.fill('tickit:v1:'+'A'.repeat(43));await page.getByRole('button',{name:'Validate and admit'}).click();
  await expect(page.locator('#scanResult')).toContainText('Already checked in');await expect(page.locator('#scanResult')).not.toHaveClass(/admitted/);expect(f.scans()).toBe(2);expect(f.errors).toEqual([]);
});
test('entry upload works without camera support and network failure never confirms entry',async({page})=>{
  const f=await entry(page,true);await page.locator('#entryFile').setInputFiles({name:'ticket.png',mimeType:'image/png',buffer:png});
  await expect(page.locator('#scanResult')).toContainText('Do not admit until validation succeeds');await expect(page.locator('#scanResult')).not.toHaveClass(/admitted/);expect(f.scans()).toBe(1);expect(f.errors).toEqual([]);
});
test('organizer reports support staff grants, revocation and private CSV export',async({page})=>{
  const errors=await setup(page);let grants=0,revokes=0;
  await page.route('**/api/**',r=>{
    const url=new URL(r.request().url()),p=url.pathname;
    if(p==='/api/organizer/profile')return r.fulfill({json:{id:101,displayName:'Fixture events'}});
    if(p==='/api/organizer/events')return r.fulfill({json:[event]});
    if(p.endsWith('/operations'))return r.fulfill({json:{financials:{captured_orders:1,captured_amount:150000,refunded_amount:0,outstanding_refund_amount:0},admissions:{issued:3,checked_in:1},guests:[{id:ids[0],ordinal:1,label:'Floor · Regular',order_id:order,full_name:'Aditi',email:'aditi@tickit.test',event_status:'PUBLISHED',refund_status:null,checked_in_at:null}],staff:grants>revokes?[{id:202,full_name:'Entry team',email:'entry@tickit.test'}]:[],scans:[]}});
    if(p.endsWith('/staff')){grants++;return r.fulfill({json:[]});}
    if(p.endsWith('/staff/202')){revokes++;return r.fulfill({json:[]});}
    if(p.endsWith('/guests.csv'))return r.fulfill({contentType:'text/csv',body:'Guest,Admission\r\nAditi,Floor\r\n'});
    return r.fulfill({status:404,json:{error:'Unknown fixture route'}});
  });
  await page.goto('/organizer/#operations/901');await expect(page.getByRole('heading',{name:'Ready for opening night.'})).toBeVisible();
  await page.getByLabel('Verified staff email').fill('entry@tickit.test');await page.getByRole('button',{name:'Grant check-in access'}).click();
  await expect(page.getByRole('button',{name:'Revoke'})).toBeVisible();await page.getByRole('button',{name:'Revoke'}).click();await expect(page.getByRole('button',{name:'Revoke'})).toHaveCount(0);
  const download=page.waitForEvent('download');await page.getByRole('button',{name:'Download this page CSV'}).click();expect((await download).suggestedFilename()).toBe('tickit-guests-page-1.csv');expect(grants).toBe(1);expect(revokes).toBe(1);expect(errors).toEqual([]);
});
test('developer delivery queue shows disabled mail and durable pending jobs honestly',async({page})=>{
  const errors=await setup(page);
  await page.route('**/api/developer/**',r=>r.fulfill({json:r.request().url().endsWith('/overview')?{catalog:{events:1,seats:3,bookings:1,accounts:1},services:{postgresql:true,redis:true,objectStorage:true},integrations:{authentication:true,payments:true,email:false},checkedAt:'fixture'}:{deliveryEnabled:false,counts:[{status:'QUEUED',total:2}],recent:[{kind:'BOOKING',attempts:0,status:'QUEUED'}]}}));
  await page.goto('/developer/');await page.locator('[data-section="email"]').click();await expect(page.locator('#notificationQueue')).toContainText('Messages remain queued');await expect(page.locator('#notificationQueue')).toContainText('QUEUED');expect(errors).toEqual([]);
});
