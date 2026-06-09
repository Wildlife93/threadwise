// Threadwise Service Worker
const CACHE_VERSION = 'tw-v14';
const SHELL_CACHE   = `${CACHE_VERSION}-shell`;
const DATA_CACHE    = `${CACHE_VERSION}-data`;

// App shell — everything needed to load offline
const SHELL_ASSETS = [
  '/',
  '/index.html',
  '/manifest.json',
  '/icons/icon-192.png',
  '/icons/icon-512.png',
  // Google Fonts (cached on first fetch via network-first below)
];

const offlineFallbackPage = '/index.html';

// PWABuilder offline support pattern

// ── Install ───────────────────────────────────────────────────────────────
self.addEventListener('install', event => {
  event.waitUntil(
    caches.open(SHELL_CACHE).then(cache =>
      // Cache each asset individually so a missing icon/manifest
      // does not abort the entire install and leave the app uncached offline.
      Promise.allSettled(
        SHELL_ASSETS.map(url =>
          cache.add(url).catch(err => console.warn('[SW] Failed to cache:', url, err))
        )
      )
    ).then(() => self.skipWaiting())
  );
});

// ── Activate ──────────────────────────────────────────────────────────────
self.addEventListener('activate', event => {
  event.waitUntil(
    caches.keys().then(keys =>
      Promise.all(
        keys
          .filter(k => k.startsWith('tw-') && k !== SHELL_CACHE && k !== DATA_CACHE)
          .map(k => caches.delete(k))
      )
    ).then(() => self.clients.claim())
  );
});

// ── Fetch strategy ────────────────────────────────────────────────────────
self.addEventListener('fetch', event => {
  const { request } = event;
  const url = new URL(request.url);

  // Navigation requests — serve cached index.html immediately
  if (request.mode === 'navigate') {
    event.respondWith(
      caches.match('/index.html').then(cached => {
        const networkFetch = fetch(request).then(res => {
          if (res.ok) {
            const clone = res.clone();
            caches.open(SHELL_CACHE).then(c => c.put('/index.html', clone));
          }
          return res;
        }).catch(() => cached || caches.match('/index.html'));
        return cached || networkFetch;
      })
    );
    return;
  }

  // Never intercept Firebase, Cloudinary, or extension requests
  if (
    url.hostname.includes('firestore.googleapis.com') ||
    url.hostname.includes('firebase') ||
    url.hostname.includes('cloudinary.com') ||
    url.hostname.includes('googleapis.com') && url.pathname.includes('token') ||
    request.method !== 'GET'
  ) return;

  // Google Fonts — network first, fall back to cache
  if (url.hostname.includes('fonts.googleapis.com') || url.hostname.includes('fonts.gstatic.com')) {
    event.respondWith(
      fetch(request)
        .then(res => {
          const clone = res.clone();
          caches.open(DATA_CACHE).then(c => c.put(request, clone));
          return res;
        })
        .catch(() => caches.match(request))
    );
    return;
  }

  // CDN deps (TweetNaCl, etc.) — cache first
  if (url.hostname.includes('cdn.jsdelivr.net') || url.hostname.includes('cdnjs.cloudflare.com')) {
    event.respondWith(
      caches.match(request).then(cached => cached || fetch(request).then(res => {
        const clone = res.clone();
        caches.open(DATA_CACHE).then(c => c.put(request, clone));
        return res;
      }))
    );
    return;
  }

  // App shell — cache first, fall back to network, fall back to /index.html
  event.respondWith(
    caches.match(request).then(cached => {
      if (cached) return cached;
      return fetch(request)
        .then(res => {
          if (res.ok) {
            const clone = res.clone();
            caches.open(SHELL_CACHE).then(c => c.put(request, clone));
          }
          return res;
        })
            })
  );
});

// ── Push notifications ─────────────────────────────────────────────────────
self.addEventListener('push', event => {
  let data = {};
  try { data = event.data?.json() || {}; } catch {}

  const title   = data.notification?.title || 'Threadwise';
  const options = {
    body:    data.notification?.body || 'New message',
    icon:    '/icons/icon-192.png',
    badge:   '/icons/icon-96.png',
    tag:     'tw-message',
    renotify: true,
    vibrate: [200, 100, 200],
    data:    data.data || {},
    actions: [
      { action: 'open',    title: 'Open' },
      { action: 'dismiss', title: 'Dismiss' }
    ]
  };

  event.waitUntil(self.registration.showNotification(title, options));
});

// ── Notification click ────────────────────────────────────────────────────
self.addEventListener('notificationclick', event => {
  event.notification.close();
  if (event.action === 'dismiss') return;

  event.waitUntil(
    clients.matchAll({ type: 'window', includeUncontrolled: true }).then(clientList => {
      // Focus existing window if open
      for (const client of clientList) {
        if (client.url.includes(self.location.origin) && 'focus' in client) {
          return client.focus();
        }
      }
      // Otherwise open a new window
      return clients.openWindow('/');
    })
  );
});

// ── Background sync (flush send queue on reconnect) ───────────────────────
self.addEventListener('sync', event => {
  if (event.tag === 'tw-send-queue') {
    // Signal all open clients to flush their send queue
    event.waitUntil(
      clients.matchAll({ type: 'window' }).then(clientList => {
        clientList.forEach(client => client.postMessage({ type: 'FLUSH_SEND_QUEUE' }));
      })
    );
  }
});
