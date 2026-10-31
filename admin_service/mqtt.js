import { Problem, rememberRoom } from "./model.js";
import { hasGameplayActivity, isLiveGameplayTopic } from "./cleanup-activity.js";
const encoder = new TextEncoder(),
  decoder = new TextDecoder();
const bytes = (...parts) =>
  Uint8Array.from(parts.flatMap((p) => Array.from(p)));
const word = (n) => [n >> 8, n & 255];
const string = (s) => bytes(word(encoder.encode(s).length), encoder.encode(s));
function packet(type, body) {
  let n = body.length,
    remaining = [];
  do {
    let b = n % 128;
    n = Math.floor(n / 128);
    if (n) b |= 128;
    remaining.push(b);
  } while (n);
  return bytes([type], remaining, body);
}
export function mqttConnect(id, user, password) {
  return packet(
    16,
    bytes(
      string("MQTT"),
      [4, 194, 0, 15],
      string(id),
      string(user),
      string(password),
    ),
  );
}
export function mqttSubscribe(topics, id = 1, qos = 0) {
  return packet(
    130,
    bytes(word(id), ...topics.map((t) => bytes(string(t), [qos]))),
  );
}
export function mqttPublish(topic, payload, retain = false, id = 1) {
  return packet(
    0x32 | (retain ? 1 : 0),
    bytes(string(topic), word(id), encoder.encode(payload)),
  );
}
export function decodePackets(input) {
  const result = [];
  let offset = 0;
  while (offset < input.length) {
    const type = input[offset++];
    let n = 0,
      m = 1,
      b,
      count = 0;
    do {
      if (offset >= input.length)
        throw new Problem(503, "Incomplete MQTT frame");
      b = input[offset++];
      n += (b & 127) * m;
      m *= 128;
      if (++count > 4) throw new Problem(503, "Invalid MQTT frame");
    } while (b & 128);
    if (offset + n > input.length)
      throw new Problem(503, "Incomplete MQTT packet");
    const body = input.slice(offset, offset + n);
    offset += n;
    if (type >> 4 === 3) {
      const len = (body[0] << 8) | body[1];
      const qos = (type >> 1) & 3;
      const start = 2 + len + (qos ? 2 : 0);
      result.push({
        type: "publish",
        topic: decoder.decode(body.slice(2, 2 + len)),
        payload: decoder.decode(body.slice(start)),
        qos,
        retained: !!(type & 1),
        id: qos ? (body[2 + len] << 8) | body[3 + len] : 0,
      });
    } else result.push({ type: type >> 4, body });
  }
  return result;
}
/** MQTT packets may span WebSocket frames or share one frame. */
export class PacketStream {
  buffer = new Uint8Array();
  push(input) {
    this.buffer = bytes(this.buffer, input);
    if (this.buffer.length > 2 * 1024 * 1024)
      throw new Problem(503, "MQTT frame exceeds limit");
    const result = [];
    let consumed = 0;
    while (consumed < this.buffer.length) {
      let offset = consumed + 1,
        n = 0,
        m = 1,
        b,
        count = 0;
      do {
        if (offset >= this.buffer.length) {
          this.buffer = this.buffer.slice(consumed);
          return result;
        }
        b = this.buffer[offset++];
        n += (b & 127) * m;
        m *= 128;
        if (++count > 4) throw new Problem(503, "Invalid MQTT length");
      } while (b & 128);
      if (offset + n > this.buffer.length) break;
      result.push(...decodePackets(this.buffer.slice(consumed, offset + n)));
      consumed = offset + n;
    }
    this.buffer = this.buffer.slice(consumed);
    return result;
  }
}
/** Native MQTT/TLS for the server; no browser broker credentials are needed. */
export async function brokerTcp(env, args, connectFactory) {
  const connect =
    connectFactory || (await import("cloudflare:sockets")).connect;
  const target = new URL(env.MQTT_URL);
  const socket = connect(
    { hostname: target.hostname, port: 8883 },
    { secureTransport: "on" },
  );
  let timer;
  try {
    await Promise.race([
      socket.opened,
      new Promise((_, reject) => {
        timer = setTimeout(
          () => reject(new Problem(503, "Broker TLS connection timed out")),
          6000,
        );
      }),
    ]);
  } catch (error) {
    try {
      socket.close();
    } catch {}
    throw error;
  } finally {
    clearTimeout(timer);
  }
  const listeners = new Map(),
    writer = socket.writable.getWriter(),
    reader = socket.readable.getReader();
  let closed = false;
  const emit = (name, event) => {
    for (const listener of listeners.get(name) || []) listener(event);
  };
  const adapter = {
    addEventListener(name, listener) {
      if (!listeners.has(name)) listeners.set(name, []);
      listeners.get(name).push(listener);
    },
    accept() {
      queueMicrotask(async () => {
        try {
          while (!closed) {
            const { done, value } = await reader.read();
            if (done) {
              emit("close", {});
              break;
            }
            emit("message", { data: value });
          }
        } catch {
          if (!closed) emit("error", {});
        }
      });
    },
    send(data) {
      writer.write(data).catch(() => {
        if (!closed) emit("error", {});
      });
    },
    close() {
      closed = true;
      socket.close();
    },
  };
  return brokerExchange(env, args, async () => ({ webSocket: adapter }));
}
/** Short-lived retained snapshot. Gameplay is never routed through this service. */
export async function brokerExchange(
  env,
  { messages = [], snapshot = false, rawFilters = null, guardedGroups = null, inventory = false,
    deferOnGameplay = false, cleanupExpiresAt = null } = {},
  transport = fetch,
) {
  if (!env.MQTT_USERNAME || !env.MQTT_PASSWORD)
    throw new Problem(503, "Admin broker connection is not configured");
  if (inventory && (!snapshot || messages.length || rawFilters || guardedGroups))
    throw new Problem(400, "Broker usage inventory is read-only");
  const url = env.MQTT_URL.replace(/^wss:/, "https:").replace(/^ws:/, "http:");
  const upgrade = await transport(url, {
    headers: { Upgrade: "websocket", "Sec-WebSocket-Protocol": "mqtt" },
    signal: AbortSignal.timeout(6000),
  });
  const socket = upgrade.webSocket;
  if (!socket) throw new Problem(503, "Broker WebSocket unavailable");
  socket.accept();
  const state = { presence: {}, rooms: {}, registry: {}, connected: false };
  const fenceTopic = rawFilters || inventory ? "bingo/emqx7/probe/cleanup_"+crypto.randomUUID() : null;
  // Serverless EMQX rejects a root '#' subscription. Count the complete Bingo
  // namespace rather than just the eight filters eligible for deletion.
  const subscriptionFilters = inventory ? ["bingo/#"] : rawFilters ? [...new Set([...rawFilters,...(deferOnGameplay?["bingo/emqx7/reliable/+/+/state"]:[])]),fenceTopic] : ["bingo/v3/presence/#","bingo/v3/room_meta/#","bingo/v3/registry/#","bingo/emqx7/metadata/+/state"];
  const retainedTopics = inventory ? new Set() : null;
  if (rawFilters) {
    if (!snapshot || messages.length || rawFilters.length > 8 || rawFilters.some(t => !t.startsWith("bingo/")))
      throw new Problem(400, "Invalid maintenance scope");
    state.retainedRecords = {};
    state.cleanup = { acknowledged: [], skipped: 0, deferred: false };
  }
  return new Promise((resolve, reject) => {
    let complete = false,
      quiet = null,
      pending = new Set(),
      nextId = 10,
      subscribed = false,
      deleting = false,
      fenceSeen = !fenceTopic;
    let gameplayObserved = false;
    const ackWaiters = new Map();
    const startedAt = Date.now();
    const stream = new PacketStream();
    async function guardedDelete() {
      deleting = true;
      try {
        for (const group of guardedGroups) {
          const current = Object.fromEntries(Object.entries(state.retainedRecords).filter(([t]) => group.prefixes.some(p => p.endsWith("/") ? t.startsWith(p) : t === p)));
          if (Object.keys(current).length !== Object.keys(group.entries).length || Object.entries(group.entries).some(([t,p]) => current[t] !== p)) {
            state.cleanup.skipped += group.topics.length; continue;
          }
          const removed = new Set();
          for (let position=0;position<group.topics.length;position++) {
            const topic=group.topics[position];
            if (complete) return;
            if ((deferOnGameplay && (gameplayObserved || hasGameplayActivity(state.retainedRecords))) ||
                (cleanupExpiresAt && Date.now()>=cleanupExpiresAt)) {
              state.cleanup.deferred=true;
              state.cleanup.skipped+=guardedGroups.reduce((n,g)=>n+g.topics.length,0)-state.cleanup.acknowledged.length-state.cleanup.skipped;
              finish();return;
            }
            const observed=Object.fromEntries(Object.entries(state.retainedRecords).filter(([t])=>!removed.has(t) && group.prefixes.some(p=>p.endsWith('/')?t.startsWith(p):t===p)));
            const expected=Object.entries(group.entries).filter(([t])=>!removed.has(t));
            if(Object.keys(observed).length!==expected.length || expected.some(([t,p])=>observed[t]!==p)) {
              state.cleanup.skipped+=group.topics.length-position;break;
            }
            if (!topic.startsWith("bingo/") || /[+#\x00]/.test(topic) || state.retainedRecords[topic] !== group.entries[topic]) { state.cleanup.skipped++; continue; }
            const id = nextId++;
            await new Promise((resolveAck, rejectAck) => {
              const timer = setTimeout(() => { ackWaiters.delete(id); rejectAck(new Problem(503,"Cleanup acknowledgement timed out")); },6000);
              ackWaiters.set(id,() => { clearTimeout(timer); state.cleanup.acknowledged.push(topic); resolveAck(); });
              socket.send(mqttPublish(topic,"",true,id));
            });
            removed.add(topic);
            await new Promise(r => setTimeout(r,150));
          }
        }
        finish();
      } catch (error) { finish(error); }
    }
    const settle = () => {
      clearTimeout(quiet);
      if(rawFilters && deferOnGameplay && (gameplayObserved || hasGameplayActivity(state.retainedRecords)))state.cleanup.deferred=true;
      if (snapshot && subscribed && fenceSeen && !pending.size && !deleting)
        quiet = setTimeout(() => guardedGroups ? guardedDelete() : finish(), rawFilters || inventory ? Math.max(1200,2500-(Date.now()-startedAt)) : 500);
    };
    const finish = (error) => {
      if (complete) return;
      complete = true;
      clearTimeout(timeout);
      clearTimeout(quiet);
      if (inventory && !error) {
        const bingo = [...retainedTopics].filter(t => t.startsWith("bingo/")).length;
        state.retainedUsage = { used: retainedTopics.size, limit: 2000, bingo, other: retainedTopics.size-bingo,
          observedAt: Date.now(), scope: "bingo-topics" };
      }
      try {
        socket.send(Uint8Array.of(224, 0));
        socket.close(1000, "Snapshot complete");
      } catch {}
      error ? reject(error) : resolve(state);
    };
    const timeout = setTimeout(
      () =>
        finish(
          !rawFilters && !inventory && state.connected && subscribed && messages.length === 0
            ? null
            : new Problem(503, "Broker acknowledgement timed out"),
        ),
      rawFilters || inventory ? (guardedGroups ? 120000 : 20000) : 7500,
    );
    socket.addEventListener("error", () =>
      finish(new Problem(503, "Broker connection failed")),
    );
    socket.addEventListener("close", () => {
      if (!complete) finish(new Problem(503, "Broker connection closed"));
    });
    socket.addEventListener("message", (event) => {
      try {
        const raw =
          typeof event.data === "string"
            ? encoder.encode(event.data)
            : new Uint8Array(event.data);
        for (const p of stream.push(raw)) {
          if (p.type === 2) {
            if (p.body[1] !== 0)
              return finish(new Problem(503, "Broker authentication rejected"));
            state.connected = true;
            for (const message of messages) {
              const id = nextId++;
              pending.add(id);
              socket.send(
                mqttPublish(message.topic, message.payload, message.retain, id),
              );
            }
            if (snapshot)
              socket.send(
                // QoS 1 retained fan-out can overflow Serverless's 1,000-entry
                // subscriber queue before all ~2,000 records arrive. Inventory
                // uses the existing QoS 0 TCP snapshot path instead.
                mqttSubscribe(subscriptionFilters),
              );
            else if (!pending.size) return finish();
          } else if (p.type === 4) {
            const acknowledgedId = (p.body[0] << 8) | p.body[1];
            const waiter = ackWaiters.get(acknowledgedId);
            if (waiter) { ackWaiters.delete(acknowledgedId); waiter(); continue; }
            pending.delete((p.body[0] << 8) | p.body[1]);
            if (!snapshot && !pending.size) finish();
            else settle();
          } else if (p.type === 9 || p.type === "publish") {
            if (p.type === 9) {
              if (
                p.body.length !== 2+subscriptionFilters.length ||
                [...p.body.slice(2)].some((x) => x === 128)
              )
                return finish(new Problem(503, "Broker subscription rejected"));
              subscribed = true;
              if (fenceTopic) socket.send(mqttPublish(fenceTopic,"cleanup-fence",false,nextId++));
            }
            if (p.type === "publish") {
              if (fenceTopic && p.topic === fenceTopic) {
                fenceSeen = p.payload === "cleanup-fence";
                if (p.qos === 1) socket.send(bytes([64, 2], word(p.id)));
                settle(); continue;
              }
              if (inventory) {
                if (p.retained && p.payload) retainedTopics.add(p.topic);
                if (p.qos === 1) socket.send(bytes([64, 2], word(p.id)));
                // Ongoing live traffic is not retained usage and must not keep
                // postponing the end of a usage snapshot during a busy match.
                if (p.retained) settle();
                continue;
              }
              if(rawFilters && deferOnGameplay && !p.retained && isLiveGameplayTopic(p.topic,p.payload))gameplayObserved=true;
              if (rawFilters && p.payload.length <= 700000) {
                if (!p.payload) delete state.retainedRecords[p.topic];
                else if (p.retained || Object.hasOwn(state.retainedRecords,p.topic) ||
                  guardedGroups?.some(g => g.prefixes.some(prefix => prefix.endsWith('/') ? p.topic.startsWith(prefix) : p.topic === prefix)))
                  state.retainedRecords[p.topic] = p.payload;
              }
              if (rawFilters && p.retained && p.payload.length > 700000)
                state.retainedRecords[p.topic] = null; // Oversized/unknown data blocks group cleanup.
              const current = /^bingo\/emqx7\/metadata\/([^/]+)\/state$/.exec(
                p.topic,
              );
              if (current && p.payload.length <= 150000)
                rememberRoom(state.rooms, current[1], p.payload);
              const match =
                /^bingo\/v3\/(presence|room_meta|registry)\/([^/]+)$/.exec(
                  p.topic,
                );
              if (match && p.payload.length <= 150000) {
                if (match[1] === "room_meta")
                  rememberRoom(state.rooms, match[2], p.payload);
                else
                  (match[1] === "presence" ? state.presence : state.registry)[
                    match[2]
                  ] = p.payload;
              }
              if (p.qos === 1) socket.send(bytes([64, 2], word(p.id)));
            }
            settle();
          }
        }
      } catch {
        finish(new Problem(503, "Invalid broker response"));
      }
    });
    socket.send(
      mqttConnect(
        "admin_read_" + crypto.randomUUID().slice(0, 12),
        env.MQTT_USERNAME,
        env.MQTT_PASSWORD,
      ),
    );
  });
}
