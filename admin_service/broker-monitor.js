import { mqttConnect, mqttSubscribe, mqttPublish, PacketStream } from "./mqtt.js";
import { json } from "./http.js";
import { Problem, requireThat } from "./model.js";

export const MONITOR_INTERVAL_MS = 60_000;
export const MONITOR_RETENTION_MS = 30 * 86400_000;
const PROBES = 3;

/** Fresh TLS + MQTT subscription followed by isolated non-retained application echoes.
 * RTT starts only when each echo is published; DNS/TLS/subscribe time is excluded.
 * No retained inventory, room, profile, chat or cleanup topic is read or changed.
 */
export async function measureBrokerEcho(env, options = {}) {
  requireThat(env.MQTT_USERNAME && env.MQTT_PASSWORD, 503, "Monitor broker credentials unavailable");
  const connect = options.connect || (await import("cloudflare:sockets")).connect;
  const hostname = new URL(env.MQTT_URL).hostname;
  const socket = connect({ hostname, port: 8883 }, { secureTransport: "on" });
  let timer, writer, reader, closed = false;
  const values = [];
  const clock = options.clock || (() => performance.now());
  const uuid = crypto.randomUUID().replaceAll("-", "");
  const topic = "bingo/emqx7/probe/monitor_" + uuid;
  const parser = new PacketStream();
  const deadline = options.timeoutMs || 12_000;
  async function exchange() {
    await socket.opened;
    writer = socket.writable.getWriter(); reader = socket.readable.getReader();
    await writer.write(mqttConnect("bingo_monitor_" + uuid, env.MQTT_USERNAME, env.MQTT_PASSWORD));
    let phase = "connect", nonce = null, sentAt = 0;
    async function sendProbe() {
      nonce = crypto.randomUUID();
      sentAt = clock();
      await writer.write(mqttPublish(topic, nonce, false, 100 + values.length));
      phase = "echo";
    }
    while (!closed) {
      const { done, value } = await reader.read();
      if (done) throw Error("transport_closed");
      for (const packet of parser.push(value)) {
        if (packet.type === 2 && phase === "connect") {
          if (packet.body.length !== 2 || packet.body[1] !== 0) throw Error("connect_rejected");
          await writer.write(mqttSubscribe([topic], 1, 0)); phase = "subscribe";
        } else if (packet.type === 9 && phase === "subscribe") {
          if (packet.body.length !== 3 || packet.body[0] !== 0 || packet.body[1] !== 1 || packet.body[2] > 2)
            throw Error("subscribe_rejected");
          await sendProbe();
        } else if (packet.type === "publish") {
          if (packet.qos === 1) await writer.write(Uint8Array.of(64, 2, packet.id >> 8, packet.id & 255));
          if (phase !== "echo" || packet.retained || packet.topic !== topic || packet.payload !== nonce) continue;
          const elapsed = clock() - sentAt;
          if (!Number.isFinite(elapsed) || elapsed <= 0) throw Error("invalid_clock");
          values.push(elapsed);
          if (values.length === PROBES) return;
          await sendProbe();
        }
      }
    }
  }
  let failure = null;
  try {
    await Promise.race([exchange(), new Promise((_, reject) => {
      timer = setTimeout(() => reject(Error("probe_timeout")), deadline);
    })]);
  } catch (error) {
    const known = ["transport_closed", "connect_rejected", "subscribe_rejected", "probe_timeout", "invalid_clock"];
    failure = known.includes(error?.message) ? error.message : "transport_unavailable";
  } finally {
    closed = true; clearTimeout(timer);
    try { if (writer) await Promise.race([writer.write(Uint8Array.of(224, 0)), new Promise(r => setTimeout(r, 50))]); } catch {}
    try { await socket.close(); } catch {}
    try { await reader?.cancel(); } catch {}
    try { reader?.releaseLock(); writer?.releaseLock(); } catch {}
  }
  return { status: values.length === PROBES ? "online" : values.length ? "partial" : "unreachable",
    averageMs: values.length ? values.reduce((a, b) => a + b, 0) / values.length : null,
    minimumMs: values.length ? Math.min(...values) : null, peakMs: values.length ? Math.max(...values) : null,
    successfulProbes: values.length, failedProbes: PROBES - values.length, failure };
}

/** Dedicated storage and queue; stopping automatic cleanup cannot stop monitoring. */
export class BrokerMonitor {
  constructor(state, env) {
    this.sql = state.storage.sql;
    this.env = env;
    this.queue = Promise.resolve();
    this.probe = env.TEST_MONITOR_PROBE || (() => measureBrokerEcho(env));
    this.sql.exec(`CREATE TABLE IF NOT EXISTS monitor_samples (
      minute INTEGER PRIMARY KEY, observed_at INTEGER NOT NULL,
      status TEXT NOT NULL, average_ms REAL, minimum_ms REAL, peak_ms REAL,
      successful_probes INTEGER NOT NULL, failed_probes INTEGER NOT NULL, failure TEXT)`);
    this.sql.exec("CREATE INDEX IF NOT EXISTS monitor_observed ON monitor_samples(observed_at)");
    this.sql.exec("CREATE TABLE IF NOT EXISTS monitor_meta (key TEXT PRIMARY KEY, value INTEGER NOT NULL)");
    this.sql.exec("INSERT OR IGNORE INTO monitor_meta(key,value) VALUES('started_at',?)", Date.now());
    this.sql.exec("INSERT OR IGNORE INTO monitor_meta(key,value) VALUES('sample_count',0)");
  }
  async fetch(request) {
    const operation = this.queue.then(() => this.route(request));
    this.queue = operation.catch(() => {});
    try { return await operation; }
    catch (error) { return json({ error: error instanceof Problem ? error.message : "Monitor unavailable" }, error instanceof Problem ? error.status : 503); }
  }
  async route(request) {
    const url = new URL(request.url);
    if (url.pathname === "/tick" && request.method === "POST") return json(await this.tick());
    if (url.pathname === "/history" && request.method === "GET") return json(this.history(url));
    return json({ error: "Unknown monitor operation" }, 404);
  }
  async tick(now = Date.now()) {
    const minute = Math.floor(now / MONITOR_INTERVAL_MS) * MONITOR_INTERVAL_MS;
    const existing = this.sql.exec("SELECT minute FROM monitor_samples WHERE minute=?", minute).toArray();
    if (existing.length) return { recorded: false, duplicate: true, minute };
    let sample;
    try { sample = await this.probe(); }
    catch { sample = { status: "unreachable", averageMs: null, minimumMs: null, peakMs: null,
      successfulProbes: 0, failedProbes: PROBES, failure: "probe_unavailable" }; }
    // Validate before persistence. Failed checks are nullable, never fabricated 0ms.
    const count = sample.successfulProbes;
    requireThat(Number.isInteger(count) && count >= 0 && count <= PROBES && sample.failedProbes === PROBES - count,
      503, "Invalid monitor sample");
    requireThat(["online", "partial", "unreachable"].includes(sample.status) &&
      (count === PROBES ? sample.status === "online" : count ? sample.status === "partial" : sample.status === "unreachable"), 503, "Invalid monitor status");
    if (count) requireThat([sample.averageMs, sample.minimumMs, sample.peakMs].every(x => Number.isFinite(x) && x > 0) &&
      sample.minimumMs <= sample.averageMs && sample.averageMs <= sample.peakMs, 503, "Invalid monitor latency");
    else requireThat(sample.averageMs === null && sample.minimumMs === null && sample.peakMs === null, 503, "Invalid unavailable sample");
    this.sql.exec("INSERT INTO monitor_samples VALUES(?,?,?,?,?,?,?,?,?)", minute, now, sample.status,
      sample.averageMs, sample.minimumMs, sample.peakMs, count, sample.failedProbes, sample.failure || null);
    const pruned = this.sql.exec("SELECT COUNT(*) AS count FROM monitor_samples WHERE minute < ?", now - MONITOR_RETENTION_MS).toArray()[0].count;
    this.sql.exec("DELETE FROM monitor_samples WHERE minute < ?", now - MONITOR_RETENTION_MS);
    this.sql.exec("UPDATE monitor_meta SET value=MAX(0,value+1-?) WHERE key='sample_count'", pruned);
    const result = { recorded: true, at: now, ...sample,
      storedSamples: this.sql.exec("SELECT value AS count FROM monitor_meta WHERE key='sample_count'").toArray()[0].count };
    // No tokens, credentials, account data or MQTT payloads are logged.
    console.log(JSON.stringify({ event: "broker-monitor-recorded", at: now, status: sample.status,
      averageMs: sample.averageMs, storedSamples: result.storedSamples }));
    return result;
  }
  history(url, now = Date.now()) {
    const start = Number(url.searchParams.get("from")), end = Number(url.searchParams.get("to"));
    const limit = url.searchParams.has("points") ? Number(url.searchParams.get("points")) : 240;
    requireThat(Number.isSafeInteger(start) && Number.isSafeInteger(end) && start > 0 && end > start &&
      start < now && end - start <= MONITOR_RETENTION_MS && end <= now + MONITOR_INTERVAL_MS && start >= now - MONITOR_RETENTION_MS - MONITOR_INTERVAL_MS &&
      Number.isInteger(limit) && limit >= 8 && limit <= 480, 400, "Choose a past range within 30 days");
    const actualEnd = Math.min(now, end);
    const width = Math.max(MONITOR_INTERVAL_MS, Math.ceil((actualEnd - start) / limit / MONITOR_INTERVAL_MS) * MONITOR_INTERVAL_MS);
    const points = this.sql.exec(`SELECT MIN(observed_at) AS at,
      SUM(average_ms*successful_probes)/NULLIF(SUM(successful_probes),0) AS averageMs,
      MIN(minimum_ms) AS minimumMs, MAX(peak_ms) AS peakMs,
      SUM(successful_probes) AS successfulProbes, SUM(failed_probes) AS failedProbes,
      COUNT(*) AS observations
      FROM monitor_samples WHERE observed_at>=? AND observed_at<=?
      GROUP BY CAST((observed_at-?)/? AS INTEGER) ORDER BY at`, start, actualEnd, start, width).toArray();
    return { schema: 1, source: "cloud-monitor", measurement: "monitor-to-emqx-mqtt-echo",
      intervalMs: MONITOR_INTERVAL_MS, retentionDays: 30, from: start, to: actualEnd, bucketMs: width,
      startedAt: this.sql.exec("SELECT value FROM monitor_meta WHERE key='started_at'").toArray()[0].value,
      lastRecordedAt: this.sql.exec("SELECT MAX(observed_at) AS at FROM monitor_samples").toArray()[0].at,
      points };
  }
}
