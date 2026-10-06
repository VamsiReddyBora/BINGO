// Bingo Server Admin Service Worker (PWA) - v6
const CACHE_NAME = 'bingo-admin-v6';
const ASSETS_TO_CACHE = [
  '/BINGO/admin/manifest.json',
  '/BINGO/admin/favicon.png',
  '/BINGO/admin/splash-logo.png',
  '/BINGO/admin/icon-192.png',
  '/BINGO/admin/icon-512.png',
  '/BINGO/admin/icon-maskable-192.png',
  '/BINGO/admin/icon-maskable-512.png'
];

// Install: Cache icons and activate immediately
self.addEventListener('install', (event) => {
  self.skipWaiting();
  event.waitUntil(
    caches.open(CACHE_NAME).then((cache) => {
      return cache.addAll(ASSETS_TO_CACHE).catch((err) => {
        console.warn('Cache addAll warning:', err);
      });
    })
  );
});

// Activate: Claim clients immediately and purge ALL old caches
self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((keys) => {
      return Promise.all(
        keys.filter((key) => key !== CACHE_NAME).map((key) => caches.delete(key))
      );
    }).then(() => self.clients.claim())
  );
});

// Fetch: Always network-only for HTML, API calls, and WebSockets
self.addEventListener('fetch', (event) => {
  const url = new URL(event.request.url);

  // Dynamic API calls & WebSocket connections: always network-only
  if (
    url.hostname.includes('keyvalue') ||
    url.hostname.includes('emqx.io') ||
    url.hostname.includes('github') ||
    url.hostname.includes('extendsclass') ||
    event.request.method !== 'GET'
  ) {
    return;
  }

  // HTML page and root navigation: ALWAYS NETWORK-FIRST (NEVER STALE CACHE)
  if (
    event.request.mode === 'navigate' ||
    url.pathname.endsWith('/admin/') ||
    url.pathname.endsWith('/admin/index.html') ||
    url.pathname.endsWith('.html')
  ) {
    event.respondWith(
      fetch(event.request, { cache: 'no-store' }).catch(() => {
        return caches.match('/BINGO/admin/index.html');
      })
    );
    return;
  }

  // Static assets (images, icons, manifest): Cache with network fallback
  event.respondWith(
    caches.match(event.request).then((cachedResponse) => {
      if (cachedResponse) return cachedResponse;
      return fetch(event.request).then((response) => {
        if (response && response.status === 200) {
          const responseClone = response.clone();
          caches.open(CACHE_NAME).then((cache) => {
            cache.put(event.request, responseClone);
          });
        }
        return response;
      });
    })
  );
});
