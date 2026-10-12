// Heat Web service worker: caches the app shell so Heat opens offline after the first visit.
const VER = "heat-web-1";
const SHELL = [
  "./", "index.html", "style.css", "app.js", "manifest.webmanifest",
  "vendor/qrcode.js", "vendor/jsQR.js",
  "assets/monocraft.ttf", "assets/egg.webp",
  "assets/icon-180.png", "assets/icon-192.png", "assets/icon-512.png"
];

self.addEventListener("install", (e) => {
  e.waitUntil(caches.open(VER).then((c) => c.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener("activate", (e) => {
  e.waitUntil(
    caches.keys().then((ks) => Promise.all(ks.filter((k) => k !== VER).map((k) => caches.delete(k)))).then(() => self.clients.claim())
  );
});

// Cache first, refresh in the background.
self.addEventListener("fetch", (e) => {
  const req = e.request;
  if (req.method !== "GET" || new URL(req.url).origin !== location.origin) return;
  e.respondWith(
    caches.match(req, { ignoreSearch: true }).then((hit) => {
      const net = fetch(req)
        .then((res) => {
          if (res.ok) caches.open(VER).then((c) => c.put(req, res.clone()));
          return res;
        })
        .catch(() => hit);
      return hit || net;
    })
  );
});
