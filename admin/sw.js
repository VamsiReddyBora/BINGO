// Bingo Server Admin Service Worker (PWA)
const CACHE_NAME = 'bingo-admin-v4';
const ASSETS_TO_CACHE = [
  '/BINGO/admin/',
  '/BINGO/admin/index.html',
  '/BINGO/admin/manifest.json',
  '/BINGO/admin/favicon.png',
  '/BINGO/admin/splash-logo.png',
  '/BINGO/admin/icon-192.png',
  '/BINGO/admin/icon-512.png',
  '/BINGO/admin/icon-maskable-192.png',
  '/BINGO/admin/icon-maskable-512.png'
];

// Install: Cache core assets and activate immediately
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

// Activate: Claim clients immediately and clear old caches
self.addEventListener('activate', (event) => {
  event.waitUntil(
    Promise.all([
      self.clients.claim(),
      caches.keys().then((keys) => {
        return Promise.all(
          keys.filter((key) => key !== CACHE_NAME).map((key) => caches.delete(key))
        );
      })
    ])
  );
});

// Fetch: Pass through dynamic APIs (KeyValue, MQTT, GitHub) and serve app shell
self.addEventListener('fetch', (event) => {
  const url = new URL(event.request.url);

  // Dynamic API calls & WebSocket connections: always network-only
  if (
    url.hostname.includes('keyvalue.xyz') ||
    url.hostname.includes('emqx.io') ||
    url.hostname.includes('github') ||
    event.request.method !== 'GET'
  ) {
    return;
  }

  // App shell & static assets: Network-first with cache fallback
  event.respondWith(
    fetch(event.request)
      .then((response) => {
        if (response && response.status === 200) {
          const responseClone = response.clone();
          caches.open(CACHE_NAME).then((cache) => {
            cache.put(event.request, responseClone);
          });
        }
        return response;
      })
      .catch(() => {
        return caches.match(event.request).then((cachedResponse) => {
          if (cachedResponse) return cachedResponse;
          if (event.request.mode === 'navigate') {
            return caches.match('/BINGO/admin/index.html').then((r) => r || caches.match('/BINGO/admin/'));
          }
        });
      })
  );
});
