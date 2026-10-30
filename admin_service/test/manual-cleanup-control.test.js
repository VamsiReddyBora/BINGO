import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs/promises";
import { AdminControl } from "../worker.js";
import { passwordHash } from "../model.js";
import { MemoryStorage, FakeLegacy } from "./fixtures.js";
import { TestAuthenticator, enroll, authorize } from "./authenticator.js";
async function fixture() {
 const storage=new MemoryStorage(), legacy=new FakeLegacy(), archive=new Map(), brokerWrites=[];
 const old=Date.now()-172800000, topic='bingo/emqx7/social/invite/fixture/expired';
 const records={[topic]:JSON.stringify({eventId:'expired',recipientUsername:'fixture',timestamp:old,expiresAt:old+120000})};
 legacy.firestoreGet=async p=>archive.get(p)||null;
 legacy.firestoreSet=async(p,v)=>{assert.ok(p.startsWith('mqttMaintenanceArchives/'));archive.set(p,v);};
 const env={PUBLIC_URL:'https://admin.test',ALLOWED_ORIGIN:'https://example.test',ADMIN_CREDENTIALS:JSON.stringify({operator:'fixture',salt:'fixture',hash:await passwordHash('fixture-password','fixture')}),TEST_LEGACY:legacy,
  TEST_BROKER:async args=>{
   if(args.guardedGroups){for(const g of args.guardedGroups)for(const t of g.topics){brokerWrites.push(t);delete records[t];}return{cleanup:{acknowledged:[...brokerWrites],skipped:0}};}
   return{retainedRecords:structuredClone(records)};
  }};
 const control=new AdminControl({storage},env),authenticator=new TestAuthenticator(env.ALLOWED_ORIGIN);
 const setup=await control.fetch(new Request('https://admin.test/api/login',{method:'POST',body:JSON.stringify({password:'fixture-password'})}));
 const setupToken=(await setup.json()).token, token=(await enroll(control,setupToken,authenticator)).token;
 const request=async(path,method='GET',data,proof=true,id=crypto.randomUUID())=>{
  const headers={Authorization:'Bearer '+token,'Content-Type':'application/json','Idempotency-Key':id};
  if(method!=='GET'&&proof)headers['X-Action-Proof']=await authorize(control,token,authenticator,path,method,data||{},id);
  return control.fetch(new Request('https://admin.test'+path,{method,headers,body:data===undefined?undefined:JSON.stringify(data)}));
 };
 return{control,storage,legacy,archive,brokerWrites,request,token,records,topic};
}
test('preview does not remove records or create recovery archives',async()=>{const f=await fixture(),r=await f.request('/api/mqtt-cleanup'),p=await r.json();assert.equal(r.status,200);assert.equal(p.batchRecords,1);assert.equal(f.brokerWrites.length,0);assert.equal(f.archive.size,0);});
test('cleanup requires fresh passkey approval even for a valid preview',async()=>{const f=await fixture(),p=await(await f.request('/api/mqtt-cleanup')).json();const r=await f.request('/api/mqtt-cleanup/run','POST',{planId:p.planId},false);assert.equal(r.status,403);assert.equal(f.brokerWrites.length,0);});
test('manual cleanup runs the actual production executor and persists audit/result',async()=>{const f=await fixture(),p=await(await f.request('/api/mqtt-cleanup')).json();const r=await f.request('/api/mqtt-cleanup/run','POST',{planId:p.planId});assert.equal(r.status,200);const result=await r.json();assert.equal(result.deletedConfirmed,1);assert.equal(f.archive.size,1);assert.equal([...(await f.storage.list({prefix:'audit:'})).values()].filter(a=>a.type==='MQTT_CLEANUP').length,1);assert.equal((await f.storage.get('mqtt-cleanup:last')).manualOnly,true);});
test('constructing the console, refreshing player data, and running its alarm do not clean MQTT',async()=>{const f=await fixture();await f.control.alarm();assert.equal(f.brokerWrites.length,0);assert.equal(f.archive.size,0);assert.ok(f.records[f.topic]);});
test('an expired preview is rejected before deletion',async()=>{const f=await fixture(),p=await(await f.request('/api/mqtt-cleanup')).json();const k='mqtt-cleanup:plan:'+p.planId;await f.storage.put(k,{...await f.storage.get(k),expiresAt:1});const r=await f.request('/api/mqtt-cleanup/run','POST',{planId:p.planId});assert.equal(r.status,409);assert.equal(f.brokerWrites.length,0);});
test('cleanup archives remain inaccessible to public client endpoints',async()=>{const f=await fixture();const r=await f.control.fetch(new Request('https://admin.test/client/mqtt-cleanup'));assert.equal(r.status,404);});
test('idempotent retry returns the recorded result without deleting again',async()=>{const f=await fixture(),p=await(await f.request('/api/mqtt-cleanup')).json(),id=crypto.randomUUID(),input={planId:p.planId};assert.equal((await f.request('/api/mqtt-cleanup/run','POST',input,true,id)).status,200);assert.equal((await f.request('/api/mqtt-cleanup/run','POST',input,false,id)).status,200);assert.equal(f.brokerWrites.length,1);});
test('source has no cleanup schedule and alarms do not invoke the cleanup executor',async()=>{const config=await fs.readFile(new URL('../wrangler.toml',import.meta.url),'utf8'),source=await fs.readFile(new URL('../worker.js',import.meta.url),'utf8');assert.doesNotMatch(config,/\[triggers\]|\bcrons\s*=/);assert.doesNotMatch(source.slice(source.indexOf('async runAlarm()')),/executeRetainedCleanup|mqtt-cleanup/);});
