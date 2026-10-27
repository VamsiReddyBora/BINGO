import test from "node:test";
import assert from "node:assert/strict";
import { validateContent, CONTENT_BASE } from "../content.js";
import { contentFixture } from "./content-fixture.js";
import { AdminControl } from "../worker.js";
import { MemoryStorage, FakeLegacy } from "./fixtures.js";
import { passwordHash } from "../model.js";
import { TestAuthenticator, enroll, authorize } from "./authenticator.js";
import fs from "node:fs/promises";

test("publisher and Android share the same supported manifest contract",async()=>{
  const p=JSON.parse(await fs.readFile(new URL('./content-contract.json',import.meta.url),'utf8'));
  assert.equal(validateContent(p),p);
});

test("content publication changes only its manifest and preserves APKs/admin files",async()=>{
  const f=contentFixture(),prepared=await f.publisher.prepare(f.input,"ALL");await f.publisher.publish(prepared);
  assert.equal(f.files.get('Bingo.apk'),'original-apk');assert.equal(f.files.get('version.json'),'original-version');assert.equal(f.files.get('admin/index.html'),'original-admin');
  assert.equal(f.manifest().revision,1);assert.equal(f.manifest().strings['home.online.title'],'Online Match');
  assert.deepEqual(f.calls.filter(c=>c.path==='/git/trees'&&c.body).flatMap(c=>c.body.tree.map(x=>x.path)),['content/index.json']);
});
test("content publishing is idempotent after a lost response",async()=>{
  const f=contentFixture(),p=await f.publisher.prepare(f.input,'ALL');await f.publisher.publish(p);const count=f.calls.filter(c=>c.path==='/git/commits'&&c.body).length;await f.publisher.publish(p);
  assert.equal(f.calls.filter(c=>c.path==='/git/commits'&&c.body).length,count);
});
test("old publications and stale editor revisions cannot overwrite newer content",async()=>{
  const f=contentFixture(),first=await f.publisher.prepare(f.input,'ALL');await f.publisher.publish(first);
  await assert.rejects(()=>f.publisher.prepare(f.input,'ALL'),/reload/);
  const second=await f.publisher.prepare({...f.input,expectedRevision:1,strings:{'home.online.title':'New copy'}},'ALL');await f.publisher.publish(second);
  await assert.rejects(()=>f.publisher.publish(first),/superseded/);assert.equal(f.manifest().revision,2);
});
test("targeted manifests use UID hashes and leave public content untouched",async()=>{
  const f=contentFixture(),audience=await f.publisher.audience('fixture_uid');assert.match(audience,/^[a-f0-9]{64}$/);
  const p=await f.publisher.prepare({...f.input,target:'bob'},audience);await f.publisher.publish(p);
  assert.ok(f.files.has(`content/users/${audience}.json`));assert.equal(f.manifest(),null);
  assert.equal(JSON.stringify(f.manifest(`content/users/${audience}.json`)).includes('fixture_uid'),false);
});
test("unknown executable settings, unsupported sizing and untrusted assets fail before blob upload",async()=>{
  const f=contentFixture();
  for(const patch of [{strings:{script:'run()'}},{script:'run()'},{homeStyle:{verticalPaddingDp:999}},
    {background:{url:'https://other.test/test.png',sha256:'a'.repeat(64),bytes:10}}, {minBuild:73}]){
    await assert.rejects(()=>f.publisher.prepare({...f.input,...patch},'ALL'));
  }
  assert.equal(f.calls.some(c=>c.path==='/git/blobs'&&c.body),false);
});
test("disabled defaults and targeted inheritance contain no leftover overrides",async()=>{
  const f=contentFixture();await assert.rejects(()=>f.publisher.prepare({...f.input,enabled:false},'ALL'));
  const p=await f.publisher.prepare({...f.input,enabled:false,strings:{}},'ALL');assert.equal(p.manifest.enabled,false);
  await assert.rejects(()=>f.publisher.prepare({...f.input,inheritGlobal:true,strings:{}},'ALL'));
  const inherited=await f.publisher.prepare({...f.input,inheritGlobal:true,strings:{}},'b'.repeat(64));assert.equal(inherited.manifest.inheritGlobal,true);
});
test("verified image bytes get an immutable checksum path and the original native update feed stays unchanged",async()=>{
  const f=contentFixture(),png='iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a6XcAAAAASUVORK5CYII=';
  const p=await f.publisher.prepare({...f.input,backgroundBase64:'data:image/png;base64,'+png},'ALL');
  assert.ok(p.manifest.background.url.startsWith(CONTENT_BASE+'assets/'));assert.match(p.assetPath,/content\/assets\/[a-f0-9]{64}\.png/);
  assert.equal(p.manifest.background.bytes,Buffer.from(png,'base64').length);await f.publisher.publish(p);
  assert.ok(f.files.has(p.assetPath));assert.equal(f.files.get('version.json'),'original-version');
});
test("failed non-fast-forward writes never report publication or overwrite the feed",async()=>{
  const f=contentFixture(),p=await f.publisher.prepare(f.input,'ALL');f.failCommit=true;
  await assert.rejects(()=>f.publisher.publish(p));assert.equal(f.manifest(),null);
  f.failCommit=false;await f.publisher.publish(p);assert.equal(f.manifest().revision,1);
});
test("public content accepts bounded supported values but rejects extra asset fields",()=>{
  const pack={schema:1,revision:1,audience:'ALL',minBuild:74,maxBuild:0,enabled:true,inheritGlobal:false,strings:{},homeStyle:{backgroundOpacity:.08,verticalPaddingDp:null},background:null};
  assert.equal(validateContent(pack),pack);
  assert.throws(()=>validateContent({...pack,background:{url:CONTENT_BASE+'assets/'+'a'.repeat(64)+'.png',sha256:'a'.repeat(64),bytes:50,code:'unsafe'}}));
});

test("content API uses actual passkey authorization and audited operations",async()=>{
  const storage=new MemoryStorage(),legacy=new FakeLegacy(),pub=contentFixture();
  const password='fixture-content-password-123';
  const env={ALLOWED_ORIGIN:'https://fixture.test',PUBLIC_URL:'https://admin.fixture.test',
    ADMIN_CREDENTIALS:JSON.stringify({operator:'owner',salt:'fixture-salt',hash:await passwordHash(password,'fixture-salt')}),
    TEST_LEGACY:legacy,TEST_CONTENT:pub.publisher};
  const control=new AdminControl({storage},env),authenticator=new TestAuthenticator(env.ALLOWED_ORIGIN);
  const request=(path,method='GET',data,extra={})=>control.fetch(new Request(env.PUBLIC_URL+path,{method,
    headers:{...(token?{Authorization:'Bearer '+token}:{}),...(data?{'Content-Type':'application/json'}:{}),...extra},body:data?JSON.stringify(data):undefined}));
  let token='';token=(await(await request('/api/login','POST',{password})).json()).token;token=(await enroll(control,token,authenticator)).token;
  assert.equal((await request('/api/content?target=bob')).status,200);
  assert.equal((await request('/api/content','PUT',pub.input)).status,403);
  const id=crypto.randomUUID(),proof=await authorize(control,token,authenticator,'/api/content','PUT',pub.input,id);
  const response=await request('/api/content','PUT',pub.input,{'Idempotency-Key':id,'X-Action-Proof':proof});
  assert.equal(response.status,200);assert.equal((await response.json()).operation.status,'SYNCED');
  assert.equal([...(await storage.list({prefix:'audit:'})).values()].filter(entry=>entry.type==='CONTENT').length,1);
  assert.equal(pub.manifest().revision,1);
});
