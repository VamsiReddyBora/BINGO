import test from "node:test";
import assert from "node:assert/strict";
import { brokerExchange, mqttPublish, decodePackets } from "../mqtt.js";
import { refreshRetainedUsage } from "../retained-usage.js";
import { MemoryStorage } from "./fixtures.js";

const env={MQTT_URL:"wss://fixture.example/mqtt",MQTT_USERNAME:"fixture",MQTT_PASSWORD:"fixture"};
function inventoryFixture({reject=false,empty=false}={}) {
 const listeners=new Map(),writes=[];
 const emit=data=>queueMicrotask(()=>listeners.get("message")?.({data}));
 const socket={accept(){},close(){},addEventListener(n,f){listeners.set(n,f);},send(frame){
  const type=frame[0]>>4;
  if(type===1)emit(Uint8Array.of(32,2,0,0));
  if(type===8){
   const body=decodePackets(frame)[0].body;
   const n=(body[2]<<8)|body[3];assert.equal(new TextDecoder().decode(body.slice(4,4+n)),"bingo/#");
   assert.equal(body[4+n],0); // Avoid the broker's 1,000-entry QoS 1 subscriber queue.
   emit(Uint8Array.of(144,3,0,1,reject?128:1));
   if(!reject&&!empty){
    emit(mqttPublish("bingo/unrelated/config","{}",true,1));
    emit(mqttPublish("bingo/legacy/backup","not-json",true,2));
    emit(mqttPublish("bingo/large/profile","x".repeat(750000),true,3));
    emit(mqttPublish("bingo/unrelated/config","{}",true,4)); // Duplicate delivery is one record.
    emit(mqttPublish("bingo/live/heartbeat","{}",false,5));
   }
  }
  if(type===3){const p=decodePackets(frame)[0];writes.push(p);emit(mqttPublish(p.topic,p.payload,false,p.id));emit(Uint8Array.of(64,2,p.id>>8,p.id&255));}
 }};
 return{writes,transport:async()=>({webSocket:socket})};
}
test("MC-004 real broker inventory counts the full Bingo namespace, including large, non-JSON and non-cleanup records",async()=>{
 const f=inventoryFixture(),result=await brokerExchange(env,{snapshot:true,inventory:true},f.transport);
 assert.equal(result.retainedUsage.used,3);assert.equal(result.retainedUsage.limit,2000);
 assert.equal(result.retainedUsage.bingo,3);assert.equal(result.retainedUsage.other,0);assert.equal(result.retainedUsage.scope,"bingo-topics");
 assert.equal(result.retainedRecords,undefined);
 assert.ok(f.writes.every(p=>!p.retained&&p.topic.includes("/probe/")));
});
test("MC-004 empty broker is zero only after a successful complete read",async()=>{
 const f=inventoryFixture({empty:true}),r=await brokerExchange(env,{snapshot:true,inventory:true},f.transport);
 assert.equal(r.retainedUsage.used,0);
});
test("MC-004 subscription denial cannot be displayed as zero usage",async()=>{
 const f=inventoryFixture({reject:true});await assert.rejects(brokerExchange(env,{snapshot:true,inventory:true},f.transport));
});
test("MC-004 broker-wide inventory can never be combined with deletion or retained publishing",async()=>{
 const f=inventoryFixture();
 for(const extra of [{messages:[{topic:"bingo/x",payload:"",retain:true}]},{guardedGroups:[]},{rawFilters:["bingo/#"]}])
  await assert.rejects(brokerExchange(env,{snapshot:true,inventory:true,...extra},f.transport));
 assert.equal(f.writes.length,0);
});
test("MC-004 usage cache coalesces refreshes and preserves a marked last-known value on outage",async()=>{
 const storage=new MemoryStorage();let calls=0,fail=false;
 const c={storage,broker:async()=>{calls++;if(fail)throw Error("offline");return{retainedUsage:{used:1988,limit:2000,observedAt:Date.now(),scope:"broker-visible-topics"}};}};
 const results=await Promise.all([refreshRetainedUsage(c),refreshRetainedUsage(c)]);
 assert.equal(calls,1);assert.equal(results[0].used,1988);assert.equal(results[0].free,12);assert.equal(results[0].percent,99.4);
 await refreshRetainedUsage(c);assert.equal(calls,1);
 fail=true;const old=await refreshRetainedUsage(c,{force:true});assert.equal(old.used,1988);assert.equal(old.stale,true);assert.ok(old.error);
});
test("MC-004 first read failure shows unknown instead of a fabricated zero",async()=>{
 const result=await refreshRetainedUsage({storage:new MemoryStorage(),broker:async()=>{throw Error("down");}});
 assert.equal(result.used,null);assert.equal(result.stale,true);
});
