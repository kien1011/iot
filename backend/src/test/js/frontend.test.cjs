// No npm or browser runtime dependency. Run: node --test src/test/js/frontend.test.cjs
const { test } = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
const frontend = path.resolve(__dirname, '../../../../frontend/js');
function read(name) { return fs.readFileSync(path.join(frontend, name), 'utf8'); }
function stateContext() {
  const context = vm.createContext({ window: {}, console });
  vm.runInContext(read('dashboard-state.js'), context);
  vm.runInContext('const store = new window.DashboardState()', context);
  return context;
}
const flush = () => new Promise(resolve => setImmediate(resolve));

test('more than 20 points, old REST snapshots, repeated IDs and packet silence', () => {
  const c = stateContext();
  vm.runInContext(`
    for (let id=1; id<=25; id++) store.mergeReading({ temperature: id, humidity: id+1, light: id+2,
      time: '2026-10-24T10:30:00', ids: { temperature:id*3,humidity:id*3+1,light:id*3+2 } });
    store.mergeChart({ temperature:[{id:3,value:1,time:'2026-10-24T10:30:00'}], humidity:[], light:[] });
    store.mergeReading({ temperature:25,humidity:26,light:27,time:'2026-10-24T10:30:00',ids:{temperature:75,humidity:76,light:77} });
  `, c);
  assert.equal(vm.runInContext('store.series.temperature.length', c), 20);
  assert.equal(vm.runInContext('store.series.temperature[0].value', c), 6);
  assert.equal(vm.runInContext('store.series.temperature.at(-1).value', c), 25);
  const before = vm.runInContext('JSON.stringify(store.series)', c);
  assert.equal(vm.runInContext('JSON.stringify(store.series)', c), before);
});

test('per-LED locks, current state, success/timeout and stale snapshots', () => {
  const c = stateContext();
  vm.runInContext(`store.applySnapshot({deviceId:1,status:'OFF',updatedAt:'2026-10-01T10:00:00'},0);
    store.applySnapshot({deviceId:2,status:'OFF',updatedAt:'2026-10-01T10:00:00'},0);`, c);
  assert.equal(vm.runInContext("store.begin(1,'OFF')", c), null);
  assert.equal(vm.runInContext("store.begin(1,'ON')", c), 1);
  assert.equal(vm.runInContext("store.begin(1,'ON')", c), null);
  assert.equal(vm.runInContext("store.begin(2,'ON')", c), 1);
  vm.runInContext(`store.applyResult({deviceId:1,status:'ON',updatedAt:'2026-10-24T10:30:00'});
    store.applySnapshot({deviceId:1,status:'OFF',updatedAt:'2026-10-01T10:00:00'},0);`, c);
  assert.equal(vm.runInContext('store.devices[1].status', c), 'ON');
  assert.equal(vm.runInContext('store.devices[1].busy', c), false);
  assert.equal(vm.runInContext('store.devices[2].busy', c), true);
  vm.runInContext(`store.applyResult({deviceId:2,status:'OFF',updatedAt:'2026-10-01T10:00:00'});`, c);
  assert.equal(vm.runInContext('store.devices[2].busy', c), false);
  assert.equal(vm.runInContext("store.begin(2,'ON')", c), 3);
});

test('actual control function keeps SUCCESS when WebSocket beats the POST response', async () => {
  let resolvePost;
  const post = new Promise(resolve => { resolvePost = resolve; });
  const c = vm.createContext({ window:{addEventListener(){},appReady:Promise.resolve()},document:{addEventListener(){}},
    console, Api:{request:() => post} });
  vm.runInContext(read('dashboard-state.js'), c);
  c.DashboardState = c.window.DashboardState;
  vm.runInContext(read('dashboard.js'), c);
  vm.runInContext(`renderLights=()=>{}; state.applySnapshot({deviceId:1,status:'OFF',updatedAt:'2026-10-01T10:00:00'},0);`, c);
  const operation = vm.runInContext("sendLightCommand(1,'ON')", c);
  assert.equal(vm.runInContext('state.devices[1].busy', c), true);
  vm.runInContext("state.applyResult({deviceId:1,status:'ON',updatedAt:'2026-10-24T10:30:00'})", c);
  resolvePost({id:1,status:'LOADING',timeoutSeconds:5});
  await operation;
  assert.equal(vm.runInContext('state.devices[1].status', c), 'ON');
  assert.equal(vm.runInContext('state.devices[1].busy', c), false);
});

class Element {
  constructor() { this.value=''; this.textContent=''; this.children=[]; this.events={}; this.attrs={}; this.scrollTop=30; }
  classList = { values:new Set(), add(...a){a.forEach(x=>this.values.add(x));},remove(...a){a.forEach(x=>this.values.delete(x));},
    toggle(x, value){if(value)this.values.add(x);else this.values.delete(x);},contains(x){return this.values.has(x);} };
  addEventListener(name, fn) { (this.events[name] ||= []).push(fn); }
  async fire(name) { for (const fn of this.events[name] || []) await fn({preventDefault(){}}); await flush(); }
  setAttribute(name, value) { this.attrs[name]=value; }
  removeAttribute(name) { delete this.attrs[name]; }
  appendChild(child) { this.children.push(child); }
  set innerHTML(value) { this.html=value; this.children=[]; }
  get innerHTML() { return this.html; }
  focus() { this.focused=true; }
}
function historyContext(pageName) {
  const elements={};
  for (const id of ['filter-form','f-filter-by','f-query','f-query-label','query-time-icon','rows-per-page','reset-btn','table-body',
    'summary-text','pagination','filter-error','f-device','f-action','f-status','f-date','wrap']) elements[id]=new Element();
  elements['rows-per-page'].value='10';
  elements['filter-form'].reset=()=>{for (const id of ['f-filter-by','f-query','f-device','f-action','f-status','f-date']) elements[id].value='';};
  const calls=[]; let total=62;
  const context=vm.createContext({ window:{appReady:Promise.resolve()}, console,
    document:{getElementById:id=>elements[id],querySelector:()=>elements.wrap,createElement:()=>new Element(),addEventListener(name,fn){this.ready=fn;}},
    Api:{query:values=>new URLSearchParams(Object.entries(values).filter(([,v])=>v!==null&&v!==undefined&&v!=='')).toString(),
      formatDateTime:v=>v.replace('T',' ').slice(0,19),escapeHtml:v=>String(v),
      async request(url){calls.push(url);const p=new URL('http://test'+url).searchParams;const size=Number(p.get('size'));
        return {content:[],totalElements:total,totalPages:Math.ceil(total/size),page:Number(p.get('page')),size};}} });
  vm.runInContext(read('history.js'),context);
  vm.runInContext(read(pageName+'.js'),context);
  return {context,elements,calls,setTotal:n=>{total=n;},ready:async()=>{context.document.ready();await flush();}};
}

test('six time precisions, calendar validation and exact numeric grammar match demo', () => {
  const {context:c}=historyContext('sensor-history');
  for (const value of ['2026','2026-10','2024-02-29','2024-02-29 23','2024-02-29 23:59','2024-02-29 23:59:59']) {
    c.value=value;assert.doesNotThrow(()=>vm.runInContext('HistoryUtils.parseTimePrefix(value)',c));
  }
  for (const value of ['2023-02-29','1900-02-29','2026-1','2026-01-01 24','2026-01-01 10:60','2026-01-01 10:20:60']) {
    c.value=value;assert.throws(()=>vm.runInContext('HistoryUtils.parseTimePrefix(value)',c));
  }
  for (const value of ['1.5','+2','-.5','.3','-0']) { c.value=value;assert.doesNotThrow(()=>vm.runInContext('parseExactNumber(value)',c)); }
  for (const value of ['1e2','Infinity','1,2','3 lux','1.']) {c.value=value;assert.throws(()=>vm.runInContext('parseExactNumber(value)',c));}
});

test('Search/Enter applies sensor, empty value keeps sensor, draft edits do not apply', async () => {
  const t=historyContext('sensor-history');await t.ready();
  t.elements['f-filter-by'].value='Light';
  await t.elements['filter-form'].fire('submit');
  assert.match(t.calls.at(-1),/sensorId=3/);
  t.elements['f-filter-by'].value='Temperature';t.elements['f-query'].value='99';
  t.elements['rows-per-page'].value='5';await t.elements['rows-per-page'].fire('change');
  assert.match(t.calls.at(-1),/sensorId=3/);assert.doesNotMatch(t.calls.at(-1),/value=99/);
  assert.match(t.calls.at(-1),/page=0&size=5/);
});

test('invalid search keeps result and applied filter; Reset clears errors and keeps page size', async () => {
  const t=historyContext('sensor-history');await t.ready();
  t.elements['f-query'].value='28.6';await t.elements['filter-form'].fire('submit');
  const count=t.calls.length;
  t.elements['f-query'].value='1e2';await t.elements['filter-form'].fire('submit');
  assert.equal(t.calls.length,count);assert.equal(t.elements['filter-error'].classList.contains('show'),true);
  t.elements['rows-per-page'].value='15';await t.elements['rows-per-page'].fire('change');
  assert.match(t.calls.at(-1),/value=28.6/);
  await t.elements['reset-btn'].fire('click');
  assert.doesNotMatch(t.calls.at(-1),/value=/);assert.match(t.calls.at(-1),/page=0&size=15/);
  assert.equal(t.elements['filter-error'].classList.contains('show'),false);
});

test('AND device filters, maximum five numbered buttons and empty results hide pagination', async () => {
  const t=historyContext('device-history');await t.ready();
  assert.equal(t.elements.pagination.children.length,7);
  assert.equal(t.elements.pagination.children[0].disabled,true);
  t.elements['f-device'].value='Light 2';t.elements['f-action'].value='ON';t.elements['f-status'].value='FAILED';t.elements['f-date'].value='2024-02-29';
  await t.elements['filter-form'].fire('submit');
  assert.match(t.calls.at(-1),/deviceId=2&action=ON&status=FAILED&time=2024-02-29/);
  t.setTotal(0);await t.elements['reset-btn'].fire('click');
  assert.equal(t.elements['summary-text'].textContent,'Showing 0 records');
  assert.equal(t.elements.pagination.children.length,0);
  assert.equal(t.elements.wrap.scrollTop,0);
});

function apiContext(handler) {
  const requests=[],redirects=[],saved=new Map();
  const storage={getItem:key=>saved.get(key)??null,setItem:(key,value)=>saved.set(key,value),removeItem:key=>saved.delete(key)};
  const c=vm.createContext({window:{sessionStorage:storage,location:{replace:p=>redirects.push(p)}},Headers,URLSearchParams,
    fetch:async(url,options)=>{requests.push({url,options});return handler(url,options);}});
  vm.runInContext(read('api.js'),c);
  return {c,api:c.window.Api,requests,redirects,saved,storage};
}
const response=(status,body)=>({ok:status>=200&&status<300,status,text:async()=>body===undefined?'':JSON.stringify(body)});

test('shared API stores identity/token only and sends Bearer without a CSRF request or cookie', async () => {
  const t=apiContext(url=>response(url==='/api/auth/logout'?204:200));
  t.api.setAuth({id:1,username:'admin',fullName:'Administrator',token:'access-token',password:'must-not-be-stored'});
  assert.deepEqual(JSON.parse(t.saved.get('iot.auth')),{id:1,username:'admin',fullName:'Administrator',token:'access-token'});
  await t.api.request('/api/auth/me');
  await t.api.request('/api/devices/1/control',{method:'POST',body:JSON.stringify({action:'ON'})});
  assert.equal(await t.api.request('/api/auth/logout',{method:'POST'}),null);
  assert.deepEqual(t.requests.map(r=>r.url),['/api/auth/me','/api/devices/1/control','/api/auth/logout']);
  for(const r of t.requests){
    assert.equal(r.options.headers.get('Authorization'),'Bearer access-token');
    assert.equal(r.options.credentials,'omit');
    assert.equal(r.options.headers.has('X-CSRF-TOKEN'),false);
  }
  assert.equal(t.requests[1].options.headers.get('Content-Type'),'application/json');
  t.api.clearAuth();assert.equal(t.api.getToken(),null);
});

test('HTTP 401 clears expired token and redirects, while failed login stays on the form', async () => {
  const t=apiContext(()=>response(401,{message:'Unauthorized'}));
  t.api.setAuth({token:'expired-token'});
  await assert.rejects(t.api.request('/api/auth/me'),{status:401});
  assert.equal(t.api.getToken(),null);assert.deepEqual(t.redirects,['/html/login.html']);
  t.api.setAuth({token:'old-token'});
  await assert.rejects(t.api.request('/api/auth/login',{method:'POST',body:'{}',redirectOn401:false}),{status:401});
  assert.equal(t.requests.at(-1).options.headers.get('Authorization'),null);
  assert.equal(t.api.getToken(),'old-token');assert.equal(t.redirects.length,1);
});

test('a late 401 for an older token does not discard a newer login', async () => {
  let finish;const pending=new Promise(resolve=>{finish=resolve;});
  const t=apiContext(()=>pending);
  t.api.setAuth({token:'old-token'});
  const operation=t.api.request('/api/auth/me');
  t.api.setAuth({token:'new-token'});finish(response(401));
  await assert.rejects(operation,{status:401});
  assert.equal(t.api.getToken(),'new-token');assert.deepEqual(t.redirects,[]);
});

test('invalid stored auth and missing login token cannot be used as credentials', () => {
  const t=apiContext(()=>response(200));
  t.storage.setItem('iot.auth','not JSON');
  assert.equal(t.api.getToken(),null);assert.equal(t.saved.size,0);
  assert.throws(()=>t.api.setAuth({id:1,username:'admin'}),/missing a token/);
});

test('login form saves the returned token before navigating to Dashboard', async () => {
  const elements=Object.fromEntries(['login-form','login-error','login-btn','username','password','toggle-password'].map(id=>[id,new Element()]));
  const calls=[],saved=[],redirects=[];
  const auth={id:1,username:'admin',fullName:'Administrator',token:'new-access-token'};
  const c=vm.createContext({document:{getElementById:id=>elements[id],addEventListener(name,fn){this.ready=fn;}},
    window:{location:{replace:p=>{assert.equal(saved.length,1);redirects.push(p);}}},
    Api:{async request(url,options){calls.push({url,options});return auth;},setAuth:value=>saved.push(value)}});
  vm.runInContext(read('login.js'),c);c.document.ready();
  elements.username.value=' admin ';elements.password.value='123456';
  await elements['login-form'].fire('submit');
  assert.equal(calls.length,1);assert.equal(calls[0].url,'/api/auth/login');
  assert.deepEqual(JSON.parse(calls[0].options.body),{username:'admin',password:'123456'});
  assert.equal(calls[0].options.redirectOn401,false);assert.equal(saved[0],auth);
  assert.deepEqual(redirects,['dashboard.html']);
});

test('Dashboard authenticates WebSocket via subprotocol and opens only one socket', () => {
  const opened=[];
  const c=vm.createContext({window:{addEventListener(){},location:{replace(){throw new Error('Unexpected redirect');}}},
    document:{addEventListener(){}},location:{protocol:'https:',host:'iot.test'},console,
    Api:{getToken:()=> 'access-token'},WebSocket:class {constructor(url,protocols){this.readyState=0;opened.push({url,protocols});}}});
  vm.runInContext(read('dashboard-state.js'),c);c.DashboardState=c.window.DashboardState;
  vm.runInContext(read('dashboard.js'),c);vm.runInContext('connectSocket();connectSocket()',c);
  assert.equal(opened.length,1);assert.equal(opened[0].url,'wss://iot.test/ws');
  assert.deepEqual(Array.from(opened[0].protocols),['bearer','access-token']);
});
