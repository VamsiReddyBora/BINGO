import { requireThat, Problem } from "./model.js";
import { decodeLegacy } from "./model.js";
const API = "https://api.github.com/repos/VamsiReddyBora/BINGO";
const SITE = "https://vamsireddybora.github.io/BINGO/";
const MAX_APK = 12 * 1024 * 1024;
const bytes64 = (value) => Uint8Array.from(atob(value), (c) => c.charCodeAt(0));
const hex = (b) =>
  [...new Uint8Array(b)].map((v) => v.toString(16).padStart(2, "0")).join("");
const equal = (a, b) => JSON.stringify(a) === JSON.stringify(b);
export class ReleasePublisher {
  constructor(env, legacy, transport = fetch) {
    this.env = env;
    this.legacy = legacy;
    this.transport = transport;
  }
  async api(path, method = "GET", body) {
    requireThat(
      this.env.GITHUB_TOKEN,
      503,
      "Update publishing is not configured",
    );
    let res;
    try {
      res = await this.transport(API + path, {
        method,
        headers: {
          Authorization: "Bearer " + this.env.GITHUB_TOKEN,
          Accept: "application/vnd.github+json",
          "X-GitHub-Api-Version": "2022-11-28",
          "User-Agent": "Bingo-Admin-Control",
          ...(body ? { "Content-Type": "application/json" } : {}),
        },
        body: body ? JSON.stringify(body) : undefined,
        signal: AbortSignal.timeout(20000),
      });
    } catch {
      throw new Problem(
        503,
        "GitHub is unavailable; publication has not been confirmed",
      );
    }
    requireThat(
      res.ok,
      res.status === 409 || res.status === 422 ? 409 : 503,
      "GitHub update was not confirmed; refresh before retrying",
    );
    return res.json();
  }
  async head() {
    return (await this.api("/git/ref/heads/gh-pages")).object.sha;
  }
  async deployment(head) {
    try {
      const build = await this.api("/pages/builds/latest");
      return {
        status: build.commit === head ? build.status : "queued",
        commit: build.commit,
        expectedCommit: head,
      };
    } catch {
      return { status: "unknown" };
    }
  }
  async feed() {
    if (this.env.GITHUB_TOKEN) {
      const head = await this.head();
      const file = await this.api("/contents/version.json?ref=" + head);
      const release = JSON.parse(
        new TextDecoder().decode(bytes64(file.content.replace(/\s/g, ""))),
      );
      return {
        release,
        head,
        source: "GitHub Pages source",
        publishConfigured: true,
      };
    }
    const res = await this.transport(
      SITE + "version.json?_admin=" + Date.now(),
      { cache: "no-store", signal: AbortSignal.timeout(8000) },
    );
    requireThat(res.ok, 503, "Current update feed is unavailable");
    return {
      release: await res.json(),
      source: "GitHub Pages",
      publishConfigured: false,
    };
  }
  async prepare(input) {
    const current = await this.feed();
    requireThat(
      current.publishConfigured,
      503,
      "GitHub publishing credential is not configured",
    );
    requireThat(
      typeof input.versionName === "string" &&
        /^\d+\.\d+(?:\.\d+)?(?:-[a-zA-Z0-9.-]+)?$/.test(input.versionName) &&
        Number.isSafeInteger(input.versionCode) &&
        input.versionCode > 0,
      400,
      "A valid version and whole build number are required",
    );
    requireThat(
      input.expectedVersionCode === current.release.versionCode,
      409,
      "Live release changed; reload before publishing",
    );
    requireThat(
      input.allowRollback === true ||
        input.versionCode > current.release.versionCode,
      409,
      "New updates need a higher build number",
    );
    let content, downloadUrl;
    const filename =
      "Bingo-" + input.versionName + "-build" + input.versionCode + ".apk";
    if (input.apkBase64) {
      requireThat(
        typeof input.apkBase64 === "string" &&
          input.apkBase64.length <= Math.ceil(MAX_APK / 3) * 4 &&
          /^[A-Za-z0-9+/]+=*$/.test(input.apkBase64),
        413,
        "APK must be at most 12 MiB",
      );
      try {
        content = bytes64(input.apkBase64);
      } catch {
        throw new Problem(400, "Invalid APK encoding");
      }
      downloadUrl = SITE + filename;
    } else {
      let url;
      try {
        url = new URL(input.downloadUrl);
      } catch {
        throw new Problem(
          400,
          "Select a compiled APK or provide its HTTPS download URL",
        );
      }
      requireThat(
        url.protocol === "https:" &&
          !url.username &&
          !url.password &&
          !url.port &&
          ((url.hostname === "github.com" &&
            url.pathname.startsWith(
              "/VamsiReddyBora/BINGO/releases/download/",
            )) ||
            (url.hostname === "vamsireddybora.github.io" &&
              url.pathname.startsWith("/BINGO/"))) &&
          url.pathname.endsWith(".apk"),
        400,
        "Use this repository’s GitHub release or Pages APK URL",
      );
      const res = await this.transport(url.href, {
        redirect: "follow",
        signal: AbortSignal.timeout(25000),
      });
      requireThat(res.ok, 503, "The APK download is unavailable");
      requireThat(
        Number(res.headers.get("Content-Length") || 0) <= MAX_APK,
        413,
        "APK exceeds the 12 MiB upload limit",
      );
      const parts = [];
      let size = 0;
      const reader = res.body.getReader();
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        size += value.length;
        if (size > MAX_APK) {
          await reader.cancel();
          throw new Problem(413, "APK exceeds the 12 MiB upload limit");
        }
        parts.push(value);
      }
      content = new Uint8Array(size);
      let offset = 0;
      for (const v of parts) {
        content.set(v, offset);
        offset += v.length;
      }
      downloadUrl = url.href;
    }
    requireThat(
      content.length > 1000 &&
        content.length <= MAX_APK &&
        content[0] === 80 &&
        content[1] === 75,
      400,
      "The file is not an APK/ZIP archive",
    );
    const sha256 = hex(await crypto.subtle.digest("SHA-256", content));
    requireThat(
      !input.sha256 || input.sha256.toLowerCase() === sha256,
      400,
      "APK checksum does not match",
    );
    if (!input.apkBase64) {
      let binary = "";
      for (let i = 0; i < content.length; i += 8192)
        binary += String.fromCharCode(...content.subarray(i, i + 8192));
      input = { ...input, apkBase64: btoa(binary) };
    }
    const blob = await this.api("/git/blobs", "POST", {
      content: input.apkBase64,
      encoding: "base64",
    });
    return {
      manifest: {
        versionName: input.versionName,
        versionCode: input.versionCode,
        tagName: "v" + input.versionName,
        downloadUrl,
        apkSize: content.length,
        sha256,
        releaseNotes: String(input.releaseNotes || "").slice(0, 8000),
      },
      apkBlob: blob.sha,
      filename,
      expectedVersionCode: current.release.versionCode,
      allowRollback: input.allowRollback === true,
    };
  }
  async publish(data) {
    const current = await this.feed();
    if (!equal(current.release, data.manifest)) {
      requireThat(
        current.release.versionCode === data.expectedVersionCode,
        409,
        "A newer release superseded this pending update; do not overwrite it",
      );
      const parent = await this.api("/git/commits/" + current.head);
      const existing = await this.api(
        "/git/trees/" + parent.tree.sha + "?recursive=1",
      );
      requireThat(
        !existing.truncated,
        503,
        "Could not verify the existing download inventory",
      );
      const unique = existing.tree.find((p) => p.path === data.filename);
      requireThat(
        !unique || unique.sha === data.apkBlob,
        409,
        "An immutable APK with this version/build already exists",
      );
      const metadata = await this.api("/git/blobs", "POST", {
        content: JSON.stringify(data.manifest, null, 2) + "\n",
        encoding: "utf-8",
      });
      const tree = await this.api("/git/trees", "POST", {
        base_tree: parent.tree.sha,
        tree: [
          ...["Bingo.apk", data.filename].map((path) => ({
            path,
            mode: "100644",
            type: "blob",
            sha: data.apkBlob,
          })),
          {
            path: "version.json",
            mode: "100644",
            type: "blob",
            sha: metadata.sha,
          },
        ],
      });
      const identity = {
        name: "VamsiReddyBora",
        email: "203189347+VamsiReddyBora@users.noreply.github.com",
      };
      const commit = await this.api("/git/commits", "POST", {
        message:
          "Admin: publish " +
          data.manifest.versionName +
          " build " +
          data.manifest.versionCode,
        tree: tree.sha,
        parents: [current.head],
        author: identity,
        committer: identity,
      });
      await this.api("/git/refs/heads/gh-pages", "PATCH", {
        sha: commit.sha,
        force: false,
      });
      data.pagesCommit = commit.sha;
    }
    const legacy = decodeLegacy(await this.legacy.get("latest_app_version"));
    requireThat(
      !legacy?.versionCode ||
        legacy.versionCode <= data.manifest.versionCode ||
        data.allowRollback,
      409,
      "Fallback metadata contains a newer build; inspect it before retrying",
    );
    await this.legacy.set("latest_app_version", JSON.stringify(data.manifest));
    const verified = decodeLegacy(await this.legacy.get("latest_app_version"));
    requireThat(
      equal(verified, data.manifest),
      503,
      "Fallback metadata has not verified; the job will retry",
    );
    return {
      pagesCommit: data.pagesCommit || current.head,
      deploymentPending: true,
    };
  }
}
