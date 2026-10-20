import { HttpError, requireThat, response } from "./protocol.js";

// Room discovery metadata only. Picks, turn advancement and game results stay on MQTT.
// Each room uses its own Durable Object instance and serial request queue.
function inspect(payload) {
  requireThat(
    typeof payload === "string" && payload.length <= 120000,
    400,
    "Invalid room payload",
  );
  let room;
  try {
    room = JSON.parse(payload);
  } catch {
    throw new HttpError(400, "Invalid room JSON");
  }
  const seed =
    payload.match(/(?:^|[,{])\s*"currentSeed"\s*:\s*(-?\d+)/)?.[1] || "0";
  requireThat(
    room &&
      /^[A-Z0-9]{6}$/.test(room.roomCode) &&
      ["WAITING", "PLAYING", "CLOSED"].includes(room.status) &&
      typeof room.hostId === "string" &&
      room.hostId.length > 0 &&
      Array.isArray(room.players) &&
      room.players.length <= 8 &&
      room.players.every((p) => typeof p.id === "string" && p.id.length > 0),
    400,
    "Invalid room metadata",
  );
  return { room, seed };
}

async function legacyText(env, key) {
  const result = await fetch(
    `${env.LEGACY_API_URL}/GetValue/${encodeURIComponent(env.LEGACY_APP_KEY)}/${key}`,
    { signal: AbortSignal.timeout(2500) },
  );
  requireThat(result.ok, 503, "Room migration is unavailable");
  let raw = (await result.text()).trim();
  if (!raw || raw === "null" || raw === '""') return null;
  if (raw.startsWith('"')) {
    try {
      raw = JSON.parse(raw);
    } catch {
      throw new HttpError(503, "Invalid legacy room");
    }
  }
  if (raw.startsWith("{") || raw === "true" || raw === "false") return raw;
  const compressed = raw.startsWith("GZ:");
  const binary = atob(
    (compressed ? raw.slice(3) : raw).replace(/-/g, "+").replace(/_/g, "/"),
  );
  const bytes = Uint8Array.from(binary, (c) => c.charCodeAt(0));
  return compressed
    ? new Response(
        new Blob([bytes]).stream().pipeThrough(new DecompressionStream("gzip")),
      ).text()
    : new TextDecoder().decode(bytes);
}

async function readRecord(storage, env, code) {
  const record = await storage.get("room-record");
  if (record) return record;
  // Read-only legacy users keep their old behavior. Adoption happens on the first
  // authenticated CAS commit; thereafter legacy writes cannot replace this record.
  const payload = await legacyText(env, `room_${code}`);
  if (!payload) return null;
  let info;
  try {
    info = inspect(payload);
  } catch {
    throw new HttpError(503, "Legacy room is unavailable");
  }
  requireThat(info.room.roomCode === code, 503, "Legacy room mismatch");
  const closed = await legacyText(env, `room_closed_${code}`);
  const imported = {
    payload,
    revision: Number(info.room.r || 0),
    seed: info.seed,
    status: closed === "true" ? "CLOSED" : info.room.status,
    hostId: info.room.hostId,
    players: info.room.players.map((p) => p.id),
  };
  return imported;
}

export async function roomMetadata(storage, env, body) {
  const code = body.code;
  requireThat(
    typeof code === "string" && /^[A-Z0-9]{6}$/.test(code),
    400,
    "Invalid room code",
  );
  const current = await readRecord(storage, env, code);
  const envelope = (record) => ({
    payload: record?.payload ?? null,
    revision: record?.revision ?? 0,
    closed: record?.status === "CLOSED",
  });
  if (body.action === "read") return response(envelope(current));
  requireThat(
    body.action === "commit" && typeof body.actorUid === "string",
    400,
    "Invalid room operation",
  );
  const { room: proposed, seed } = inspect(body.payload);
  requireThat(proposed.roomCode === code, 400, "Room mismatch");
  requireThat(
    Number.isSafeInteger(body.expectedRevision) && body.expectedRevision >= 0,
    400,
    "Invalid revision",
  );
  // This comparison and storage.put run inside the room object's serial fetch queue.
  if (
    body.expectedRevision !== (current?.revision ?? 0) ||
    (current && body.expectedSeed !== current.seed)
  )
    return response(envelope(current), 409);
  if (current?.status === "CLOSED") throw new HttpError(410, "Room is closed");
  if (current?.status === "PLAYING" && proposed.status === "PLAYING") {
    requireThat(
      proposed.players.every((p) => current.players.includes(p.id)),
      403,
      "Cannot admit new participants to a running match",
    );
  }
  if (!current) {
    requireThat(
      proposed.status === "WAITING" &&
        proposed.hostId === body.actorUid &&
        proposed.players.some((p) => p.id === body.actorUid),
      403,
      "Only the host may create a room",
    );
  } else if (!current.players.includes(body.actorUid)) {
    // Admission may only add the authenticated player to an unchanged waiting lobby.
    const base = JSON.parse(current.payload);
    const keys = [
      "roomCode",
      "hostId",
      "hostUsername",
      "hostDisplayName",
      "hostAvatarUrl",
      "status",
      "boardSize",
      "createdAt",
      "currentSeed",
      "isManualBoard",
      "isDynamicBoard",
      "currentTurnPlayerId",
      "turnClock",
      "simplifiedRulesVersion",
      "protocolVersion",
      "startCommitDeadline",
      "canonicalStartPacketJson",
      "manualPreparationComplete",
      "mp",
      "tc",
    ];
    const metadata = (value) =>
      JSON.stringify(keys.map((key) => value[key] ?? null));
    requireThat(
      current.status === "WAITING" &&
        proposed.status === "WAITING" &&
        proposed.players.some((p) => p.id === body.actorUid) &&
        current.players.every((id) =>
          proposed.players.some((p) => p.id === id),
        ) &&
        proposed.players.length <= current.players.length + 1 &&
        metadata(proposed) === metadata(base),
      403,
      "Participant admission is not authorized",
    );
  }
  const next = {
    payload: body.payload,
    revision: (current?.revision ?? 0) + 1,
    seed,
    status: proposed.status,
    hostId: proposed.hostId,
    players: proposed.players.map((p) => p.id),
  };
  await storage.put("room-record", next);
  return response(envelope(next));
}
