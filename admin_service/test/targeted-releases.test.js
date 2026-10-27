import test from 'node:test';
import assert from 'node:assert/strict';
import { targetedReleaseFixture } from './targeted-release-fixture.js';
import { releaseFixture } from './publication-fixture.js';
import { AdminControl } from '../worker.js';
import { MemoryStorage, FakeLegacy } from './fixtures.js';
import { passwordHash } from '../model.js';
import { TestAuthenticator, enroll, authorize } from './authenticator.js';

test('a selected-account APK changes no public feed, stable APK, OTA content or legacy metadata', async () => {
  const f = targetedReleaseFixture(), audience = await f.publisher.audience('bob-uid');
  const p = await f.publisher.prepare(f.input, audience); await f.publisher.publish(p);
  assert.equal(f.store.files.get('version.json'), 'original-version');
  assert.equal(f.store.files.get('Bingo.apk'), 'original-apk');
  assert.equal(f.store.files.get('admin/index.html'), 'original-admin'); assert.deepEqual(f.writes, []);
  const paths = f.store.calls.filter(c => c.path === '/git/trees' && c.body).flatMap(c => c.body.tree.map(v => v.path));
  assert.equal(paths.length, 2); assert.ok(paths.includes(`app-updates/users/${audience}.json`));
  assert.ok(paths.some(p => /^app-updates\/apks\/Bingo-1.0.3-build76-[a-f0-9]{64}\.apk$/.test(p)));
  const channel = (await f.publisher.state(audience)).channel;
  assert.equal(channel.release.versionCode, 76); assert.equal(JSON.stringify(channel).includes('bob-uid'), false);
});
test('withdrawing Bob leaves Stuart and the public update unchanged', async () => {
  const f = targetedReleaseFixture(), bob = await f.publisher.audience('bob-uid'), stuart = await f.publisher.audience('stuart-uid');
  await f.publisher.publish(await f.publisher.prepare(f.input, bob));
  await f.publisher.publish(await f.publisher.prepare({ ...f.input, target: 'stuart' }, stuart));
  await f.publisher.publish(await f.publisher.prepare({ target: 'bob', enabled: false, expectedRevision: 1 }, bob));
  const b = await f.publisher.state(bob), s = await f.publisher.state(stuart);
  assert.equal(b.channel.enabled, false); assert.equal(b.release, null); assert.equal(b.revision, 2);
  assert.equal(s.channel.enabled, true); assert.equal(s.release.versionCode, 76);
  assert.equal(f.store.files.get('version.json'), 'original-version'); assert.deepEqual(f.writes, []);
});
test('a retry after a lost response reuses the same targeted commit', async () => {
  const f = targetedReleaseFixture(), audience = await f.publisher.audience('bob-uid');
  const p = await f.publisher.prepare(f.input, audience); await f.publisher.publish(p);
  const count = f.store.calls.filter(c => c.path === '/git/commits' && c.body).length;
  await f.publisher.publish(p); assert.equal(f.store.calls.filter(c => c.path === '/git/commits' && c.body).length, count);
});
test('a stale editor or queued offer cannot undo a later withdrawal', async () => {
  const f = targetedReleaseFixture(), audience = await f.publisher.audience('bob-uid');
  const p = await f.publisher.prepare(f.input, audience); await f.publisher.publish(p);
  await assert.rejects(() => f.publisher.prepare(f.input, audience), e => e.status === 409);
  await f.publisher.publish(await f.publisher.prepare({ enabled: false, expectedRevision: 1 }, audience));
  await assert.rejects(() => f.publisher.publish(p), e => e.status === 409);
  assert.equal((await f.publisher.state(audience)).channel.enabled, false);
});
test('branch conflicts leave both the account offer and public feed untouched', async () => {
  const f = targetedReleaseFixture(), audience = await f.publisher.audience('bob-uid');
  const p = await f.publisher.prepare(f.input, audience); f.store.failCommit = true;
  await assert.rejects(() => f.publisher.publish(p)); assert.equal((await f.publisher.state(audience)).channel, null);
  assert.equal(f.store.files.get('version.json'), 'original-version'); f.store.failCommit = false;
  await f.publisher.publish(p); assert.equal((await f.publisher.state(audience)).release.versionCode, 76);
});
test('bad checksum, stale target version and non-upgrade builds fail before a target is published', async () => {
  for (const patch of [{ sha256: '0'.repeat(64) }, { expectedVersionCode: 1 }, { versionCode: 73 }, { expectedRevision: 2 }]) {
    const f = targetedReleaseFixture(), audience = await f.publisher.audience('bob-uid');
    await assert.rejects(() => f.publisher.prepare({ ...f.input, ...patch }, audience));
    assert.equal(f.store.calls.some(c => c.path === '/git/refs/heads/gh-pages'), false);
  }
});
test('actual selected-account API requires a fresh passkey and audits the canonical UID hash', async () => {
  const f = targetedReleaseFixture(), legacy = new FakeLegacy(), storage = new MemoryStorage();
  const env = { TEST_LEGACY: legacy, TEST_RELEASES: releaseFixture().publisher, TEST_TARGETED_RELEASES: f.publisher,
    ALLOWED_ORIGIN: 'https://fixture.test', PUBLIC_URL: 'https://admin.fixture.test',
    ADMIN_CREDENTIALS: JSON.stringify({ operator: 'owner', salt: 'fixture', hash: await passwordHash('fixture-password-123', 'fixture') }) };
  const control = new AdminControl({ storage }, env), a = new TestAuthenticator(env.ALLOWED_ORIGIN); let token = '';
  const call = (path, method = 'GET', data, headers = {}) => control.fetch(new Request(env.PUBLIC_URL + path, { method,
    headers: { ...(token ? { Authorization: 'Bearer ' + token } : {}), 'Content-Type': 'application/json', ...headers }, body: data ? JSON.stringify(data) : undefined }));
  token = (await (await call('/api/login', 'POST', { password: 'fixture-password-123' })).json()).token;
  token = (await enroll(control, token, a)).token;
  const before = await (await call('/api/targeted-release?target=%40bob')).json(); assert.match(before.audience, /^[a-f0-9]{64}$/);
  assert.equal((await call('/api/targeted-release', 'PUT', f.input)).status, 403);
  const id = crypto.randomUUID(), proof = await authorize(control, token, a, '/api/targeted-release', 'PUT', f.input, id);
  const response = await call('/api/targeted-release', 'PUT', f.input, { 'Idempotency-Key': id, 'X-Action-Proof': proof });
  assert.equal(response.status, 200); assert.equal((await response.json()).operation.status, 'SYNCED');
  assert.equal((await f.publisher.state(before.audience)).release.versionCode, 76);
  const operations = [...(await storage.list({ prefix: 'operation:' })).values()];
  assert.equal(operations[0].type, 'APK_RELEASE'); assert.equal(operations[0].target, before.audience);
  const repeated = await call('/api/targeted-release', 'PUT', f.input); assert.equal(repeated.status, 403);
});
