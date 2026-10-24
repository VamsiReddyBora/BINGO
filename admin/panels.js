import { selectRooms, matchFilter, activeStatus } from "./model.js";

export function createPanels({
  state,
  h,
  $,
  api,
  button,
  badge,
  avatar,
  stamp,
  age,
  num,
  toast,
  openPlayer,
  confirmAction,
  refresh,
  rows,
  switchTab,
}) {
  let matchRows = [],
    matchSequence = 0;
  const empty = (text) =>
    h(
      "div",
      { class: "empty" },
      h("span", { "data-icon": "rooms" }),
      h("p", {}, text),
    );
  const metric = (name, value, description) =>
    h(
      "article",
      { class: "stat" },
      h("span", {}, name),
      h("strong", {}, num(value)),
      h("small", {}, description),
    );
  function overview() {
    const players = rows(),
      snapshot = state.snapshot,
      rooms = snapshot?.rooms || [],
      active = rooms.filter(
        (r) => !["CLOSED", "CANCELLED", "FINISHED"].includes(r.status),
      );
    const online = players.filter((p) => activeStatus.has(p.presence?.status));
    $("overview-metrics").replaceChildren(
      metric(
        "Registered players",
        snapshot ? players.length : null,
        "Verified Firebase accounts",
      ),
      metric(
        "Active now",
        snapshot ? online.length : null,
        "Online · in lobby · playing",
      ),
      metric("Open rooms", snapshot ? active.length : null, "Observed on MQTT"),
      metric(
        "Matches in progress",
        snapshot ? active.filter((r) => r.status === "PLAYING").length : null,
        "Room metadata, not a result",
      ),
    );
    $("overview-status").textContent = state.stale
      ? "Snapshot out of date"
      : snapshot?.presenceConnected
        ? "Live observations connected"
        : "Awaiting live observations";
    $("overview-status").className =
      "connection " +
      (snapshot?.presenceConnected && !state.stale ? "live" : "stale");
    const live = $("overview-players");
    live.replaceChildren();
    const selected = [...players]
      .sort(
        (a, b) => (b.presence?.observedAt || 0) - (a.presence?.observedAt || 0),
      )
      .slice(0, 6);
    for (const p of selected)
      live.append(
        button(
          h(
            "span",
            { class: "roster-entry" },
            avatar(p.profile),
            h(
              "span",
              {},
              h("strong", {}, p.profile.displayName || p.username),
              h("small", {}, "@" + p.username),
            ),
            badge(p.presence?.status || "UNKNOWN"),
            h(
              "small",
              { class: "observation-age" },
              age(p.presence?.observedAt),
            ),
          ),
          () => openPlayer(p.username),
          "roster-button",
        ),
      );
    if (!selected.length)
      live.append(
        empty("Player observations will appear after a verified refresh."),
      );
    const health = $("overview-health");
    health.replaceChildren(
      healthRow(
        "Player storage",
        state.stale
          ? "Refresh failed"
          : snapshot?.directoryVerified
            ? "Firebase verified"
            : "Waiting",
        !state.stale && snapshot?.directoryVerified,
      ),
      healthRow(
        "MQTT observations",
        snapshot?.presenceConnected && !state.stale
          ? "Connected"
          : "Unavailable",
        snapshot?.presenceConnected && !state.stale,
      ),
      healthRow(
        "Last verified refresh",
        snapshot ? stamp(snapshot.fetchedAt) : "No snapshot",
        !!snapshot && !state.stale,
      ),
      healthRow("Administrative access", "Passkey verified", true),
    );
    $("overview-room-list").replaceChildren(
      ...active
        .slice(0, 4)
        .map((r) =>
          button(
            h(
              "span",
              { class: "roster-entry" },
              h("strong", { class: "mono" }, "#" + r.code),
              h(
                "span",
                {},
                r.hostDisplayName || r.hostUsername || "Host not reported",
              ),
              badge(r.status === "PLAYING" ? "PLAYING" : "IN_LOBBY", r.status),
              h("small", {}, (r.players?.length || 0) + " players"),
            ),
            () => inspectRoom(r),
            "roster-button",
          ),
        ),
    );
    if (!active.length)
      $("overview-room-list").append(
        empty(
          snapshot?.presenceConnected
            ? "No active rooms observed."
            : "Room observations are unavailable.",
        ),
      );
  }
  function healthRow(label, text, ok) {
    return h(
      "div",
      { class: "health-row" },
      h("span", {}, label),
      h(
        "strong",
        { class: ok ? "text-live" : "muted" },
        h("i", { class: "status-light " + (ok ? "" : "dim") }),
        text,
      ),
    );
  }
  function inspectRoom(room) {
    $("room-inspector-title").textContent = "Room #" + room.code;
    const root = $("room-inspector-body");
    root.replaceChildren(
      h(
        "div",
        { class: "room-summary" },
        badge(room.status === "PLAYING" ? "PLAYING" : "IN_LOBBY", room.status),
        h(
          "span",
          { class: "muted" },
          room.sourceStatus === "STALE"
            ? "Older metadata observation"
            : "Latest observed metadata",
        ),
      ),
      h(
        "div",
        { class: "detail-grid" },
        ...Object.entries({
          Host: room.hostDisplayName || room.hostUsername || room.hostId,
          Board: room.boardSize + " × " + room.boardSize,
          Players: room.players?.length || 0,
          Protocol: room.metadataProtocolVersion || "Legacy",
          Revision: room.revision ?? "—",
          Session: room.sessionId || "—",
          Created: stamp(room.createdAt),
          "Last heartbeat": stamp(room.lastHeartbeat),
        }).map(([key, value]) =>
          h(
            "div",
            {},
            h("small", { class: "muted" }, key),
            h(
              "strong",
              { class: key === "Session" ? "mono" : "" },
              String(value),
            ),
          ),
        ),
      ),
      h("h3", {}, "Player roster"),
    );
    for (const p of room.players || []) {
      const name =
        p.username?.replace(/^@/, "") ||
        state.snapshot?.players?.find((r) => r.profile.uid === p.id)?.username;
      const content = h(
        "span",
        { class: "roster-entry" },
        avatar(p),
        h(
          "span",
          {},
          h("strong", {}, p.name || p.displayName || name || p.id),
          h("small", {}, p.isHost ? "Host" : p.readyStatus || "Player"),
        ),
      );
      root.append(
        name
          ? button(
              content,
              () => {
                $("room-inspector").close();
                openPlayer(name);
              },
              "roster-button",
            )
          : h("div", { class: "health-row" }, content),
      );
    }
    root.append(
      h(
        "p",
        { class: "notice" },
        "This is a read-only room inspection. A retained snapshot can be older than the live game. Active matches are never closed as stale rooms.",
      ),
      h(
        "details",
        {},
        h("summary", {}, "Inspect reported room data"),
        h("pre", { class: "raw-data" }, JSON.stringify(room, null, 2)),
      ),
    );
    $("room-inspector").showModal();
  }
  function rooms() {
    const result = selectRooms(state.snapshot?.rooms || [], {
      query: $("room-search").value,
      status: $("room-status").value,
      sort: $("room-sort").value,
    });
    $("room-result-count").textContent =
      `${result.length} rooms · ${state.stale ? "last snapshot; current state unknown" : "latest available observations"}`;
    $("rooms").replaceChildren(
      ...result.map((room) =>
        h(
          "article",
          { class: "panel room-card" },
          h(
            "div",
            { class: "room-card-heading" },
            h("span", { class: "room-glyph" }, "▦"),
            badge(
              room.status === "PLAYING"
                ? "PLAYING"
                : room.status === "WAITING"
                  ? "IN_LOBBY"
                  : "OFFLINE",
              room.status || "Unknown",
            ),
          ),
          h("h2", { class: "mono" }, "#" + room.code),
          h(
            "p",
            { class: "muted" },
            room.hostDisplayName || room.hostUsername || "Host not reported",
          ),
          h(
            "div",
            { class: "room-facts" },
            h(
              "span",
              {},
              h("strong", {}, num(room.players?.length || 0)),
              " players",
            ),
            h(
              "span",
              {},
              h("strong", {}, room.boardSize || "?"),
              " × " + (room.boardSize || "?") + " board",
            ),
          ),
          h(
            "div",
            { class: "room-avatar-row" },
            ...(room.players || [])
              .slice(0, 8)
              .map((p) =>
                avatar({ ...p, displayName: p.name || p.displayName }),
              ),
          ),
          h(
            "small",
            { class: "muted" },
            "Metadata " +
              age(room.lastHeartbeat || room.createdAt) +
              (room.sourceStatus === "STALE" ? " · older observation" : ""),
          ),
          button("Inspect room ↗", () => inspectRoom(room), "wide"),
          room.metadataProtocolVersion === 7
            ? null
            : button(
                "Close lobby",
                async () => {
                  if (
                    !(await confirmAction(
                      "Close legacy lobby",
                      "Close the observed legacy waiting lobby #" +
                        room.code +
                        "?",
                    ))
                  )
                    return;
                  try {
                    await api(
                      "rooms/" + encodeURIComponent(room.code) + "/close",
                      { method: "POST", data: {} },
                    );
                    toast("Lobby closure acknowledged.", "success");
                    await refresh();
                  } catch (e) {
                    toast(e.message, "error");
                  }
                },
                "danger",
                { disabled: room.status !== "WAITING" },
              ),
        ),
      ),
    );
    if (!result.length)
      $("rooms").append(
        empty(
          state.snapshot?.presenceConnected
            ? "No rooms match these filters."
            : "MQTT room observations are unavailable.",
        ),
      );
  }
  async function matches() {
    const generation = state.generation,
      sequence = ++matchSequence;
    $("match-records").replaceChildren(
      h("div", { class: "loading" }, "Reading recent Firebase match records…"),
    );
    try {
      const result = await api("matches");
      if (generation !== state.generation || sequence !== matchSequence) return;
      matchRows = result.matches || [];
      $("match-fetched").textContent =
        `Latest ${result.limit} records at most · fetched ${stamp(result.fetchedAt)} · each player’s saved result is a separate record`;
      drawMatches();
    } catch (e) {
      if (generation === state.generation && sequence === matchSequence)
        $("match-records").replaceChildren(
          h("div", { class: "notice warning" }, e.message),
        );
    }
  }
  function drawMatches() {
    const result = matchFilter(matchRows, {
      query: $("match-search").value,
      outcome: $("match-outcome").value,
    });
    const mode = $("match-mode").value;
    const filtered =
      mode === "ALL"
        ? result
        : result.filter((m) =>
            String(m.mode || "")
              .toUpperCase()
              .includes(mode),
          );
    $("match-records").replaceChildren(
      ...filtered.map((m) => {
        const owner = state.snapshot?.players?.find(
          (p) => p.profile.uid === m.ownerUid,
        );
        return h(
          "article",
          { class: "panel match-record" },
          h(
            "div",
            { class: "match-outcome " + (m.didWin ? "text-live" : "") },
            m.isDraw ? "DRAW" : m.didWin ? "WIN" : "LOSS",
          ),
          h(
            "div",
            { class: "match-record-main" },
            h("h3", {}, m.matchTitle || m.opponentName || "Bingo match"),
            h(
              "p",
              { class: "muted small" },
              (owner ? "@" + owner.username : m.ownerUid) +
                " · " +
                stamp(m.timestamp),
            ),
            h("small", { class: "mono muted" }, m.id),
          ),
          h("span", { class: "badge" }, m.mode || "Mode not reported"),
          owner
            ? button("Inspect player", () => openPlayer(owner.username))
            : null,
        );
      }),
    );
    if (!filtered.length)
      $("match-records").append(
        empty("No recent saved results match these filters."),
      );
  }
  async function releases() {
    const root = $("release-content"),
      generation = state.generation;
    root.replaceChildren(
      h("div", { class: "loading" }, "Reading the actual update feed…"),
    );
    try {
      const result = await api("release");
      if (generation !== state.generation) return;
      const current = result.release;
      const form = h(
        "form",
        { class: "panel release-form" },
        h("p", { class: "eyebrow" }, "DISTRIBUTE AN UPDATE"),
        h("h2", {}, "Ship the next version"),
        h(
          "p",
          { class: "muted small" },
          "Use a compiled, signed APK. This publishes the in-app update through Pages and its fallback feed; existing GitHub Releases and source tags stay separate.",
        ),
      );
      const grid = h("div", { class: "form-grid" });
      const field = (label, name, value, type = "text") =>
        h(
          "label",
          {},
          label,
          h("input", {
            name,
            value,
            type,
            required: true,
            ...(type === "number" ? { min: 1, step: 1 } : {}),
          }),
        );
      grid.append(
        field("New version", "versionName", ""),
        field(
          "Build number",
          "versionCode",
          Number(current.versionCode) + 1,
          "number",
        ),
      );
      const file = h("input", {
        type: "file",
        accept: ".apk,application/vnd.android.package-archive",
        name: "apk",
      });
      const url = h("input", {
        name: "downloadUrl",
        type: "url",
        placeholder:
          "https://github.com/VamsiReddyBora/BINGO/releases/download/…",
      });
      const preview = h(
        "p",
        { class: "small muted" },
        "No APK selected · maximum 12 MiB",
      );
      file.addEventListener("change", () => {
        const f = file.files[0];
        preview.textContent = f
          ? f.name + " · " + (f.size / 1024 / 1024).toFixed(2) + " MiB"
          : "No APK selected · maximum 12 MiB";
        url.disabled = !!f;
      });
      const submit = h(
        "button",
        {
          class: "primary",
          type: "submit",
          disabled: !result.publishConfigured,
        },
        "Review & publish update",
      );
      form.append(
        grid,
        h(
          "label",
          { class: "upload-zone" },
          h("strong", {}, "↑ Select signed APK"),
          file,
          preview,
        ),
        h(
          "p",
          { class: "or-divider" },
          "OR USE AN EXISTING REPOSITORY DOWNLOAD",
        ),
        h("label", {}, "GitHub Release / Pages APK URL", url),
        h(
          "label",
          {},
          "Release notes",
          h("textarea", {
            name: "releaseNotes",
            rows: 5,
            maxLength: 8000,
            placeholder: "What changed for your players?",
          }),
        ),
        h(
          "label",
          {},
          "Optional SHA-256 verification",
          h("input", {
            name: "sha256",
            maxLength: 64,
            placeholder:
              "The server calculates the file checksum before publishing.",
          }),
        ),
        h(
          "div",
          { class: "notice" },
          "Confirm that the APK’s embedded version/build and signing key are correct. Uploading a file does not compile the application.",
        ),
        submit,
      );
      const live = h(
        "aside",
        { class: "panel release-live" },
        h(
          "div",
          { class: "release-orbit", "aria-hidden": "true" },
          h("span", {}, "↗"),
        ),
        h("p", { class: "eyebrow" }, "CURRENT UPDATE"),
        h("h2", {}, "v" + current.versionName),
        h("p", { class: "build-label" }, "BUILD " + current.versionCode),
        h(
          "span",
          {
            class:
              "badge " +
              (result.deployment?.status === "built" ? "online" : "lobby"),
          },
          result.deployment?.status === "built"
            ? "Pages deployment complete"
            : result.deployment
              ? "Deployment " + result.deployment.status
              : "Feed source verified",
        ),
        h("hr"),
        h("p", { class: "small muted" }, "Source: " + result.source),
        h("p", { class: "small" }, current.releaseNotes || "No release notes"),
        h(
          "a",
          {
            href: current.downloadUrl,
            target: "_blank",
            rel: "noopener noreferrer",
            class: "download-link",
          },
          "Open current APK ↗",
        ),
        h(
          "small",
          { class: "mono muted checksum" },
          current.sha256 || "Checksum not reported",
        ),
      );
      root.replaceChildren(h("div", { class: "release-grid" }, form, live));
      if (!result.publishConfigured)
        form.append(
          h(
            "p",
            { class: "error" },
            "Publishing is unavailable until the server credential is configured.",
          ),
        );
      form.addEventListener("submit", async (event) => {
        event.preventDefault();
        if (submit.disabled) return;
        const values = new FormData(form),
          f = file.files[0],
          input = {
            versionName: String(values.get("versionName")).trim(),
            versionCode: Number(values.get("versionCode")),
            downloadUrl: url.value.trim(),
            releaseNotes: values.get("releaseNotes"),
            sha256: String(values.get("sha256") || "").trim(),
            expectedVersionCode: current.versionCode,
          };
        if (!f && !input.downloadUrl) {
          toast("Select an APK or provide its download URL.", "error");
          return;
        }
        if (f && f.size > 12 * 1024 * 1024) {
          toast("Select an APK smaller than 12 MiB.", "error");
          return;
        }
        if (
          !(await confirmAction(
            "Publish in-app update",
            `Offer ${input.versionName} (build ${input.versionCode}) to your players? Verify the APK version and signing key before approval.`,
          ))
        )
          return;
        submit.disabled = true;
        submit.textContent = "Verifying & publishing…";
        try {
          if (f)
            input.apkBase64 = await new Promise((resolve, reject) => {
              const reader = new FileReader();
              reader.onload = () =>
                resolve(String(reader.result).split(",")[1]);
              reader.onerror = () =>
                reject(new Error("Could not read the APK"));
              reader.readAsDataURL(f);
            });
          if (generation !== state.generation) return;
          const answer = await api("release", { method: "PUT", data: input });
          if (generation !== state.generation) return;
          toast(
            answer.operation?.status === "SYNCED"
              ? "Update committed. Pages deployment is starting; track it in Audit & sync jobs."
              : "Publication saved; an external step is pending. Check Audit & sync jobs.",
            answer.operation?.status === "SYNCED" ? "success" : "warning",
          );
          await releases();
        } catch (e) {
          if (generation === state.generation) toast(e.message, "error");
        } finally {
          submit.disabled = false;
          submit.textContent = "Review & publish update";
        }
      });
    } catch (e) {
      if (generation === state.generation)
        root.replaceChildren(
          h(
            "div",
            { class: "notice warning" },
            "No live version can be verified. " + e.message,
          ),
        );
    }
  }
  function clear() {
    matchRows = [];
    matchSequence++;
  }
  return { overview, rooms, matches, drawMatches, releases, clear };
}
