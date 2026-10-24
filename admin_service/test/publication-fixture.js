import { ReleasePublisher } from "../releases.js";
const json = (value) =>
  new Response(JSON.stringify(value), {
    headers: { "Content-Type": "application/json" },
  });
export function releaseFixture() {
  let feed = {
      versionName: "1.0.3",
      versionCode: 70,
      downloadUrl: "https://fixture/old.apk",
    },
    head = "head0",
    failFallback = false;
  const blobs = new Map(),
    commits = new Map(),
    trees = new Map(),
    calls = [];
  let legacyValue = JSON.stringify(feed),
    failCommit = false;
  const legacy = {
    get: async () => legacyValue,
    set: async (k, v) => {
      if (failFallback) throw new Error("fallback down");
      legacyValue = v;
    },
  };
  const transport = async (url, opts = {}) => {
    const u = new URL(url),
      path = u.pathname.split("/BINGO")[1] || "";
    const body = opts.body ? JSON.parse(opts.body) : null;
    calls.push({ path, method: opts.method || "GET", body });
    if (path === "/git/ref/heads/gh-pages")
      return json({ object: { sha: head } });
    if (path === "/contents/version.json")
      return json({
        content: Buffer.from(JSON.stringify(feed)).toString("base64"),
      });
    if (path === "/git/blobs") {
      const sha = "blob" + blobs.size;
      blobs.set(sha, body);
      return json({ sha });
    }
    if (path.startsWith("/git/commits/") && !body)
      return json({ tree: { sha: "base-tree" } });
    if (path.startsWith("/git/trees/") && !body)
      return json({
        tree: [
          { path: "admin/index.html", sha: "admin-preserved" },
          { path: "Bingo-1.0.2-build68.apk", sha: "old-preserved" },
        ],
        truncated: false,
      });
    if (path === "/git/trees") {
      const sha = "tree" + trees.size;
      trees.set(sha, body);
      return json({ sha });
    }
    if (path === "/git/commits") {
      const sha = "commit" + commits.size;
      commits.set(sha, body);
      return json({ sha });
    }
    if (path === "/git/refs/heads/gh-pages") {
      if (failCommit) return new Response("", { status: 422 });
      head = body.sha;
      const tree = trees.get(commits.get(head).tree);
      feed = JSON.parse(
        blobs.get(tree.tree.find((x) => x.path === "version.json").sha).content,
      );
      return json({ object: { sha: head } });
    }
    throw new Error("Unexpected fixture route " + path);
  };
  const publisher = new ReleasePublisher(
    { GITHUB_TOKEN: "fixture-token" },
    legacy,
    transport,
  );
  const apk = Buffer.alloc(1200);
  apk[0] = 80;
  apk[1] = 75;
  const input = {
    versionName: "1.0.4",
    versionCode: 71,
    expectedVersionCode: 70,
    apkBase64: apk.toString("base64"),
    releaseNotes: "Fixture release",
  };
  return {
    publisher,
    legacy,
    input,
    calls,
    get feed() {
      return feed;
    },
    set feed(v) {
      feed = v;
    },
    set failFallback(v) {
      failFallback = v;
    },
    set failCommit(v) {
      failCommit = v;
    },
    get legacyValue() {
      return legacyValue;
    },
  };
}
