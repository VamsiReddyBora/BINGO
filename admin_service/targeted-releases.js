import { ReleasePublisher } from './releases.js';
import { digest, requireThat } from './model.js';

const SITE = 'https://vamsireddybora.github.io/BINGO/';
const pathFor = audience => `app-updates/users/${audience}.json`;

/** Account-specific APK offers. Global feeds, legacy metadata and Bingo.apk are never written. */
export class TargetedReleasePublisher extends ReleasePublisher {
  async audience(uid) { return digest(uid); }
  async state(audience) {
    requireThat(/^[a-f0-9]{64}$/.test(audience), 400, 'Invalid APK audience');
    const head = await this.head(), parent = await this.api('/git/commits/' + head);
    const inventory = await this.api('/git/trees/' + parent.tree.sha + '?recursive=1');
    requireThat(!inventory.truncated, 503, 'APK inventory could not be verified');
    const entry = inventory.tree.find(row => row.path === pathFor(audience));
    let channel = null;
    if (entry) {
      const blob = await this.api('/git/blobs/' + entry.sha);
      requireThat(blob.size == null || blob.size <= 32768, 503, 'APK offer is too large');
      channel = JSON.parse(new TextDecoder().decode(Uint8Array.from(atob(blob.content.replace(/\s/g, '')), c => c.charCodeAt(0))));
      requireThat(channel.schema === 1 && channel.audience === audience && Number.isSafeInteger(channel.revision) && channel.revision > 0 &&
        typeof channel.enabled === 'boolean' && (!channel.enabled || Number.isSafeInteger(channel.release?.versionCode)), 503, 'Stored APK offer is invalid');
    }
    return { channel, revision: channel?.revision || 0, release: channel?.enabled ? channel.release : null,
      head, baseTree: parent.tree.sha, inventory: inventory.tree, audience, publishConfigured: true,
      source: 'Account-specific GitHub Pages source' };
  }
  async prepare(input, audience) {
    const current = await this.state(audience);
    requireThat(input.expectedRevision === current.revision, 409, 'APK offer changed; reload before publishing');
    requireThat(current.revision < Number.MAX_SAFE_INTEGER, 409, 'APK revision limit reached');
    let prepared = null;
    if (input.enabled !== false) {
      const global = await super.feed();
      requireThat(Number.isSafeInteger(input.versionCode) && input.versionCode > global.release.versionCode,
        409, 'A selected-user APK must have a higher build than the public update');
      const delegate = new ReleasePublisher(this.env, this.legacy, this.transport);
      delegate.feed = async () => ({ publishConfigured: true, release: current.release || { versionCode: 0 } });
      prepared = await delegate.prepare({ ...input, allowRollback: false });
      prepared.filename = `app-updates/apks/Bingo-${prepared.manifest.versionName}-build${prepared.manifest.versionCode}-${prepared.manifest.sha256}.apk`;
      prepared.manifest.downloadUrl = SITE + prepared.filename;
    }
    return { audience, expectedRevision: current.revision, filename: prepared?.filename || null,
      apkBlob: prepared?.apkBlob || null, channel: { schema: 1, audience, revision: current.revision + 1,
        enabled: input.enabled !== false, release: prepared?.manifest || null } };
  }
  async publish(data) {
    const current = await this.state(data.audience);
    if (JSON.stringify(current.channel) === JSON.stringify(data.channel))
      return { pagesCommit: current.head, deploymentPending: true };
    requireThat(current.revision === data.expectedRevision, 409, 'Newer APK offer superseded this publication');
    if (data.filename) {
      const existing = current.inventory.find(row => row.path === data.filename);
      requireThat(!existing || existing.sha === data.apkBlob, 409, 'Immutable APK path already has different bytes');
    }
    const blob = await this.api('/git/blobs', 'POST', { content: JSON.stringify(data.channel, null, 2) + '\n', encoding: 'utf-8' });
    const tree = await this.api('/git/trees', 'POST', { base_tree: current.baseTree, tree: [
      { path: pathFor(data.audience), mode: '100644', type: 'blob', sha: blob.sha },
      ...(data.apkBlob ? [{ path: data.filename, mode: '100644', type: 'blob', sha: data.apkBlob }] : [])
    ] });
    const identity = { name: 'VamsiReddyBora', email: '203189347+VamsiReddyBora@users.noreply.github.com' };
    const commit = await this.api('/git/commits', 'POST', { message: 'Admin: selected-account APK offer revision ' + data.channel.revision,
      tree: tree.sha, parents: [current.head], author: identity, committer: identity });
    await this.api('/git/refs/heads/gh-pages', 'PATCH', { sha: commit.sha, force: false });
    return { pagesCommit: commit.sha, deploymentPending: true };
  }
}
