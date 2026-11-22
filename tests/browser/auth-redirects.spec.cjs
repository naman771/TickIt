const { test, expect } = require('@playwright/test');
const config = {authProvider:'clerk', publishableKey:'pk_test_'+Buffer.from('identity.tickit.test$').toString('base64')};
async function setup(page, path) {
  await page.route('**/api/public/config',r=>r.fulfill({json:config}));
  await page.route('https://identity.tickit.test/**',r=>r.fulfill({contentType:'text/javascript',body:''}));
  await page.addInitScript(()=>{
    window.recorded=[];
    window.Clerk={
      session:null,
      async load(options){window.loadedOptions=options},addListener(){},
      openSignIn(options){window.recorded.push({kind:'sign-in',options})},
      openSignUp(options){window.recorded.push({kind:'sign-up',options})},
      async signOut(options){window.recorded.push({kind:'sign-out',options})}
    };
  });
  await page.goto(path);
  await page.evaluate(()=>TickItAuth.init());
}
test('return destinations preserve the workspace, application query and hash without replaying Clerk handshake values',async({page})=>{
  await setup(page,'/organizer/?view=events&__clerk_handshake=temporary&__clerk_db_jwt=temporary#operations/901');
  await page.evaluate(()=>TickItAuth.open('sign-in'));
  await page.evaluate(()=>TickItAuth.open('sign-up'));
  await page.evaluate(()=>TickItAuth.signOut());
  const records=await page.evaluate(()=>window.recorded);
  const target='http://127.0.0.1:4180/organizer/?view=events#operations/901';
  for(const {kind,options} of records){
    if(kind==='sign-out')expect(options.redirectUrl).toBe(target);
    else{
      expect(options.forceRedirectUrl).toBe(target);
      expect(options.signInForceRedirectUrl).toBe(target);
      expect(options.signUpForceRedirectUrl).toBe(target);
    }
  }
  expect(await page.evaluate(()=>window.loadedOptions.allowedRedirectOrigins)).toEqual(['http://127.0.0.1:4180']);
});
test('opening auth after SPA navigation returns to the current page rather than the initial page',async({page})=>{
  await setup(page,'/developer/');
  await page.evaluate(()=>history.replaceState(null,'','/developer/?tab=payments#refunds'));
  await page.evaluate(()=>TickItAuth.open('sign-in'));
  expect(await page.evaluate(()=>window.recorded[0].options.forceRedirectUrl)).toBe('http://127.0.0.1:4180/developer/?tab=payments#refunds');
});
test('a query-supplied external destination cannot replace the current site return target',async({page})=>{
  await setup(page,'/?redirect_url=https%3A%2F%2Fexample.invalid%2F');
  await page.evaluate(()=>TickItAuth.open('sign-in'));
  expect(new URL(await page.evaluate(()=>window.recorded[0].options.forceRedirectUrl)).origin).toBe('http://127.0.0.1:4180');
});
