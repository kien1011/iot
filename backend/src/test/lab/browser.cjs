// TEST ONLY. Run against a fresh local test deployment and a separate test database.
// Requires Playwright outside the project. No npm/package.json is used by the application.
const assert = require('node:assert/strict');
const path = require('node:path');
const {spawn} = require('node:child_process');
const readline = require('node:readline');
const {chromium} = require(process.env.IOT_PLAYWRIGHT_MODULE || 'playwright');
const origin = process.env.IOT_TEST_ORIGIN || 'http://127.0.0.1:8081';
const sleep = ms => new Promise(resolve => setTimeout(resolve,ms));
async function until(check, timeout=8000) {
  const deadline=Date.now()+timeout;
  while(Date.now()<deadline) { if(await check())return; await sleep(70); }
  throw new Error('Condition did not become true');
}
(async()=>{
  const peer=spawn(process.env.IOT_TEST_PYTHON || 'python', [path.join(__dirname,'hardware.py')],{stdio:['pipe','pipe','inherit']});
  const waiting=[];
  let ready;
  const readyPromise=new Promise(resolve=>{ready=resolve;});
  readline.createInterface({input:peer.stdout}).on('line',line=>{
    const value=JSON.parse(line);
    if(value.ready)ready();
    else {const task=waiting.shift();if(value.error)task.reject(new Error(value.error));else task.resolve(value.result);}
  });
  const command = request=>new Promise((resolve,reject)=>{waiting.push({resolve,reject});peer.stdin.write(JSON.stringify(request)+'\n');});
  let browser;
  try {
    await readyPromise;
    browser=await chromium.launch({headless:true,executablePath:process.env.IOT_TEST_CHROMIUM || undefined,
      args:['--no-sandbox','--disable-dev-shm-usage','--disable-gpu']});
    const context=await browser.newContext({viewport:{width:1440,height:1000},timezoneId:'America/Los_Angeles'});
    const page=await context.newPage();
    const errors=[];page.on('pageerror',error=>errors.push(error.message));
    await page.addInitScript(()=>{
      window.__activeSockets=0;
      const Base=window.WebSocket;
      window.WebSocket=class extends Base {constructor(...args){super(...args);window.__activeSockets++;this.addEventListener('close',()=>window.__activeSockets--);}};
    });
    await page.goto(origin+'/html/dashboard.html');
    await page.waitForURL('**/html/login.html');
    await page.click('#login-btn');
    assert.match(await page.locator('#login-error').textContent(),/both username/);
    await page.fill('#username','admin');await page.fill('#password','wrong');await page.click('#login-btn');
    await until(async()=>(await page.locator('#login-error').textContent()).includes('Incorrect'));
    await page.fill('#password','123456');await page.click('#toggle-password');
    assert.equal(await page.locator('#password').getAttribute('type'),'text');
    await page.click('#login-btn');await page.waitForURL('**/html/dashboard.html');
    await page.waitForFunction(()=>state.devices[1].ready && state.devices[2].ready && socket?.readyState===1);
    const cookies=await context.cookies();assert(!cookies.some(cookie=>cookie.name==='JSESSIONID'));
    assert(await page.evaluate(()=>Boolean(JSON.parse(sessionStorage.getItem('iot.auth'))?.token)));
    assert.equal(await page.evaluate(()=>socket.protocol),'bearer');
    assert.equal(await page.locator('#temp-value').textContent(),'--');
    assert.equal(await page.evaluate(()=>chart.data.labels.length),0);
    assert.equal(await page.locator('#light-1-card .status-text').textContent(),'OFF');
    const api = path=>page.evaluate(path=>Api.request(path),path);
    const original=await api('/api/devices/1/status');assert(original.updatedAt);
    for(const time of ['0000','9999']) assert.equal((await api('/api/sensor-data/history?time='+time)).totalElements,0);
    console.log('PASS real login/Bearer token, static paths, empty dashboard and DB initial time');

    await command({operation:'publish',packet:{temperature:28.56,humidity:70.14,light:68}});
    await until(async()=>(await page.locator('#temp-value').textContent())==='28.6');
    const latest=await api('/api/sensor-data/latest');
    assert.equal(latest.humidity,70.1);
    assert.equal(await page.locator('#light-value').textContent(),String(latest.light));
    const database=await command({operation:'query',sql:'SELECT COUNT(*) AS n, COUNT(DISTINCT created_at) AS times FROM sensor_data'});
    assert.equal(database[0].n,3);assert.equal(database[0].times,1);
    assert.equal((await api('/api/sensor-data/history?sensorId=3&value='+latest.light)).totalElements,1);
    assert.equal((await api('/api/sensor-data/chart')).light[0].value,latest.light);
    assert.equal(await page.locator('#temp-updated').textContent(),'Updated '+latest.time.slice(11,19));
    await command({operation:'burst',count:25});
    await until(async()=>(await api('/api/sensor-data/history')).totalElements===78);
    await until(async()=>(await page.evaluate(()=>chart.data.labels.length))===20);
    const frozen=await api('/api/sensor-data/latest');
    await sleep(2200);assert.deepEqual(await api('/api/sensor-data/latest'),frozen);
    assert.equal(await page.evaluate(()=>window.__activeSockets),1);
    console.log('PASS real MQTT -> MySQL -> REST/WebSocket/Chart, exact lux, Vietnam display, latest 20 chart points and silence');

    // Delay only delivery of the actual HTTP response; the backend and MQTT peer run normally.
    await page.route('**/api/devices/*/control', async route=>{
      const response=await route.fetch();await sleep(350);await route.fulfill({response});
    });
    await page.click('#light-1-card .on-btn');
    await until(async()=>(await page.locator('#light-1-card .status-text').textContent())==='ON');
    await sleep(500);assert.equal(await page.locator('#light-1-card .status-text').textContent(),'ON');
    assert.equal(await page.locator('#light-1-card .off-btn').isEnabled(),true);
    await page.click('#light-1-card .off-btn');
    await until(async()=>(await page.locator('#light-1-card .status-text').textContent())==='OFF');
    await page.unroute('**/api/devices/*/control');
    console.log('PASS fast ACK/event before POST response, committed success, reverse command');

    const beforeTimeout=await api('/api/devices/1/status');
    await command({operation:'ack',devices:[2]});
    await page.click('#light-1-card .on-btn');
    assert.equal(await page.locator('#light-1-card .off-btn').isDisabled(),true);
    assert.equal(await page.locator('#light-2-card .on-btn').isEnabled(),true);
    await page.click('#light-2-card .on-btn');
    await until(async()=>(await page.locator('#light-2-card .status-text').textContent())==='ON');
    assert.equal(await page.locator('#light-1-card .status-text').textContent(),'LOADING');
    await until(async()=>(await page.locator('#light-1-card .status-text').textContent())==='OFF');
    assert.deepEqual(await api('/api/devices/1/status'),beforeTimeout);
    assert.equal((await api('/api/devices/action-history?deviceId=1&status=FAILED')).totalElements,1);
    await command({operation:'ack',devices:[1,2]});await page.click('#light-1-card .on-btn');
    await until(async()=>(await page.locator('#light-1-card .status-text').textContent())==='ON');
    console.log('PASS one LED timeout while other succeeds; failed history, unchanged updated_at, retry');

    for(const id of [1,2]) {await page.click(`#light-${id}-card .off-btn`);await until(async()=>(await page.locator(`#light-${id}-card .status-text`).textContent())==='OFF');}
    await command({operation:'ack',devices:[]});
    await page.click('#light-1-card .on-btn');await page.click('#light-2-card .on-btn');
    await until(async()=>(await page.locator('#light-1-card .status-text').textContent())==='OFF'&&(await page.locator('#light-2-card .status-text').textContent())==='OFF');
    await command({operation:'ack',devices:[1,2]});
    await page.click('#light-1-card .on-btn');await page.click('#light-2-card .on-btn');
    await until(async()=>(await page.locator('#light-1-card .status-text').textContent())==='ON'&&(await page.locator('#light-2-card .status-text').textContent())==='ON');
    console.log('PASS both LED timeouts, then both successful retries');

    await page.click('a[href="sensor-history.html"]');await page.waitForFunction(()=>document.getElementById('summary-text').textContent.includes('78 records'));
    assert.equal(await page.locator('#table-body tr').count(),10);
    assert((await page.locator('#pagination button').count())<=7);
    await page.selectOption('#f-filter-by','Light');await page.fill('#f-query',String(latest.light));await page.press('#f-query','Enter');
    await until(async()=>(await page.locator('#table-body tr').count())===1 && (await page.locator('#table-body').textContent()).includes('lux'));
    const previous=await page.locator('#table-body').innerHTML();await page.fill('#f-query','1e2');await page.press('#f-query','Enter');
    assert.equal(await page.locator('#table-body').innerHTML(),previous);
    assert.equal(await page.locator('#filter-error').isVisible(),true);
    await page.click('#reset-btn');await page.selectOption('#rows-per-page','15');
    await until(async()=>(await page.locator('#table-body tr').count())===15);
    await page.selectOption('#f-filter-by','Temperature');await page.fill('#f-query','20');
    await page.selectOption('#rows-per-page','5');
    await until(async()=>(await page.locator('#summary-text').textContent()).includes('78 records'));
    await page.selectOption('#f-filter-by','time');
    await page.fill('#f-query','2023-02-29');await page.press('#f-query','Enter');
    assert.equal(await page.locator('#filter-error').isVisible(),true);
    await page.fill('#f-query','2024-02-29');await page.press('#f-query','Enter');
    await until(async()=>(await page.locator('#summary-text').textContent())==='Showing 0 records');
    assert.equal(await page.locator('#pagination button').count(),0);
    await page.click('#reset-btn');assert.equal(await page.locator('#rows-per-page').inputValue(),'5');
    await page.click('a[href="device-history.html"]');await page.waitForFunction(()=>document.getElementById('table-body').textContent.includes('admin'));
    await page.selectOption('#f-device','Light 1');await page.selectOption('#f-action','ON');await page.selectOption('#f-status','FAILED');
    await page.press('#f-date','Enter');
    await until(async()=>(await page.locator('#table-body').textContent()).includes('FAILED')&&!(await page.locator('#table-body').textContent()).includes('SUCCESS'));
    console.log('PASS real SQL filters, Enter, invalid input keeps results, Reset, page sizes, pagination and leap dates');

    await page.click('a[href="profile.html"]');assert.equal(await page.locator('#profile-name').textContent(),'Administrator');
    await page.click('a[href="dashboard.html"]');
    await page.waitForFunction(()=>state.devices[1].ready&&socket?.readyState===1);
    assert.equal(await page.locator('#light-1-card .status-text').textContent(),'ON');
    assert.equal(await page.evaluate(()=>chart.data.labels.length),20);
    assert.equal(await page.evaluate(()=>window.__activeSockets),1);
    for(const id of [1,2]) {await page.click(`#light-${id}-card .off-btn`);await until(async()=>(await page.locator(`#light-${id}-card .status-text`).textContent())==='OFF');}
    const loading=await api('/api/devices/action-history?status=LOADING');assert.equal(loading.totalElements,0);
    if(process.env.IOT_TEST_SCREENSHOT)await page.screenshot({path:process.env.IOT_TEST_SCREENSHOT,fullPage:true});
    const oldToken=await page.evaluate(()=>Api.getToken());
    await page.click('#user-chip');await page.click('#logout-btn');await page.waitForURL('**/html/login.html');
    assert.equal(await page.evaluate(()=>sessionStorage.getItem('iot.auth')),null);
    assert.equal((await context.request.get(origin+'/api/auth/me',{headers:{Authorization:'Bearer '+oldToken}})).status(),401);
    await page.fill('#username','admin');await page.fill('#password','123456');await page.click('#login-btn');await page.waitForURL('**/html/dashboard.html');
    await page.waitForFunction(()=>state.devices[1].ready);
    await context.close();
    const countBefore=(await command({operation:'query',sql:'SELECT COUNT(*) AS n FROM sensor_data'}))[0].n;
    await command({operation:'publish',packet:{temperature:31.2,humidity:58.9,light:220}});
    await until(async()=>(await command({operation:'query',sql:'SELECT COUNT(*) AS n FROM sensor_data'}))[0].n===countBefore+3);
    assert.deepEqual(errors,[]);
    console.log('PASS unchanged Profile, reopen from DB, no duplicate sockets, logout/relogin and MQTT with no FE open');
    console.log('BROWSER ACCEPTANCE SUCCESS');
  } finally {
    if(browser) await browser.close();
    peer.stdin.end(JSON.stringify({operation:'quit'})+'\n');
    peer.kill();
  }
})().catch(error=>{console.error(error);process.exitCode=1;});
