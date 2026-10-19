// Admin cache only. Never delete the Bingo game's origin caches or cache API data.
const PREFIX = "bingo-admin-";
const CACHE = PREFIX + "control-v1";
const base = new URL("./", self.location.href);
self.addEventListener("install", (event) =>
  event.waitUntil(self.skipWaiting()),
);
self.addEventListener("activate", (event) =>
  event.waitUntil(
    (async () => {
      for (const name of await caches.keys())
        if (name.startsWith(PREFIX) && name !== CACHE)
          await caches.delete(name);
      await self.clients.claim();
    })(),
  ),
);
self.addEventListener("fetch", (event) => {
  const url = new URL(event.request.url);
  if (
    event.request.method !== "GET" ||
    url.origin !== base.origin ||
    !url.pathname.startsWith(base.pathname)
  )
    return;
  // Fresh shell and code on every load; a disconnected admin must see a network failure.
  if (event.request.mode === "navigate" || !/\.(png|ico)$/.test(url.pathname)) {
    event.respondWith(fetch(event.request, { cache: "no-store" }));
    return;
  }
  event.respondWith(
    (async () => {
      const cache = await caches.open(CACHE);
      const existing = await cache.match(event.request);
      if (existing) return existing;
      const response = await fetch(event.request);
      if (response.ok) await cache.put(event.request, response.clone());
      return response;
    })(),
  );
});
