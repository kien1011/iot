// TEST-ONLY browser boundary doubles; never loaded by the delivered frontend.
globalThis.T = {
  authenticated: true, requests: [], commands: [], navigations: [], sockets: [], connections: 0,
  successDevices: [], postDelay: 0, snapshotDelay: 0,
  current: {temperature:null, humidity:null, light:null, time:null, ids:{}},
  chart: {temperature:[], humidity:[], light:[]},
  devices: {1:{deviceId:1,status:"OFF",updatedAt:null}, 2:{deviceId:2,status:"OFF",updatedAt:null}},
  sensorRows: [], actionRows: [],
};
for(let p=24;p>=1;p--) for(let s=3;s>=1;s--) T.sensorRows.push({id:(p-1)*3+s,sensorId:s,
  sensorName:["Temperature","Humidity","Light"][s-1],value:[28.5,70,123][s-1],createdAt:`2026-10-16T10:${String(p).padStart(2,"0")}:00`});
for(let i=40;i>=1;i--) T.actionRows.push({id:i,deviceName:i%2?"Light 1":"Light 2",action:i%3?"ON":"OFF",
  status:i%4===0?"LOADING":i%3?"ON":"OFF",userId:1,username:"admin",createdAt:`2026-10-16T11:${String(i).padStart(2,"0")}:00`});
T.clone = x => JSON.parse(JSON.stringify(x));
T.delay = ms => new Promise(resolve=>setTimeout(resolve,ms));
T.response = (body,status=200) => new Response(status===204?null:JSON.stringify(body), {status});
T.emit = (type,data) => T.sockets.forEach(ws=>ws.onmessage?.({data:JSON.stringify({type,data})}));
T.packet = (n, sameTime=false) => {
  const time=sameTime?"2026-10-16T10:00:00":`2026-10-16T10:${String(Math.floor(n/30)).padStart(2,"0")}:${String(n*2%60).padStart(2,"0")}`;
  T.current={temperature:Math.round((25+n/10)*10)/10,humidity:60,light:100+n,time,
    ids:{temperature:n*3-2,humidity:n*3-1,light:n*3}};
  for(const key of Object.keys(T.chart)) {
    T.chart[key].push({id:T.current.ids[key],time,value:T.current[key]});
    T.chart[key]=T.chart[key].slice(-20);
  }
  T.emit("sensor:update",T.current);
};
T.WebSocket = class {
  static OPEN=1;
  constructor(url) {
    T.connections++;this.url=url;this.readyState=0;T.sockets.push(this);
    setTimeout(()=>{this.readyState=1;this.onopen?.();},0);
  }
  close() {this.readyState=3;T.sockets=T.sockets.filter(ws=>ws!==this);this.onclose?.();}
};
T.location={host:"localhost:8081",protocol:"http:",replace: path=>T.navigations.push(path)};
T.window={location:T.location,addEventListener:globalThis.addEventListener.bind(globalThis)};
T.paginate=(rows,q)=>{
  const page=+(q.page||0),size=+(q.size||10);
  return {content:rows.slice(page*size,(page+1)*size),totalElements:rows.length,totalPages:Math.ceil(rows.length/size),page,size};
};
T.fetch=async (path,options={})=>{
  const url=new URL(path,"http://localhost:8081");const q=Object.fromEntries(url.searchParams);const method=options.method||"GET";
  const body=options.body?JSON.parse(options.body):null;
  T.requests.push({method,path:url.pathname,query:q,body,headers:options.headers});path=url.pathname;
  if(path==="/api/auth/csrf") return T.response({headerName:"X-CSRF-TOKEN",token:"test-token"});
  if(method==="POST"&&options.headers["X-CSRF-TOKEN"]!=="test-token") return T.response({message:"CSRF"},403);
  if(path==="/api/auth/login") {
    if(body.username!=="admin"||body.password!=="123456") return T.response({message:"Incorrect username or password."},401);
    T.authenticated=true;return T.response({id:1,username:"admin",fullName:"Administrator"});
  }
  if(!T.authenticated) return T.response({message:"Unauthorized"},401);
  if(path==="/api/auth/me")return T.response({id:1,username:"admin",fullName:"Administrator"});
  if(path==="/api/auth/logout"){T.authenticated=false;return T.response(null,204);}
  if(path==="/api/sensor-data/latest"||path==="/api/sensor-data/chart"){
    const data=T.clone(path.endsWith("latest")?T.current:T.chart);await T.delay(T.snapshotDelay);return T.response(data);
  }
  if(path.endsWith("/status")){
    const data=T.clone(T.devices[path.split("/")[3]]);await T.delay(T.snapshotDelay);return T.response(data);
  }
  if(path.endsWith("/control")){
    const id=+path.split("/")[3];T.commands.push({deviceId:id,action:body.action});
    if(T.successDevices.includes(id)){
      T.devices[id]={deviceId:id,status:body.action,updatedAt:`2026-10-16T12:00:${String(T.commands.length).padStart(2,"0")}`};
      T.emit("device:status",T.devices[id]);
    }
    await T.delay(T.postDelay);return T.response({id:T.commands.length,status:"LOADING",timeoutSeconds:5});
  }
  if(path==="/api/sensor-data/history") {
    const rows=T.sensorRows.filter(r=>(!q.sensorId||r.sensorId===+q.sensorId)&&(!q.value||r.value===+q.value)&&
      (!q.time||r.createdAt.startsWith(q.time.replace(" ","T"))));
    return T.response(T.paginate(rows,q));
  }
  if(path==="/api/devices/action-history") {
    const rows=T.actionRows.filter(r=>(!q.deviceId||r.deviceName===`Light ${q.deviceId}`)&&(!q.action||r.action===q.action)&&
      (!q.status||r.status===q.status)&&(!q.time||r.createdAt.startsWith(q.time.replace(" ","T"))));
    return T.response(T.paginate(rows,q));
  }
  return T.response({message:path},404);
};
