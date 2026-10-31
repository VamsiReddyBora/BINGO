export class MemoryStorage {
  map = new Map();
  alarmAt = 0;
  queue = Promise.resolve();
  async get(k) {
    return structuredClone(this.map.get(k));
  }
  async put(k, v) {
    this.map.set(k, structuredClone(v));
  }
  async delete(k) {
    this.map.delete(k);
  }
  async list({ prefix = "", reverse = false, limit = 10000 } = {}) {
    let a = [...this.map]
      .filter(([k]) => k.startsWith(prefix))
      .sort(([a], [b]) => a.localeCompare(b));
    if (reverse) a.reverse();
    return new Map(structuredClone(a.slice(0, limit)));
  }
  async setAlarm(n) {
    this.alarmAt = n;
  }
  async getAlarm() {
    return this.alarmAt || null;
  }
  async transaction(action) {
    const task = this.queue.then(async () => {
      const old = structuredClone(this.map);
      try {
        return await action(this);
      } catch (e) {
        this.map = old;
        throw e;
      }
    });
    this.queue = task.catch(() => {});
    return task;
  }
}
export class FakeLegacy {
  players = new Map(
    ["kevin", "stuart", "bob"].map((name, i) => [
      name,
      {
        username: name,
        profile: {
          uid: "uid_" + name,
          username: name,
          displayName: name,
          gamesPlayed: 10,
          gamesWon: 5,
          level: 2,
          currentStreak: 1,
          bestStreak: 3,
          avatarUrl: "https://example.test/photo.jpg",
          adminRevision: 0,
        },
        binId: "bin_" + name,
        backup: {},
        matchHistory: [
          { id: "m1", timestamp: 100 },
          { id: "m2", timestamp: 200 },
        ],
        fetchedAt: Date.now(),
      },
    ]),
  );
  rosters = new Map();
  writes = [];
  failDirectory = false;
  failRead = false;
  failWrite = false;
  async names() {
    if (this.failDirectory) throw new Error("network");
    return [...this.players.keys()];
  }
  async read(n) {
    if (this.failRead) throw new Error("network");
    if (!this.players.has(n)) throw new Error("missing");
    return structuredClone({ ...this.players.get(n), fetchedAt: Date.now() });
  }
  async backup(bin) {
    const p = [...this.players.values()].find((p) => p.binId === bin);
    return { profile: p.profile, matchHistory: p.matchHistory };
  }
  async friends(n) {
    return structuredClone(this.rosters.get(n) || []);
  }
  async mirrorProfile(n, p) {
    this.writes.push(["profile", n]);
    if (this.failWrite) throw new Error("unavailable");
    this.players.get(n).profile = structuredClone(p);
  }
  async mirrorFriend(a, b, present) {
    this.writes.push(["friend", a.username, b.username, present]);
    if (this.failWrite) throw new Error("unavailable");
    for (const [own, other] of [
      [a, b],
      [b, a],
    ]) {
      let list = (this.rosters.get(own.username) || []).filter(
        (f) => f.username !== other.username,
      );
      if (present)
        list.push({ uid: other.profile.uid, username: other.username });
      this.rosters.set(own.username, list);
    }
  }
  async createAccount(profile) {
    this.writes.push(["create", profile.username]);
    if (this.failWrite) throw new Error("unavailable");
    const current = this.players.get(profile.username);
    if (current && current.profile.uid !== profile.uid)
      throw new Error("identity conflict");
    this.players.set(profile.username, {
      username: profile.username,
      profile: structuredClone(profile),
      binId: "bin_" + profile.username,
      matchHistory: [],
      fetchedAt: Date.now(),
    });
  }
  async deleteAccount(n) {
    this.writes.push(["delete", n]);
    if (this.failWrite) throw new Error("unavailable");
    this.players.delete(n);
  }
  async writeBackup(n, b, data) {
    this.players.get(n).matchHistory = data.matchHistory;
  }
  async collection() {
    return [];
  }
  async firestoreDelete() {}
  async get(k) {
    if (k === "latest_app_version")
      return JSON.stringify({ versionCode: 45, versionName: "1.0" });
    return "";
  }
  async set(k, v) {
    this.writes.push([k, v]);
    if (this.failWrite) throw new Error("unavailable");
  }
}
