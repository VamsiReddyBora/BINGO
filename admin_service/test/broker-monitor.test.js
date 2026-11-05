import test from "node:test";
import assert from "node:assert/strict";
import { DatabaseSync } from "node:sqlite";
import { BrokerMonitor, measureBrokerEcho, MONITOR_RETENTION_MS } from "../broker-monitor.js";
import worker, { AdminControl } from "../worker.js";
import { decodePackets, mqttPublish } from "../mqtt.js";
import { MemoryStorage, FakeLegacy } from "./fixtures.js";
const credentials={MQTT_URL:"wss://fixture.test/mqtt",MQTT_USERNAME:"fixture",MQTT_PASSWORD:"fixture"};
function socketFixture({rejectConnect=false,rejectSubscription=false,stopAfter=3,timeout=false,noise=false}={}){
 let controller,clock=0,topic,closed=false;const packets=[],durations=[12,14,16];
 const readable=new ReadableStream({start(c){controller=c;}});
 function emit(frame){if(closed)return;const cut=Math.max(1,Math.floor(frame.length/2));controller.enqueue(frame.slice(0,cut));controller.enqueue(frame.slice(cut));}
 const writable=new WritableStream({write(frame){const p=decodePackets(frame)[0];packets.push(p);
  if(p.type===1){clock+=2000;emit(Uint8Array.of(32,2,0,rejectConnect?5:0));}
  if(p.type===8){const n=p.body[2]*256+p.body[3];topic=new TextDecoder().decode(p.body.slice(4,4+n));assert.equal(p.body[4+n],0);clock+=1000;emit(Uint8Array.of(144,3,0,1,rejectSubscription?128:0));}
  if(p.type==="publish"){const index=p.id-100;assert.equal(p.topic,topic);assert.equal(p.retained,false);
   if(index>=stopAfter){closed=true;controller.close();return;}if(timeout)return;
   if(noise){emit(mqttPublish(topic,"wrong",false,22));emit(mqttPublish(topic,p.payload,true,23));emit(mqttPublish(topic+"/other",p.payload,false,24));}
   clock+=durations[index];emit(mqttPublish(topic,p.payload,false,p.id));}
 }});
 const socket={opened:Promise.resolve(),readable,writable,close(){if(!closed){closed=true;controller.close();}}};
 return{connect:(_a,o)=>{assert.equal(o.secureTransport,"on");return socket;},clock:()=>clock,packets,closed:()=>closed,timeoutMs:40};
}
test("monitor echo excludes connect/subscribe time and uses only isolated non-retained topics",async()=>{
 const f=socketFixture({noise:true}),s=await measureBrokerEcho(credentials,f);
 assert.equal(s.status,"online");assert.equal(s.averageMs,14);assert.equal(s.minimumMs,12);assert.equal(s.peakMs,16);
 assert.equal(s.successfulProbes,3);assert.equal(s.failedProbes,0);
 const sent=f.packets.filter(p=>p.type==="publish");assert.equal(sent.length,3);
 assert.ok(sent.every(p=>/^bingo\/emqx7\/probe\/monitor_[a-f0-9]+$/.test(p.topic)&&!p.retained));assert.equal(f.closed(),true);
});
test("monitor failures remain null and timed-out sockets close",async()=>{
 for(const options of [{rejectConnect:true},{rejectSubscription:true},{timeout:true}]){
  const f=socketFixture(options),s=await measureBrokerEcho(credentials,f);
  assert.equal(s.status,"unreachable");assert.equal(s.averageMs,null);assert.equal(s.peakMs,null);assert.equal(s.failedProbes,3);assert.equal(f.closed(),true);
 }
});
test("monitor partial echoes preserve measured values without claiming success",async()=>{
 const s=await measureBrokerEcho(credentials,socketFixture({stopAfter:2}));assert.equal(s.status,"partial");assert.equal(s.averageMs,13);assert.equal(s.successfulProbes,2);assert.equal(s.failedProbes,1);
});
function sqlFixture(){const db=new DatabaseSync(":memory:");return{db,sql:{exec(q,...a){const rows=db.prepare(q).all(...a);return{toArray:()=>rows};}}};}
const online=(v=100)=>({status:"online",averageMs:v,minimumMs:v-1,peakMs:v+1,successfulProbes:3,failedProbes:0,failure:null});
const unavailable={status:"unreachable",averageMs:null,minimumMs:null,peakMs:null,successfulProbes:0,failedProbes:3,failure:"probe_timeout"};
const url=(a,b,s="")=>new URL(`https://internal/history?from=${a}&to=${b}${s}`);
test("cloud history survives object reconstruction while cleanup is OFF",async()=>{
 const f=sqlFixture(),now=Date.now()-1000,env={MQTT_AUTOCLEAN_ENABLED:"false",TEST_MONITOR_PROBE:async()=>online(98)};
 let m=new BrokerMonitor({storage:{sql:f.sql}},env);await m.tick(now);m=new BrokerMonitor({storage:{sql:f.sql}},env);
 const r=m.history(url(now-60000,now+1));assert.equal(r.source,"cloud-monitor");assert.equal(r.retentionDays,30);assert.equal(r.intervalMs,60000);
 assert.equal(r.points.length,1);assert.equal(r.points[0].averageMs,98);assert.equal(r.points[0].peakMs,99);assert.equal(r.lastRecordedAt,now);f.db.close();
});
test("concurrent duplicate ticks produce one check per minute",async()=>{
 const f=sqlFixture();let probes=0;const m=new BrokerMonitor({storage:{sql:f.sql}},{TEST_MONITOR_PROBE:async()=>{probes++;return online();}});
 const r=await Promise.all([1,2,3].map(()=>m.fetch(new Request("https://internal/tick",{method:"POST"}))));
 assert.ok(r.every(x=>x.status===200));assert.equal(probes,1);assert.equal(f.sql.exec("SELECT COUNT(*) AS n FROM monitor_samples").toArray()[0].n,1);f.db.close();
});
test("unavailable observations and missing intervals never become interpolated zeroes",async()=>{
 const f=sqlFixture(),now=Date.now(),m=new BrokerMonitor({storage:{sql:f.sql}},{TEST_MONITOR_PROBE:async()=>unavailable});
 await m.tick(now-120000);m.probe=async()=>online();await m.tick(now-1000);
 const r=m.history(url(now-180000,now),now);assert.equal(r.points.length,2);assert.equal(r.points[0].averageMs,null);assert.equal(r.points[0].failedProbes,3);assert.equal(r.points[1].averageMs,100);f.db.close();
});
test("30-day retention and aggregation bound storage and response while preserving peaks",async()=>{
 const f=sqlFixture(),now=Date.now(),m=new BrokerMonitor({storage:{sql:f.sql}},{TEST_MONITOR_PROBE:async()=>online()});
 await m.tick(now-MONITOR_RETENTION_MS-120000);await m.tick(now-1000);assert.equal(f.sql.exec("SELECT COUNT(*) AS n FROM monitor_samples").toArray()[0].n,1);
 for(let i=2;i<1440;i++){const at=now-i*60000;f.sql.exec("INSERT INTO monitor_samples VALUES(?,?,?,?,?,?,?,?,?)",Math.floor(at/60000)*60000,at,"online",i,i-1,i+1,3,0,null);}
 const r=m.history(url(now-86400000,now),now);assert.ok(r.points.length<=241);assert.equal(Math.max(...r.points.map(p=>p.peakMs)),1440);
 for(const[a,b,s]of[[now,now-1,""],[now-1000,now+120000,""],[now-31*86400000,now,""],[now-1000,now,"&points=10000"],[now+1,now+2,""]])assert.throws(()=>m.history(url(a,b,s),now));f.db.close();
});
test("probe errors store an unavailable observation instead of success",async()=>{
 const f=sqlFixture(),now=Date.now()-1000,m=new BrokerMonitor({storage:{sql:f.sql}},{TEST_MONITOR_PROBE:async()=>{throw Error("network");}});
 await m.tick(now);const p=m.history(url(now-60000,now+1)).points[0];assert.equal(p.averageMs,null);assert.equal(p.successfulProbes,0);assert.equal(p.failedProbes,3);f.db.close();
});
test("scheduled recorder has its own object and needs no phone or admin session",async()=>{
 let target,operation;const env={MQTT_AUTOCLEAN_ENABLED:"false",MONITOR:{idFromName:x=>x,get:(id,options)=>{target={id,options};return{fetch:async r=>{assert.equal(new URL(r.url).pathname,"/tick");assert.equal(r.method,"POST");return new Response("{}");}};}}};
 await worker.scheduled({cron:"* * * * *"},env,{waitUntil:t=>{operation=t;}});await operation;assert.equal(target.id,"broker-monitor-v1");assert.equal(target.options.locationHint,"apac");
});
test("native history requires a signed admin and forwards only a read route",async()=>{
 const storage=new MemoryStorage();let forwarded;
 const c=new AdminControl({storage,blockConcurrencyWhile:f=>f()},{PUBLIC_URL:"https://admin.test",ALLOWED_ORIGIN:"https://example.test",TEST_LEGACY:new FakeLegacy(),MONITOR:{idFromName:x=>x,get:()=>({fetch:async u=>{forwarded=new URL(u);return new Response('{"source":"cloud-monitor"}');}})}});
 const request=()=>new Request("https://admin.test/client/admin/latency-history?from=123&to=456&ignored=evil",{headers:{"X-Admin-User":"bob"}});
 assert.equal((await c.fetch(request())).status,401);assert.equal(forwarded,undefined);c.nativeAdminIdentity=async()=>"fixture-admin";
 assert.equal((await c.fetch(request())).status,200);assert.equal(forwarded.pathname,"/history");assert.equal(forwarded.search,"?from=123&to=456");
 assert.equal((await c.fetch(new Request("https://admin.test/client/admin/latency-history",{method:"POST"}))).status,405);
});

test("a non-advancing timer never invents sub-millisecond latency",async()=>{
 const fixture=socketFixture();fixture.clock=()=>0;
 const sample=await measureBrokerEcho(credentials,fixture);
 assert.equal(sample.status,"unreachable");assert.equal(sample.averageMs,null);
 assert.equal(sample.successfulProbes,0);assert.equal(sample.failure,"invalid_clock");
});
