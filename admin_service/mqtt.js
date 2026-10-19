import { Problem } from "./model.js";
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
export function mqttSubscribe(topics, id = 1) {
  return packet(
    130,
    bytes(word(id), ...topics.map((t) => bytes(string(t), [0]))),
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
    if (this.buffer.length > 512000)
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
  { messages = [], snapshot = false } = {},
  transport = fetch,
) {
  if (!env.MQTT_USERNAME || !env.MQTT_PASSWORD)
    throw new Problem(503, "Admin broker connection is not configured");
  const url = env.MQTT_URL.replace(/^wss:/, "https:").replace(/^ws:/, "http:");
  const upgrade = await transport(url, {
    headers: { Upgrade: "websocket", "Sec-WebSocket-Protocol": "mqtt" },
    signal: AbortSignal.timeout(6000),
  });
  const socket = upgrade.webSocket;
  if (!socket) throw new Problem(503, "Broker WebSocket unavailable");
  socket.accept();
  const state = { presence: {}, rooms: {}, registry: {}, connected: false };
  return new Promise((resolve, reject) => {
    let complete = false,
      quiet = null,
      pending = new Set(),
      nextId = 10,
      subscribed = false;
    const stream = new PacketStream();
    const settle = () => {
      clearTimeout(quiet);
      if (snapshot && subscribed && !pending.size)
        quiet = setTimeout(() => finish(), 500);
    };
    const finish = (error) => {
      if (complete) return;
      complete = true;
      clearTimeout(timeout);
      clearTimeout(quiet);
      try {
        socket.send(Uint8Array.of(224, 0));
        socket.close(1000, "Snapshot complete");
      } catch {}
      error ? reject(error) : resolve(state);
    };
    const timeout = setTimeout(
      () =>
        finish(
          state.connected && subscribed && messages.length === 0
            ? null
            : new Problem(503, "Broker acknowledgement timed out"),
        ),
      7500,
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
                mqttSubscribe([
                  "bingo/v3/presence/#",
                  "bingo/v3/room_meta/#",
                  "bingo/v3/registry/#",
                ]),
              );
            else if (!pending.size) return finish();
          } else if (p.type === 4) {
            pending.delete((p.body[0] << 8) | p.body[1]);
            if (!snapshot && !pending.size) finish();
            else settle();
          } else if (p.type === 9 || p.type === "publish") {
            if (p.type === 9) {
              if (
                p.body.length < 5 ||
                [...p.body.slice(2)].some((x) => x === 128)
              )
                return finish(new Problem(503, "Broker subscription rejected"));
              subscribed = true;
            }
            if (p.type === "publish") {
              const match =
                /^bingo\/v3\/(presence|room_meta|registry)\/([^/]+)$/.exec(
                  p.topic,
                );
              if (match && p.payload.length <= 150000) {
                (match[1] === "presence"
                  ? state.presence
                  : match[1] === "registry"
                    ? state.registry
                    : state.rooms)[match[2]] = p.payload;
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
