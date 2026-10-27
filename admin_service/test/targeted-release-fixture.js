import { contentFixture } from './content-fixture.js';
import { TargetedReleasePublisher } from '../targeted-releases.js';

export function targetedReleaseFixture() {
  const store = contentFixture(), writes = [];
  const legacy = { get: async () => null, set: async (...args) => writes.push(args) };
  const publisher = new TargetedReleasePublisher({ GITHUB_TOKEN: 'fixture-token' }, legacy, store.transport);
  const apk = Buffer.alloc(1200); apk[0] = 80; apk[1] = 75;
  return { store, publisher, writes, input: { target: 'bob', versionName: '1.0.3', versionCode: 76,
    expectedVersionCode: 0, expectedRevision: 0, apkBase64: apk.toString('base64'), releaseNotes: 'Selected-account fixture' } };
}
