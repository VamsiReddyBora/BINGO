import {
  API_BASE,
  statusText,
  statusClass,
  activeStatus,
  selectPlayers,
  playersCsv,
  photoSource,
  matchFilter,
} from "./model.js";

const $ = (id) => document.getElementById(id);
const state = {
  session: null,
  snapshot: null,
  stale: false,
  page: 1,
  view: innerWidth < 700 ? "cards" : "table",
  viewExplicit: false,
  tab: "players",
  timer: null,
  fetching: false,
  generation: 0,
  selected: null,
  inspection: 0,
  section: "overview",
  detail: null,
  readController: null,
  controllers: new Set(),
  matchPage: 1,
  matchQuery: "",
  matchOutcome: "ALL",
};
const tabs = [
  "players",
  "rooms",
  "announcements",
  "release",
  "activity",
  "settings",
];
const h = (tag, attrs = {}, ...children) => {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(attrs)) {
    if (value === undefined || value === null) continue;
    if (key === "class") node.className = value;
    else if (key.startsWith("on"))
      node.addEventListener(key.slice(2).toLowerCase(), value);
    else if (key === "text") node.textContent = String(value);
    else if (
      [
        "value",
        "checked",
        "disabled",
        "hidden",
        "type",
        "name",
        "placeholder",
        "required",
        "min",
        "max",
        "minLength",
        "maxLength",
        "accept",
        "multiple",
      ].includes(key)
    )
      node[key] = value;
    else node.setAttribute(key, String(value));
  }
  for (const child of children.flat(Infinity))
    if (child !== null && child !== undefined)
      node.append(
        child instanceof Node ? child : document.createTextNode(String(child)),
      );
  return node;
};
const num = (value) =>
  typeof value === "number" && Number.isFinite(value)
    ? value.toLocaleString()
    : "—";
const stamp = (value) =>
  value
    ? new Date(value).toLocaleString(undefined, {
        dateStyle: "medium",
        timeStyle: "short",
      })
    : "Not observed";
const age = (value) => {
  if (!value) return "No observation";
  const n = Math.max(0, Math.floor((Date.now() - value) / 1000));
  return n < 60
    ? `${n}s ago`
    : n < 3600
      ? `${Math.floor(n / 60)}m ago`
      : `${Math.floor(n / 3600)}h ago`;
};
const button = (text, onClick, classes = "", attrs = {}) =>
  h("button", { type: "button", class: classes, onClick, ...attrs }, text);
const badge = (status, label) =>
  h(
    "span",
    { class: "badge " + (statusClass[status] || "unknown") },
    "● ",
    label || statusText[status] || "Unknown",
  );
function toast(message, type = "info") {
  const node = h("div", { class: "toast " + type }, message);
  $("toasts").append(node);
  setTimeout(() => node.remove(), 6000);
  while ($("toasts").childElementCount > 4) $("toasts").firstChild.remove();
}
function avatar(profile = {}, large = false) {
  const box = h("span", { class: "avatar" + (large ? " large" : "") });
  const initials = () =>
    box.replaceChildren(
      document.createTextNode(
        String(profile.displayName || profile.username || "?")
          .slice(0, 2)
          .toUpperCase(),
      ),
    );
  const source = photoSource(profile.avatarBase64 || profile.avatarUrl);
  if (source) {
    const image = h("img", { src: source, alt: "", loading: "lazy" });
    image.addEventListener("error", initials, { once: true });
    box.append(image);
  } else initials();
  return box;
}
function identity(row) {
  const p = row.profile || row;
  return h(
    "div",
    { class: "identity" },
    avatar(p),
    h(
      "div",
      {},
      h("strong", {}, p.displayName || row.username),
      h("small", {}, "@" + row.username),
    ),
  );
}
function clearSession() {
  state.generation++;
  state.session = null;
  state.snapshot = null;
  state.detail = null;
  state.selected = null;
  state.fetching = false;
  state.inspection++;
  for (const controller of state.controllers) controller.abort();
  state.controllers.clear();
  clearTimeout(state.timer);
  state.timer = null;
  sessionStorage.removeItem("bingo_admin_session_v3");
  for (const dialog of document.querySelectorAll("dialog[open]"))
    dialog.close();
  $("console").hidden = true;
  $("login").hidden = false;
  $("password").value = "";
  $("player-list").replaceChildren();
  $("inspector-body").replaceChildren();
  $("toasts").replaceChildren();
}
async function api(
  path,
  { method = "GET", data, signal, auth = true, operationId } = {},
) {
  if (auth && !state.session) throw new Error("Sign in to continue");
  const controller = new AbortController();
  state.controllers.add(controller);
  if (signal) {
    if (signal.aborted) controller.abort();
    else
      signal.addEventListener("abort", () => controller.abort(), {
        once: true,
      });
  }
  const timeout = setTimeout(() => controller.abort(), 45000);
  const headers = { "Content-Type": "application/json" };
  if (auth) {
    if (!state.session) throw new Error("Sign in to continue");
    headers.Authorization = "Bearer " + state.session.token;
  }
  if (method !== "GET")
    headers["Idempotency-Key"] = operationId || crypto.randomUUID();
  try {
    const res = await fetch(API_BASE + "/api/" + path, {
      method,
      headers,
      body: data === undefined ? undefined : JSON.stringify(data),
      signal: controller.signal,
      cache: "no-store",
    });
    const result = await res.json();
    if (res.status === 401 && auth) {
      clearSession();
      throw new Error("Your admin session expired. Sign in again.");
    }
    if (!res.ok) {
      const error = new Error(
        result.error || "Admin service could not complete the request",
      );
      error.status = res.status;
      throw error;
    }
    return result;
  } finally {
    clearTimeout(timeout);
    state.controllers.delete(controller);
  }
}
async function lock() {
  const session = state.session;
  clearSession();
  if (session)
    await fetch(API_BASE + "/api/logout", {
      method: "POST",
      headers: {
        Authorization: "Bearer " + session.token,
        "Content-Type": "application/json",
      },
      body: "{}",
    }).catch(() => {});
}
function filters() {
  return {
    query: $("search").value,
    status: $("status-filter").value,
    provider: $("provider-filter").value,
    sort: $("sort").value,
  };
}
function rows() {
  return (state.snapshot?.players || []).map((row) =>
    state.stale
      ? {
          ...row,
          presence: {
            ...row.presence,
            status: "UNKNOWN",
            detail: row.presence?.detail
              ? "Last verified activity: " + row.presence.detail
              : "",
          },
        }
      : row,
  );
}
function filtered() {
  return selectPlayers(rows(), filters());
}
function warning(text) {
  $("data-warning").hidden = !text;
  $("data-warning").textContent = text || "";
}
function schedule() {
  clearTimeout(state.timer);
  const delay = Number($("refresh-interval").value);
  if (state.session && delay && document.visibilityState === "visible")
    state.timer = setTimeout(refresh, delay);
}
async function refresh() {
  if (
    !state.session ||
    state.fetching ||
    document.visibilityState === "hidden"
  ) {
    schedule();
    return;
  }
  state.fetching = true;
  $("refresh").disabled = true;
  const generation = state.generation;
  try {
    const snapshot = await api("players?fresh=1");
    if (generation !== state.generation) return;
    state.snapshot = snapshot;
    state.stale = false;
    const incomplete = snapshot.players.filter((p) =>
      ["STALE", "PARTIAL", "UNAVAILABLE", "REGISTRY"].includes(p.sourceStatus),
    ).length;
    warning(
      !snapshot.presenceConnected
        ? "Live presence is unavailable. Player connectivity is shown as unknown."
        : incomplete
          ? `${incomplete} profile(s) have older, registry-only or unavailable backup data. Open a player for a fresh inspection. No missing statistics are replaced with zero.`
          : "",
    );
    $("connection").textContent = snapshot.presenceConnected
      ? "● Presence connected"
      : "● Presence unavailable";
    $("connection").className =
      "connection " + (snapshot.presenceConnected ? "live" : "stale");
    $("last-sync").textContent =
      "Directory verified " +
      stamp(snapshot.fetchedAt) +
      " · " +
      snapshot.total +
      " registered accounts";
    $("nav-count").textContent = snapshot.total;
    renderDirectory();
    renderRooms();
    if (state.selected && state.detail) {
      const updated = snapshot.players.find(
        (p) => p.username === state.selected,
      );
      if (!updated) {
        $("inspector").close();
        toast("The selected player is no longer in the directory.", "warning");
      } else if (updated.etag && updated.etag !== state.detail.etag) {
        $("inspector-warning").hidden = false;
        $("inspector-warning").textContent =
          "New account data is available. Refresh this inspection before making another change.";
      }
    }
  } catch (error) {
    if (generation !== state.generation) return;
    state.stale = true;
    warning(
      state.snapshot
        ? "Refresh failed. The directory below is the last verified snapshot; current presence is unknown. " +
            error.message
        : "No verified directory is available. " + error.message,
    );
    $("connection").textContent = "● Data unavailable";
    $("connection").className = "connection stale";
    renderDirectory();
  } finally {
    if (generation === state.generation) {
      state.fetching = false;
      $("refresh").disabled = false;
      schedule();
    }
  }
}
function renderDirectory() {
  const list = rows(),
    snapshot = state.snapshot;
  $("statistics").replaceChildren(
    ...[
      ["Registered players", snapshot ? list.length : null],
      [
        "Active in app",
        snapshot
          ? list.filter((p) => activeStatus.has(p.presence?.status)).length
          : null,
      ],
      [
        "Playing",
        snapshot
          ? list.filter((p) => p.presence?.status === "PLAYING").length
          : null,
      ],
      [
        "In lobby",
        snapshot
          ? list.filter((p) => p.presence?.status === "IN_LOBBY").length
          : null,
      ],
      [
        "Unknown status",
        snapshot
          ? list.filter((p) => p.presence?.status === "UNKNOWN").length
          : null,
      ],
    ].map(([label, value]) =>
      h(
        "div",
        { class: "stat" },
        h("span", {}, label),
        h("strong", {}, num(value)),
      ),
    ),
  );
  const result = filtered(),
    size = Number($("page-size").value),
    totalPages = Math.max(1, Math.ceil(result.length / size));
  state.page = Math.min(state.page, totalPages);
  const visible = result.slice((state.page - 1) * size, state.page * size);
  $("result-count").textContent =
    `${result.length} matching ${result.length === 1 ? "player" : "players"}${snapshot ? " · " + list.length + " in directory" : ""}`;
  $("export").textContent = "↓ Export " + result.length + " filtered";
  $("export").disabled = !result.length;
  const container = $("player-list");
  if (!snapshot) {
    container.replaceChildren(
      h(
        "div",
        { class: "empty" },
        "No verified snapshot loaded. Refresh to connect.",
      ),
    );
    $("pagination").replaceChildren();
    return;
  }
  if (!visible.length)
    container.replaceChildren(
      h("div", { class: "empty" }, "No players match these filters."),
    );
  else if (state.view === "table") {
    const table = h(
      "table",
      {},
      h(
        "thead",
        {},
        h(
          "tr",
          {},
          ...[
            "Player",
            "Live status",
            "Level",
            "Games",
            "Wins",
            "Streak",
            "Version",
            "Observed",
            "",
          ].map((text) => h("th", { scope: "col" }, text)),
        ),
      ),
    );
    const body = h("tbody");
    for (const row of visible) {
      const p = row.profile,
        s = row.presence?.status || "UNKNOWN";
      body.append(
        h(
          "tr",
          {},
          h("td", {}, identity(row)),
          h(
            "td",
            {},
            badge(s),
            row.presence?.detail
              ? h("div", { class: "muted small" }, row.presence.detail)
              : null,
          ),
          h("td", {}, num(p.level)),
          h("td", {}, num(p.gamesPlayed)),
          h("td", {}, num(p.gamesWon)),
          h("td", {}, num(p.currentStreak)),
          h(
            "td",
            { class: "muted small" },
            row.presence?.appVersion || p.appVersion || "Not reported",
          ),
          h(
            "td",
            { class: "muted small", title: stamp(row.presence?.observedAt) },
            age(row.presence?.observedAt),
          ),
          h(
            "td",
            {},
            button("Inspect →", () => openPlayer(row.username), "", {
              "aria-label": "Inspect @" + row.username,
            }),
          ),
        ),
      );
    }
    table.append(body);
    container.replaceChildren(h("div", { class: "table-wrap" }, table));
  } else {
    container.replaceChildren(
      h(
        "div",
        { class: "cards" },
        ...visible.map((row) => {
          const p = row.profile,
            s = row.presence?.status || "UNKNOWN";
          return h(
            "article",
            { class: "player-card" },
            h("div", { class: "card-top" }, identity(row), badge(s)),
            h(
              "div",
              { class: "mini-stats" },
              ...[
                ["Level", p.level],
                ["Games", p.gamesPlayed],
                ["Wins", p.gamesWon],
              ].map(([k, v]) =>
                h("div", {}, h("span", {}, k), h("strong", {}, num(v))),
              ),
            ),
            h(
              "p",
              { class: "muted small" },
              row.presence?.detail ||
                "Status observed " + age(row.presence?.observedAt),
            ),
            h(
              "div",
              { class: "card-bottom" },
              h(
                "span",
                { class: "small muted" },
                row.presence?.appVersion || p.appVersion || "Version unknown",
              ),
              button("Inspect →", () => openPlayer(row.username), "", {
                "aria-label": "Inspect @" + row.username,
              }),
            ),
          );
        }),
      ),
    );
  }
  $("pagination").replaceChildren(
    button(
      "← Previous",
      () => {
        state.page--;
        renderDirectory();
      },
      "",
      { disabled: state.page <= 1 },
    ),
    h("span", {}, `Page ${state.page} of ${totalPages}`),
    button(
      "Next →",
      () => {
        state.page++;
        renderDirectory();
      },
      "",
      { disabled: state.page >= totalPages },
    ),
  );
  $("table-view").setAttribute("aria-pressed", String(state.view === "table"));
  $("card-view").setAttribute("aria-pressed", String(state.view === "cards"));
}
function saveFile(name, type, text) {
  const url = URL.createObjectURL(new Blob([text], { type }));
  const a = h("a", { href: url, download: name });
  document.body.append(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
async function switchTab(tab) {
  if (!tabs.includes(tab)) tab = "players";
  state.tab = tab;
  for (const name of tabs) $("tab-" + name).hidden = name !== tab;
  for (const b of $("navigation").querySelectorAll("button")) {
    b.classList.toggle("active", b.dataset.tab === tab);
    b.setAttribute("aria-current", String(b.dataset.tab === tab));
  }
  history.replaceState(null, "", "#" + tab);
  if (tab === "activity") await renderActivity();
  if (tab === "announcements") await renderAnnouncements();
  if (tab === "release") await renderRelease();
}
async function openPlayer(name) {
  state.readController?.abort();
  state.readController = new AbortController();
  state.selected = name;
  state.detail = null;
  state.section = "overview";
  state.matchPage = 1;
  state.matchQuery = "";
  state.matchOutcome = "ALL";
  const sequence = ++state.inspection;
  $("inspector-heading").replaceChildren(
    h("h2", { id: "inspector-title" }, "@" + name),
    h("p", { class: "small muted" }, "Loading verified player data…"),
  );
  $("inspector-body").replaceChildren(
    h("div", { class: "loading" }, "Loading profile, matches and friends…"),
  );
  $("inspector-warning").hidden = true;
  if (!$("inspector").open) $("inspector").showModal();
  try {
    const data = await api("players/" + encodeURIComponent(name), {
      signal: state.readController.signal,
    });
    if (
      sequence !== state.inspection ||
      state.selected !== name ||
      !state.session
    )
      return;
    state.detail = data;
    renderInspection();
  } catch (error) {
    if (sequence !== state.inspection || state.selected !== name) return;
    $("inspector-body").replaceChildren(
      h(
        "div",
        { class: "notice warning" },
        error.name === "AbortError" ? "Inspection cancelled." : error.message,
      ),
      button("Try again", () => openPlayer(name)),
    );
  }
}
function renderInspection() {
  const data = state.detail;
  if (!data) return;
  const p = data.profile,
    row = rows().find((r) => r.username === data.username);
  $("inspector-heading").replaceChildren(
    h(
      "div",
      { class: "identity" },
      avatar(p),
      h(
        "div",
        {},
        h("h2", { id: "inspector-title" }, p.displayName || data.username),
        h(
          "p",
          { class: "small muted" },
          "@" +
            data.username +
            " · " +
            (statusText[row?.presence?.status] || "Unknown"),
        ),
      ),
    ),
  );
  for (const b of $("inspector-tabs").querySelectorAll("button")) {
    b.classList.toggle("active", b.dataset.section === state.section);
    b.setAttribute("aria-current", String(b.dataset.section === state.section));
  }
  $("inspector-warning").hidden = data.sourceApplied;
  $("inspector-warning").textContent =
    "This change is protected in admin control. The profile storage has not yet received this revision. Check the pending synchronization jobs.";
  if (data.historyPartial) {
    $("inspector-warning").hidden = false;
    $("inspector-warning").textContent =
      "The legacy backup is unavailable. Verified profile and history records are shown; additional backup-only records may be missing. Changes requiring that backup remain pending until it recovers.";
  }
  const body = $("inspector-body");
  body.replaceChildren();
  if (state.section === "overview") renderOverview(body, data);
  if (state.section === "matches") renderMatches(body, data);
  if (state.section === "friends") renderFriends(body, data);
  if (state.section === "danger") renderDanger(body, data);
}
function field(
  label,
  name,
  value,
  { type = "text", min, max, required = true } = {},
) {
  const input = h("input", {
    name,
    value: value ?? "",
    type,
    min,
    max,
    required,
    maxLength: type === "text" ? 80 : undefined,
  });
  return h("label", {}, label, input);
}
function meta(label, value) {
  return h(
    "p",
    {},
    h("span", {}, label),
    h("strong", { class: "small break" }, value || "Not reported"),
  );
}
function renderOverview(body, data) {
  const p = data.profile;
  body.append(
    h(
      "div",
      { class: "profile-grid" },
      ...[
        ["Games played", p.gamesPlayed],
        ["Wins", p.gamesWon],
        [
          "Win rate",
          p.gamesPlayed
            ? Math.round((p.gamesWon / p.gamesPlayed) * 100) + "%"
            : "—",
        ],
        ["Current streak", p.currentStreak],
        ["Best streak", p.bestStreak],
        ["Level", p.level],
      ].map(([k, v]) =>
        h(
          "div",
          { class: "stat" },
          h("span", {}, k),
          h("strong", {}, typeof v === "number" ? num(v) : v),
        ),
      ),
    ),
  );
  const photos = h("section", { class: "panel" }, h("h2", {}, "Profile photo"));
  const upload = h("input", {
    type: "file",
    accept: "image/jpeg,image/png,image/webp",
    "aria-label": "Upload player profile photo",
  });
  upload.addEventListener("change", async () => {
    const file = upload.files?.[0];
    if (!file) return;
    try {
      const photo = await preparePhoto(file);
      if (
        await confirmAction(
          "Replace profile photo",
          "Upload this photo for @" + data.username + "?",
        )
      )
        await change(data, "/avatar", "PUT", { photo });
    } catch (error) {
      toast(error.message, "error");
    } finally {
      upload.value = "";
    }
  });
  photos.append(
    h(
      "div",
      { class: "photo-actions" },
      avatar(p, true),
      h(
        "div",
        { class: "stack" },
        h("label", { class: "upload-button" }, "↑ Upload photo", upload),
        button(
          "Remove photo",
          async () => {
            if (
              await confirmAction(
                "Remove profile photo",
                "Remove the current profile photo for @" + data.username + "?",
              )
            )
              await change(data, "/avatar", "PUT", { photo: null });
          },
          "danger",
        ),
      ),
    ),
    h(
      "p",
      { class: "muted small" },
      "JPEG, PNG or WebP. Photos are resized before upload.",
    ),
  );
  body.append(photos);
  const edit = h(
    "form",
    { class: "panel" },
    h("h2", {}, "Profile & statistics"),
  );
  const grid = h(
    "div",
    { class: "profile-form" },
    field("Display name", "displayName", p.displayName),
    field("Level", "level", p.level, {
      type: "number",
      min: 1,
      max: 10000,
      required: false,
    }),
    ...["gamesPlayed", "gamesWon", "currentStreak", "bestStreak", "xp"].map(
      (key) =>
        field(
          {
            gamesPlayed: "Games played",
            gamesWon: "Wins",
            currentStreak: "Current streak",
            bestStreak: "Best streak",
            xp: "Experience",
          }[key],
          key,
          p[key] ?? "",
          { type: "number", min: 0, max: 100000000, required: false },
        ),
    ),
  );
  edit.append(
    grid,
    h(
      "div",
      { class: "form-actions" },
      h("button", { type: "submit", class: "primary" }, "Save profile & stats"),
    ),
  );
  edit.addEventListener("submit", async (event) => {
    event.preventDefault();
    const fields = new FormData(edit),
      patch = {};
    for (const [key, value] of fields)
      if (value !== "")
        patch[key] = key === "displayName" ? value.trim() : Number(value);
    if (patch.gamesWon > patch.gamesPlayed) {
      toast("Wins cannot exceed games played.", "error");
      return;
    }
    if (patch.currentStreak > patch.bestStreak) {
      toast("Current streak cannot exceed best streak.", "error");
      return;
    }
    const diff = Object.fromEntries(
      Object.entries(patch).filter(([key, v]) => v !== p[key]),
    );
    if (!Object.keys(diff).length) {
      toast("There are no changes to save.");
      return;
    }
    if (
      await confirmAction(
        "Update player profile",
        "Save these profile/statistics changes for @" + data.username + "?",
      )
    )
      await change(data, "", "PATCH", { patch: diff });
  });
  body.append(edit);
  body.append(
    h(
      "section",
      { class: "panel" },
      h("h2", {}, "Account metadata"),
      h(
        "div",
        { class: "metadata" },
        meta("User ID", p.uid),
        meta("Username", "@" + data.username),
        meta("Email", p.email),
        meta("Account provider", p.provider),
        meta("App version", p.appVersion),
        meta("Admin revision", String(data.revision)),
        meta("Last fresh inspection", stamp(data.fetchedAt)),
      ),
    ),
  );
}
function renderMatches(body, data) {
  const all = data.matches || [];
  const controls = h("div", { class: "match-controls" });
  const query = h("input", {
    type: "search",
    value: state.matchQuery,
    placeholder: "Search opponent, mode or match ID…",
    "aria-label": "Search matches",
  });
  const outcome = h(
    "select",
    { "aria-label": "Match outcome" },
    ...["ALL", "WIN", "LOSS", "DRAW"].map((x) =>
      h("option", { value: x }, x === "ALL" ? "All results" : x),
    ),
  );
  outcome.value = state.matchOutcome;
  const list = h("div", { class: "panel record-list" }),
    pager = h("div", { class: "pagination" });
  const draw = () => {
    const selected = matchFilter(all, {
        query: state.matchQuery,
        outcome: state.matchOutcome,
      }),
      pages = Math.max(1, Math.ceil(selected.length / 20));
    state.matchPage = Math.min(state.matchPage, pages);
    list.replaceChildren(
      h(
        "p",
        { class: "muted small" },
        `${selected.length} matching records · ${all.length} retained records`,
      ),
    );
    for (const m of selected.slice(
      (state.matchPage - 1) * 20,
      state.matchPage * 20,
    ))
      list.append(
        h(
          "article",
          { class: "match-row" },
          h(
            "div",
            {},
            h("strong", {}, m.opponentName || "Opponent"),
            h(
              "span",
              {
                class:
                  "badge " +
                  (m.didWin ? "online" : m.isDraw ? "lobby" : "offline"),
              },
              m.didWin ? "WON" : m.isDraw ? "DRAW" : "LOST",
            ),
            h("p", { class: "muted small" }, stamp(m.timestamp)),
            h(
              "p",
              { class: "small muted break" },
              (m.mode || "Online") + " · " + m.id,
            ),
          ),
          button(
            "Delete",
            async () => {
              if (
                await confirmAction(
                  "Delete match record",
                  "Remove match " +
                    m.id +
                    " from @" +
                    data.username +
                    "? Lifetime counters will remain unchanged.",
                )
              )
                await change(
                  data,
                  "/matches/" + encodeURIComponent(m.id),
                  "DELETE",
                  {},
                );
            },
            "danger",
            { "aria-label": "Delete match " + m.id },
          ),
        ),
      );
    if (!selected.length)
      list.append(h("p", { class: "muted" }, "No matching match records."));
    pager.replaceChildren(
      button(
        "←",
        () => {
          state.matchPage--;
          draw();
        },
        "",
        { disabled: state.matchPage === 1 },
      ),
      h("span", {}, `Page ${state.matchPage} of ${pages}`),
      button(
        "→",
        () => {
          state.matchPage++;
          draw();
        },
        "",
        { disabled: state.matchPage === pages },
      ),
    );
  };
  query.addEventListener("input", () => {
    state.matchQuery = query.value;
    state.matchPage = 1;
    draw();
  });
  outcome.addEventListener("change", () => {
    state.matchOutcome = outcome.value;
    state.matchPage = 1;
    draw();
  });
  controls.append(query, outcome);
  body.append(
    h(
      "p",
      { class: "muted small" },
      "Retained match logs and lifetime game counters are separate. Deleting a log does not rewrite the accepted game result.",
    ),
    controls,
    list,
    pager,
  );
  draw();
}
function renderFriends(body, data) {
  const current = new Set((data.friends || []).map((f) => f.username));
  const candidates = (state.snapshot?.players || []).filter(
    (p) =>
      p.username !== data.username && !current.has(p.username) && p.profile.uid,
  );
  const select = h(
    "select",
    { "aria-label": "Choose player to add as friend" },
    h("option", { value: "" }, "Choose a registered player…"),
    ...candidates.map((p) =>
      h(
        "option",
        { value: p.username },
        "@" + p.username + " · " + (p.profile.displayName || ""),
      ),
    ),
  );
  const add = button(
    "Add to both friends lists",
    async () => {
      if (!select.value) return;
      const target = select.value;
      if (
        !(await confirmAction(
          "Add friendship",
          "Add @" +
            target +
            " and @" +
            data.username +
            " to each other’s friends lists?",
        ))
      )
        return;
      try {
        const other = await api("players/" + encodeURIComponent(target));
        await change(data, "/friends/" + encodeURIComponent(target), "PUT", {
          otherRevision: other.revision,
          otherEtag: other.etag,
        });
      } catch (e) {
        toast(e.message, "error");
      }
    },
    "primary",
    { disabled: !candidates.length },
  );
  body.append(
    h(
      "section",
      { class: "panel stack" },
      h("h2", {}, "Add a friend"),
      select,
      add,
    ),
  );
  const list = h(
    "section",
    { class: "panel" },
    h("h2", {}, `${data.friends?.length || 0} friends`),
  );
  for (const f of data.friends || [])
    list.append(
      h(
        "div",
        { class: "friend-row" },
        h(
          "div",
          {},
          identity({ username: f.username, profile: f }),
          h("p", { class: "small muted" }, f.uid || ""),
        ),
        h(
          "div",
          { class: "inline" },
          button("Inspect", () => openPlayer(f.username)),
          button(
            "Remove",
            async () => {
              if (
                !(await confirmAction(
                  "Remove friendship",
                  "Remove @" +
                    f.username +
                    " and @" +
                    data.username +
                    " from each other’s friends lists?",
                ))
              )
                return;
              try {
                const other = await api(
                  "players/" + encodeURIComponent(f.username),
                );
                await change(
                  data,
                  "/friends/" + encodeURIComponent(f.username),
                  "DELETE",
                  { otherRevision: other.revision, otherEtag: other.etag },
                );
              } catch (e) {
                toast(e.message, "error");
              }
            },
            "danger",
          ),
        ),
      ),
    );
  if (!data.friends?.length)
    list.append(h("p", { class: "muted" }, "No active friendships."));
  body.append(list);
}
function renderDanger(body, data) {
  body.append(
    h(
      "section",
      { class: "panel danger-panel" },
      h("h2", {}, "Reset statistics"),
      h(
        "p",
        { class: "muted" },
        "Set games, wins, streaks, level and experience to their initial values. Match logs remain available.",
      ),
      button(
        "Reset statistics",
        async () => {
          if (
            await confirmAction(
              "Reset all statistics",
              "Reset statistics for @" +
                data.username +
                "? Future matches can still build new statistics.",
              data.username,
            )
          )
            await change(data, "", "PATCH", {
              patch: {
                gamesPlayed: 0,
                gamesWon: 0,
                currentStreak: 0,
                bestStreak: 0,
                level: 1,
                xp: 0,
              },
            });
        },
        "danger",
      ),
    ),
    h(
      "section",
      { class: "panel danger-panel" },
      h("h2", {}, "Clear retained match history"),
      h(
        "p",
        { class: "muted" },
        "Remove existing cloud match logs. A removal marker prevents older backups from restoring those records.",
      ),
      button(
        "Clear match history",
        async () => {
          if (
            await confirmAction(
              "Clear all retained matches",
              "Clear all existing match logs for @" + data.username + "?",
              data.username,
            )
          )
            await change(data, "/matches", "DELETE", {
              confirm: data.username,
            });
        },
        "danger",
      ),
    ),
    h(
      "section",
      { class: "panel danger-panel" },
      h("h2", {}, "Remove player account"),
      h(
        "p",
        { class: "muted" },
        "Remove this account from the active directory, clean up linked records, and permanently block stale backups from restoring it. This cannot be undone from this console.",
      ),
      button(
        "Remove @" + data.username,
        async () => {
          if (
            await confirmAction(
              "Permanently remove player",
              "Remove @" +
                data.username +
                " and their linked profile/history? A minimal deletion marker remains to block resurrection.",
              data.username,
            )
          )
            await change(data, "", "DELETE", { confirm: data.username });
        },
        "danger",
      ),
    ),
  );
}
async function change(data, suffix, method, payload) {
  const name = data.username,
    sequence = state.inspection;
  const buttons = [...$("inspector").querySelectorAll("button,input,select")];
  buttons.forEach((b) => (b.disabled = true));
  try {
    const result = await api("players/" + encodeURIComponent(name) + suffix, {
      method,
      data: { revision: data.revision, etag: data.etag, ...payload },
    });
    toast(
      result.message || "Operation completed",
      result.operation?.status === "PENDING" ? "warning" : "success",
    );
    if (method === "DELETE" && !suffix) {
      if (state.selected === name) $("inspector").close();
    } else if (state.selected === name && state.inspection === sequence) {
      const section = state.section;
      await openPlayer(name);
      state.section = section;
      if (state.detail) renderInspection();
    }
    await refresh();
  } catch (error) {
    toast(error.message, "error");
    if (error.status === 409 && state.selected === name) {
      $("inspector-warning").hidden = false;
      $("inspector-warning").textContent = error.message;
    }
  } finally {
    buttons.forEach((b) => (b.disabled = false));
  }
}
let confirmResolver = null,
  confirmTyped = "";
function confirmAction(title, description, typed = "") {
  if (confirmResolver) return Promise.resolve(false);
  $("confirmation-title").textContent = title;
  $("confirmation-description").textContent = description;
  $("confirmation-label").hidden = !typed;
  $("confirmation-input").value = "";
  $("confirmation-error").textContent = "";
  confirmTyped = typed;
  $("confirmation").showModal();
  if (typed) $("confirmation-input").focus();
  else $("confirmation-cancel").focus();
  return new Promise((resolve) => {
    confirmResolver = resolve;
  });
}
function resolveConfirmation(value) {
  const resolve = confirmResolver;
  confirmResolver = null;
  $("confirmation").close();
  resolve?.(value);
}
async function preparePhoto(file) {
  if (
    !["image/jpeg", "image/png", "image/webp"].includes(file.type) ||
    file.size > 8 * 1024 * 1024
  )
    throw new Error("Choose a JPEG, PNG or WebP image smaller than 8 MB.");
  const image = await createImageBitmap(file);
  try {
    if (image.width > 15000 || image.height > 15000)
      throw new Error("Photo dimensions are too large.");
    const scale = Math.min(1, 512 / Math.max(image.width, image.height));
    const canvas = document.createElement("canvas");
    canvas.width = Math.max(1, Math.round(image.width * scale));
    canvas.height = Math.max(1, Math.round(image.height * scale));
    canvas.getContext("2d").drawImage(image, 0, 0, canvas.width, canvas.height);
    for (const quality of [0.85, 0.65, 0.45, 0.25]) {
      const data = canvas.toDataURL("image/jpeg", quality);
      if (data.length <= 87000) return data;
    }
    throw new Error(
      "Photo remains too large after compression; choose a smaller image.",
    );
  } finally {
    image.close();
  }
}
function renderRooms() {
  const rooms = state.snapshot?.rooms || [];
  $("rooms").replaceChildren(
    ...rooms.map((room) =>
      h(
        "article",
        { class: "panel" },
        h("h2", {}, "#" + room.code),
        h(
          "p",
          {},
          badge(
            room.status === "PLAYING" ? "PLAYING" : "IN_LOBBY",
            room.status || "Waiting",
          ),
        ),
        h(
          "p",
          { class: "muted" },
          room.hostDisplayName ||
            room.hostUsername ||
            room.hostId ||
            "Host not reported",
        ),
        h(
          "p",
          { class: "small muted" },
          `${room.players?.length || 0} listed players · ${room.boardSize || "?"} × ${room.boardSize || "?"}`,
        ),
        h(
          "p",
          { class: "small muted" },
          "Metadata " +
            age(room.lastHeartbeat || room.timestamp || room.createdAt) +
            (room.sourceStatus === "STALE" ? " · stale observation" : ""),
        ),
        button(
          "Close lobby",
          async () => {
            if (
              !(await confirmAction(
                "Close lobby",
                "Close the room listing for #" + room.code + "?",
              ))
            )
              return;
            try {
              await api("rooms/" + encodeURIComponent(room.code) + "/close", {
                method: "POST",
                data: {},
              });
              toast("Lobby closure acknowledged.", "success");
              await refresh();
            } catch (e) {
              toast(e.message, "error");
            }
          },
          "danger",
          {
            disabled: room.status === "PLAYING",
            title:
              room.status === "PLAYING"
                ? "Active matches are not treated as expired lobbies"
                : "Close this lobby",
          },
        ),
      ),
    ),
  );
  if (!rooms.length)
    $("rooms").append(
      h(
        "div",
        { class: "empty" },
        state.snapshot?.presenceConnected
          ? "No room metadata was observed."
          : "Live room observations are unavailable.",
      ),
    );
}
async function renderActivity() {
  const generation = state.generation;
  $("activity-content").replaceChildren(
    h(
      "div",
      { class: "loading" },
      "Loading audit trail and synchronization jobs…",
    ),
  );
  try {
    const [audit, jobs] = await Promise.all([api("audit"), api("operations")]);
    if (generation !== state.generation) return;
    const panel = h(
      "section",
      { class: "panel" },
      h("h2", {}, "Recent synchronization jobs"),
    );
    for (const op of jobs.operations || [])
      panel.append(
        h(
          "div",
          { class: "job-row" },
          h(
            "div",
            {},
            h("strong", {}, op.type + " · " + op.target),
            h(
              "p",
              { class: "small muted" },
              stamp(op.createdAt) + " · attempt " + op.attempts,
            ),
            op.error ? h("p", { class: "small error" }, op.error) : null,
          ),
          op.status === "PENDING"
            ? button("Retry sync", async () => {
                try {
                  const result = await api("operations/" + op.id + "/retry", {
                    method: "POST",
                    data: {},
                  });
                  toast(
                    result.message,
                    result.operation?.status === "SYNCED"
                      ? "success"
                      : "warning",
                  );
                  renderActivity();
                } catch (e) {
                  toast(e.message, "error");
                }
              })
            : h("span", { class: "badge online" }, op.status),
        ),
      );
    if (!jobs.operations?.length)
      panel.append(
        h("p", { class: "muted" }, "No admin changes have been submitted."),
      );
    const log = h("section", { class: "panel" }, h("h2", {}, "Audit trail"));
    for (const entry of audit.entries || [])
      log.append(
        h(
          "details",
          {},
          h(
            "summary",
            {},
            `${stamp(entry.at)} · ${entry.operator} · ${entry.type} · ${entry.target}`,
          ),
          h(
            "pre",
            {},
            JSON.stringify(
              { before: entry.before, after: entry.after },
              null,
              2,
            ),
          ),
        ),
      );
    if (!audit.entries?.length)
      log.append(h("p", { class: "muted" }, "No recorded actions."));
    $("activity-content").replaceChildren(
      h("div", { class: "stack" }, panel, log),
    );
  } catch (e) {
    if (generation === state.generation)
      $("activity-content").replaceChildren(
        h("div", { class: "notice warning" }, e.message),
      );
  }
}
async function renderAnnouncements() {
  const root = $("announcement-content");
  const form = h(
    "form",
    { class: "panel settings" },
    h("h2", {}, "Compose an announcement"),
  );
  const target = h(
    "select",
    { name: "target" },
    h("option", { value: "ALL" }, "All players"),
    ...(state.snapshot?.players || []).map((p) =>
      h("option", { value: p.username }, "@" + p.username),
    ),
  );
  form.append(
    h("label", {}, "Audience", target),
    field("Title", "title", ""),
    h(
      "label",
      {},
      "Message",
      h("textarea", { name: "message", required: true, maxLength: 4000 }),
    ),
    h(
      "label",
      {},
      "Delivery mode",
      h(
        "select",
        { name: "deliveryMode" },
        h("option", { value: "STARTUP" }, "On app startup"),
        h("option", { value: "IN_GAME" }, "In game"),
        h("option", { value: "BOTH" }, "Startup and in game"),
      ),
    ),
    h(
      "label",
      {},
      "Type",
      h(
        "select",
        { name: "type" },
        ...["INFO", "WARNING", "UPDATE", "MAINTENANCE"].map((t) =>
          h("option", { value: t }, t),
        ),
      ),
    ),
    h(
      "div",
      { class: "form-actions" },
      h("button", { type: "submit", class: "primary" }, "Review & publish"),
    ),
  );
  form.addEventListener("submit", async (e) => {
    e.preventDefault();
    const input = Object.fromEntries(new FormData(form));
    if (
      !(await confirmAction(
        "Publish announcement",
        `Publish “${input.title}” to ${input.target === "ALL" ? "all players" : "@" + input.target}?`,
      ))
    )
      return;
    try {
      const result = await api("announcements", {
        method: "POST",
        data: input,
      });
      toast(
        result.brokerAcknowledged
          ? "Notice saved; broker acknowledged. Device delivery is not yet confirmed."
          : "Notice saved; live broadcast acknowledgement is pending.",
        result.brokerAcknowledged ? "success" : "warning",
      );
      renderAnnouncements();
    } catch (error) {
      toast(error.message, "error");
    }
  });
  const active = h(
    "div",
    { class: "panel" },
    h("h2", {}, "Stored announcements"),
  );
  root.replaceChildren(h("div", { class: "split" }, form, active));
  try {
    const result = await api("announcements");
    for (const n of result.announcements || [])
      active.append(
        h(
          "article",
          { class: "stack" },
          h("h3", {}, n.title || "Cleared announcement"),
          h(
            "p",
            { class: "small muted" },
            n.targetUsername +
              " · " +
              stamp(n.timestamp) +
              " · " +
              (n.active ? "Active" : "Cleared"),
          ),
          h("p", {}, n.message || ""),
          n.active
            ? button(
                "Clear notice",
                async () => {
                  if (
                    !(await confirmAction(
                      "Clear announcement",
                      "Clear this stored notice for " + n.targetUsername + "?",
                    ))
                  )
                    return;
                  try {
                    await api("announcements", {
                      method: "POST",
                      data: {
                        target:
                          n.targetUsername === "ALL"
                            ? "ALL"
                            : n.targetUsername.replace(/^@/, ""),
                        active: false,
                      },
                    });
                    toast("Notice cleared.", "success");
                    renderAnnouncements();
                  } catch (e) {
                    toast(e.message, "error");
                  }
                },
                "danger",
              )
            : null,
        ),
      );
    if (!result.announcements?.length)
      active.append(h("p", { class: "muted" }, "No stored notices."));
  } catch (e) {
    active.append(h("p", { class: "error" }, e.message));
  }
}
async function renderRelease() {
  const root = $("release-content");
  root.replaceChildren(
    h("div", { class: "loading" }, "Reading the actual release manifest…"),
  );
  const generation = state.generation;
  try {
    const result = await api("release");
    if (generation !== state.generation) return;
    const release = result.release;
    const form = h(
      "form",
      { class: "panel settings" },
      h(
        "h2",
        {},
        "Current release: " +
          release.versionName +
          " · build " +
          release.versionCode,
      ),
      h("p", { class: "small muted" }, "Fetched " + stamp(result.fetchedAt)),
      field("Version name", "versionName", release.versionName),
      field("Build number", "versionCode", release.versionCode, {
        type: "number",
        min: 1,
      }),
      field(
        "Release tag",
        "tag_name",
        release.tag_name || "v" + release.versionName,
      ),
      field("HTTPS APK download URL", "downloadUrl", release.downloadUrl, {
        type: "url",
      }),
      field("APK size in bytes", "apkSize", release.apkSize || 0, {
        type: "number",
        min: 0,
      }),
      h(
        "label",
        {},
        "Release notes",
        h(
          "textarea",
          { name: "releaseNotes", maxLength: 8000 },
          release.releaseNotes || "",
        ),
      ),
      h(
        "label",
        { class: "inline" },
        h("input", { type: "checkbox", name: "allowRollback" }),
        "Explicitly allow an older build number",
      ),
      h(
        "div",
        { class: "form-actions" },
        h(
          "button",
          { type: "submit", class: "primary" },
          "Review & publish manifest",
        ),
      ),
    );
    form.addEventListener("submit", async (e) => {
      e.preventDefault();
      const input = Object.fromEntries(new FormData(form));
      input.versionCode = Number(input.versionCode);
      input.apkSize = Number(input.apkSize);
      input.allowRollback = !!input.allowRollback;
      if (
        !(await confirmAction(
          "Publish app update manifest",
          `Publish ${input.versionName} (build ${input.versionCode})? This changes what update player devices are offered.`,
        ))
      )
        return;
      try {
        await api("release", { method: "PUT", data: input });
        toast("Release manifest saved.", "success");
        renderRelease();
      } catch (error) {
        toast(error.message, "error");
      }
    });
    root.replaceChildren(form);
  } catch (error) {
    if (generation === state.generation)
      root.replaceChildren(
        h(
          "div",
          { class: "notice warning" },
          "Release metadata is unavailable. No default version is presented as live. " +
            error.message,
        ),
      );
  }
}

$("login-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  $("sign-in").disabled = true;
  $("login-error").textContent = "";
  try {
    const result = await api("login", {
      method: "POST",
      data: { password: $("password").value },
      auth: false,
    });
    state.session = result;
    sessionStorage.setItem("bingo_admin_session_v3", JSON.stringify(result));
    await startConsole();
  } catch (error) {
    $("login-error").textContent =
      error.name === "AbortError"
        ? "The admin service timed out. Try again."
        : error.message;
  } finally {
    $("sign-in").disabled = false;
  }
});
async function startConsole() {
  const result = await api("session");
  $("operator").textContent = result.operator;
  $("login").hidden = true;
  $("console").hidden = false;
  $("password").value = "";
  state.generation++;
  await switchTab(location.hash.slice(1) || "players");
  await refresh();
}
$("lock").addEventListener("click", lock);
$("refresh").addEventListener("click", refresh);
$("refresh-activity").addEventListener("click", renderActivity);
$("theme").addEventListener("click", () => {
  const theme =
    document.documentElement.dataset.theme === "dark" ? "light" : "dark";
  document.documentElement.dataset.theme = theme;
  localStorage.setItem("bingo_admin_theme_v3", theme);
});
document.documentElement.dataset.theme =
  localStorage.getItem("bingo_admin_theme_v3") || "dark";
for (const button of $("navigation").querySelectorAll("button"))
  button.addEventListener("click", () => switchTab(button.dataset.tab));
for (const id of [
  "search",
  "status-filter",
  "provider-filter",
  "sort",
  "page-size",
])
  $(id).addEventListener(id === "search" ? "input" : "change", () => {
    state.page = 1;
    renderDirectory();
  });
$("refresh-interval").addEventListener("change", schedule);
$("table-view").addEventListener("click", () => {
  state.view = "table";
  state.viewExplicit = true;
  renderDirectory();
});
$("card-view").addEventListener("click", () => {
  state.view = "cards";
  state.viewExplicit = true;
  renderDirectory();
});
window.addEventListener("resize", () => {
  if (!state.viewExplicit) {
    state.view = innerWidth < 700 ? "cards" : "table";
    if (state.session) renderDirectory();
  }
});
document.addEventListener("visibilitychange", () => {
  if (document.visibilityState === "hidden") clearTimeout(state.timer);
  else if (state.session) refresh();
});
$("export").addEventListener("click", () => {
  const list = filtered();
  saveFile(
    "bingo-filtered-players-" + new Date().toISOString().slice(0, 10) + ".csv",
    "text/csv;charset=utf-8",
    "\ufeff" + playersCsv(list),
  );
  toast(`Exported ${list.length} filtered player(s).`, "success");
});
$("export-json").addEventListener("click", () => {
  if (state.snapshot)
    saveFile(
      "bingo-directory-snapshot.json",
      "application/json",
      JSON.stringify(
        { ...state.snapshot, snapshotStale: state.stale },
        null,
        2,
      ),
    );
});
$("inspector-close").addEventListener("click", () => $("inspector").close());
$("inspector-refresh").addEventListener("click", () => {
  if (state.selected) openPlayer(state.selected);
});
$("inspector").addEventListener("close", () => {
  state.readController?.abort();
  state.inspection++;
  state.selected = null;
  state.detail = null;
  $("inspector-body").replaceChildren();
});
for (const button of $("inspector-tabs").querySelectorAll("button"))
  button.addEventListener("click", () => {
    state.section = button.dataset.section;
    if (state.detail) renderInspection();
  });
$("confirmation-cancel").addEventListener("click", () =>
  resolveConfirmation(false),
);
$("confirmation").addEventListener("cancel", (e) => {
  e.preventDefault();
  resolveConfirmation(false);
});
$("confirmation").addEventListener("close", () => {
  if (confirmResolver) {
    const resolve = confirmResolver;
    confirmResolver = null;
    resolve(false);
  }
});
$("confirmation-form").addEventListener("submit", (e) => {
  e.preventDefault();
  if (
    confirmTyped &&
    $("confirmation-input").value.trim().replace(/^@/, "").toLowerCase() !==
      confirmTyped
  ) {
    $("confirmation-error").textContent = "The username does not match.";
    return;
  }
  resolveConfirmation(true);
});
$("add-player").addEventListener("click", () => $("create-player").showModal());
$("create-cancel").addEventListener("click", () => $("create-player").close());
$("create-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  const input = Object.fromEntries(new FormData(e.currentTarget));
  if (
    !(await confirmAction(
      "Register player record",
      "Register @" + input.username + " with UID " + input.uid + "?",
    ))
  )
    return;
  try {
    const result = await api("players", { method: "POST", data: input });
    toast(
      result.message,
      result.operation?.status === "SYNCED" ? "success" : "warning",
    );
    $("create-player").close();
    $("create-form").reset();
    await refresh();
  } catch (error) {
    toast(error.message, "error");
  }
});
$("password-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  const values = new FormData(e.currentTarget);
  if (values.get("password") !== values.get("confirm")) {
    toast("Passwords do not match.", "error");
    return;
  }
  try {
    await api("password", {
      method: "POST",
      data: { password: values.get("password") },
    });
    clearSession();
    $("login-error").textContent =
      "Password changed. Sign in again with the new password.";
  } catch (error) {
    toast(error.message, "error");
  }
});
try {
  const saved = JSON.parse(
    sessionStorage.getItem("bingo_admin_session_v3") || "null",
  );
  if (
    saved &&
    /^[a-f0-9]{64}$/.test(saved.token) &&
    saved.expiresAt > Date.now()
  ) {
    state.session = saved;
    startConsole().catch((error) => {
      clearSession();
      $("login-error").textContent = error.message;
    });
  }
} catch {
  sessionStorage.removeItem("bingo_admin_session_v3");
}

if ("serviceWorker" in navigator)
  navigator.serviceWorker
    .register("./sw.js", { scope: "./", updateViaCache: "none" })
    .then((registration) => registration.update())
    .catch(() => {});
