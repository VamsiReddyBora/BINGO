export const API_BASE =
  "https://bingo-admin-control.vamsireddy2534.workers.dev";
export const statusText = {
  ONLINE: "Online",
  IN_LOBBY: "In lobby",
  PLAYING: "Playing",
  AWAY: "Away",
  OFFLINE: "Offline",
  UNKNOWN: "Unknown",
};
export const statusClass = {
  ONLINE: "online",
  IN_LOBBY: "lobby",
  PLAYING: "playing",
  AWAY: "away",
  OFFLINE: "offline",
  UNKNOWN: "unknown",
};
export const activeStatus = new Set(["ONLINE", "IN_LOBBY", "PLAYING"]);
export function selectPlayers(
  players,
  { query = "", status = "ALL", provider = "ALL", sort = "name" } = {},
) {
  const q = query.trim().toLowerCase();
  return players
    .filter((row) => {
      const p = row.profile || {},
        s = row.presence?.status || "UNKNOWN";
      return (
        (!q ||
          [row.username, p.displayName, p.uid].some((v) =>
            String(v || "")
              .toLowerCase()
              .includes(q),
          )) &&
        (status === "ALL" ||
          (status === "ACTIVE" ? activeStatus.has(s) : status === s)) &&
        (provider === "ALL" || p.provider === provider)
      );
    })
    .sort((a, b) => {
      const x = a.profile || {},
        y = b.profile || {};
      if (sort === "level")
        return (
          (y.level ?? -1) - (x.level ?? -1) ||
          a.username.localeCompare(b.username)
        );
      if (sort === "played")
        return (
          (y.gamesPlayed ?? -1) - (x.gamesPlayed ?? -1) ||
          a.username.localeCompare(b.username)
        );
      if (sort === "wins")
        return (
          (y.gamesWon ?? -1) - (x.gamesWon ?? -1) ||
          a.username.localeCompare(b.username)
        );
      if (sort === "recent")
        return (b.presence?.observedAt || 0) - (a.presence?.observedAt || 0);
      return a.username.localeCompare(b.username);
    });
}
export function csvField(value) {
  let text = String(value ?? "");
  if (/^[\s]*[=+@-]/.test(text)) text = "'" + text;
  return '"' + text.replaceAll('"', '""') + '"';
}
export function playersCsv(rows) {
  return [
    [
      "Username",
      "Display name",
      "UID",
      "Status",
      "Activity",
      "Provider",
      "Level",
      "Games played",
      "Wins",
      "Current streak",
      "Best streak",
      "App version",
      "Last observation",
      "Profile source",
    ],
    ...rows.map((r) => {
      const p = r.profile || {};
      return [
        r.username,
        p.displayName,
        p.uid,
        statusText[r.presence?.status] || "Unknown",
        r.presence?.detail || "",
        p.provider,
        p.level,
        p.gamesPlayed,
        p.gamesWon,
        p.currentStreak,
        p.bestStreak,
        r.presence?.appVersion || p.appVersion,
        r.presence?.observedAt
          ? new Date(r.presence.observedAt).toISOString()
          : "",
        r.sourceStatus,
      ];
    }),
  ]
    .map((row) => row.map(csvField).join(","))
    .join("\r\n");
}
export function photoSource(value) {
  if (typeof value === "string") {
    if (value.startsWith("/9j/")) value = "data:image/jpeg;base64," + value;
    else if (value.startsWith("iVBORw0KGgo"))
      value = "data:image/png;base64," + value;
    else if (value.startsWith("UklGR"))
      value = "data:image/webp;base64," + value;
  }
  return typeof value === "string" &&
    (/^https:\/\//i.test(value) ||
      /^data:image\/(jpeg|png|webp);base64,[A-Za-z0-9+/]+=*$/.test(value))
    ? value
    : null;
}
export function matchFilter(matches, { query = "", outcome = "ALL" } = {}) {
  const q = query.toLowerCase();
  return matches
    .filter(
      (m) =>
        (outcome === "ALL" ||
          (outcome === "WIN" && m.didWin) ||
          (outcome === "DRAW" && m.isDraw) ||
          (outcome === "LOSS" && !m.didWin && !m.isDraw)) &&
        (!q ||
          [m.id, m.opponentName, m.matchTitle, m.mode].some((v) =>
            String(v || "")
              .toLowerCase()
              .includes(q),
          )),
    )
    .sort((a, b) => (b.timestamp || 0) - (a.timestamp || 0));
}
