import { ReleasePublisher } from "./releases.js";
import { requireThat, digest } from "./model.js";

export const CONTENT_BASE = "https://vamsireddybora.github.io/BINGO/content/";
export const CONTENT_TEXT = Object.freeze({
  "home.ai.title": 48, "home.ai.description": 140,
  "home.online.title": 48, "home.online.description": 140,
  "home.nearby.title": 48, "home.nearby.description": 140,
  "home.developerNote": 60, "home.serverNote": 400,
});
const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);
const pathFor = (audience) => audience === "ALL" ? "content/index.json" : `content/users/${audience}.json`;
const defaultStyle = () => ({ backgroundOpacity: .08 });
export function validateContent(pack) {
  requireThat(pack.schema === 1 && Number.isSafeInteger(pack.revision) && pack.revision > 0, 400, "Invalid content revision");
  requireThat(pack.audience === "ALL" || /^[a-f0-9]{64}$/.test(pack.audience), 400, "Invalid content audience");
  requireThat(Number.isSafeInteger(pack.minBuild) && pack.minBuild >= 74 && Number.isSafeInteger(pack.maxBuild) &&
    (pack.maxBuild === 0 || pack.maxBuild >= pack.minBuild), 400, "Invalid app-build range");
  requireThat(typeof pack.enabled === "boolean" && typeof pack.inheritGlobal === "boolean", 400, "Invalid content switches");
  requireThat(pack.strings && !Array.isArray(pack.strings) && typeof pack.strings === "object", 400, "Invalid text content");
  for (const [key, value] of Object.entries(pack.strings)) requireThat(Object.hasOwn(CONTENT_TEXT, key) && typeof value === "string" &&
    value.trim() && value.length <= CONTENT_TEXT[key] && !/[\x00-\x1f\x7f-\x9f]/.test(value), 400, "Unsupported text field or value");
  const style = pack.homeStyle;
  const bounds = { verticalPaddingDp: [8,20], titleSp: [13,18], descriptionSp: [10,14], descriptionMaxLines: [1,3] };
  requireThat(style && typeof style === "object" && !Array.isArray(style), 400, "Invalid Home style");
  for (const [key,value] of Object.entries(style)) {
    if (key === "backgroundOpacity") requireThat(Number.isFinite(value) && value >= 0 && value <= .15, 400, "Background opacity must be 0–0.15");
    else requireThat(Object.hasOwn(bounds,key) && (value === null || Number.isSafeInteger(value) && value >= bounds[key][0] && value <= bounds[key][1]), 400, "Unsupported Home styling");
  }
  if (pack.background) requireThat(Object.keys(pack.background).length === 3 && pack.background.bytes > 0 && Number.isSafeInteger(pack.background.bytes) && pack.background.bytes <= 2*1024*1024 &&
    /^[a-f0-9]{64}$/.test(pack.background.sha256) && new RegExp("^" + CONTENT_BASE.replace(/[.*+?^${}()|[\]\\]/g,"\\$&") + "assets/" + pack.background.sha256 + "\\.(png|jpg|webp)$").test(pack.background.url), 400, "Unsupported background asset");
  const empty = !Object.keys(pack.strings).length && !pack.background && Object.entries(style).every(([key,value]) => key === "backgroundOpacity" ? value === .08 : value === null);
  requireThat(pack.enabled || empty, 400, "Disabled content must restore built-in defaults");
  requireThat(!pack.inheritGlobal || pack.audience !== "ALL" && empty, 400, "Public inheritance cannot include overrides");
  requireThat(new TextEncoder().encode(JSON.stringify(pack)).length <= 32768, 400, "Content manifest exceeds 32 KiB");
  return pack;
}

/** Uses the existing admin GitHub credential, audit and passkey guard; never changes APK feeds. */
export class ContentPublisher extends ReleasePublisher {
  constructor(env, transport = fetch) { super(env, null, transport); }
  async audience(uid) { return uid === "ALL" ? "ALL" : digest(uid); }
  async state(audience) {
    const head = await this.head(), parent = await this.api("/git/commits/" + head);
    const inventory = await this.api("/git/trees/" + parent.tree.sha + "?recursive=1");
    requireThat(!inventory.truncated, 503, "Content inventory could not be verified");
    const item = inventory.tree.find(item => item.path === pathFor(audience));
    let manifest = null;
    if (item) {
      const blob = await this.api("/git/blobs/" + item.sha);
      requireThat(blob.size == null || blob.size <= 32768, 503, "Stored content manifest is too large");
      manifest = JSON.parse(new TextDecoder().decode(Uint8Array.from(atob(blob.content.replace(/\s/g,"")), c=>c.charCodeAt(0))));
      validateContent(manifest);
    }
    return { manifest, revision: manifest?.revision || 0, head, baseTree: parent.tree.sha, inventory: inventory.tree,
      audience, path: pathFor(audience), publishConfigured: true };
  }
  async prepare(input, audience) {
    const current = await this.state(audience);
    requireThat(input.expectedRevision === current.revision, 409, "Content changed; reload before publishing");
    const allowed = new Set(["target","expectedRevision","minBuild","maxBuild","enabled","inheritGlobal","strings","homeStyle","background","backgroundBase64"]);
    requireThat(Object.keys(input).every(key=>allowed.has(key)),400,"Unsupported content publishing field");
    const manifest = { schema:1, revision: current.revision+1, audience,
      minBuild:input.minBuild ?? 74, maxBuild:input.maxBuild ?? 0, enabled:input.enabled ?? true,
      inheritGlobal:input.inheritGlobal ?? false, strings:input.strings ?? {},
      homeStyle:input.homeStyle ?? defaultStyle(), background:input.background ?? null };
    let image = null, assetPath = null;
    if (input.backgroundBase64) {
      requireThat(typeof input.backgroundBase64 === "string",400,"Background must be an encoded image");
      const match = /^data:image\/(png|jpeg);base64,([A-Za-z0-9+/]+=*)$/.exec(input.backgroundBase64);
      requireThat(match && input.backgroundBase64.length <= 2_800_000, 400, "Use a PNG/JPEG background of at most 2 MiB");
      const bytes = Uint8Array.from(atob(match[2]), c=>c.charCodeAt(0));
      requireThat(bytes.length > 24 && bytes.length <= 2*1024*1024,400,"Invalid background size");
      requireThat(match[1] === "png" ? [...bytes.slice(0,8)].join(',') === '137,80,78,71,13,10,26,10' :
        bytes[0] === 255 && bytes[1] === 216 && bytes[2] === 255,400,"Background does not match its image type");
      if (match[1] === "png") {
        const view = new DataView(bytes.buffer);
        requireThat(view.getUint32(16)>0 && view.getUint32(16)<=2048 && view.getUint32(20)>0 && view.getUint32(20)<=2048,400,"Background dimensions exceed 2048 pixels");
      }
      const sha256 = [...new Uint8Array(await crypto.subtle.digest("SHA-256",bytes))].map(v=>v.toString(16).padStart(2,'0')).join('');
      const filename = `${sha256}.${match[1]==='png'?'png':'jpg'}`;
      manifest.background = { url: CONTENT_BASE+"assets/"+filename, sha256, bytes:bytes.length };
      image = match[2]; assetPath = "content/assets/"+filename;
    }
    validateContent(manifest);
    if (manifest.background && !image) requireThat(current.inventory.some(item=>"https://vamsireddybora.github.io/BINGO/"+item.path === manifest.background.url),400,"Existing background asset is missing");
    // Validation finishes before uploading/staging any blob.
    const assetBlob = image ? (await this.api("/git/blobs","POST",{content:image,encoding:"base64"})).sha : null;
    return { manifest, expectedRevision:current.revision, assetPath, assetBlob };
  }
  async publish(data) {
    const current = await this.state(data.manifest.audience);
    if (same(current.manifest,data.manifest)) return { pagesCommit:current.head, deploymentPending:true };
    requireThat(current.revision === data.expectedRevision,409,"Newer content superseded this pending publication");
    if (data.assetPath) {
      const old = current.inventory.find(item=>item.path===data.assetPath);
      requireThat(!old || old.sha===data.assetBlob,409,"An immutable content asset already exists with different bytes");
    }
    const manifest = await this.api("/git/blobs","POST",{content:JSON.stringify(data.manifest,null,2)+"\n",encoding:"utf-8"});
    const tree = await this.api("/git/trees","POST",{base_tree:current.baseTree,tree:[
      {path:pathFor(data.manifest.audience),mode:"100644",type:"blob",sha:manifest.sha},
      ...(data.assetBlob?[{path:data.assetPath,mode:"100644",type:"blob",sha:data.assetBlob}]:[]) ]});
    const identity = {name:"VamsiReddyBora",email:"203189347+VamsiReddyBora@users.noreply.github.com"};
    const commit = await this.api("/git/commits","POST",{message:`Admin: content revision ${data.manifest.revision}`,
      tree:tree.sha,parents:[current.head],author:identity,committer:identity});
    await this.api("/git/refs/heads/gh-pages","PATCH",{sha:commit.sha,force:false});
    return {pagesCommit:commit.sha,deploymentPending:true};
  }
}
