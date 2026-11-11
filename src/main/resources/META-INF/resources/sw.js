/*
 * Static-asset cache for the installable app shell.
 *
 * Pages are deliberately not cached: they contain household data and are
 * rendered per user. Offline data entry is out of scope for v1.
 *
 * Cache-first is intentional, so bumping CACHE here (and the matching ?v=
 * token on the stylesheet link in layout.html) is how an asset change reaches
 * installed clients. Static assets are also served with a one-day immutable
 * HTTP cache, so the query token is what makes a new URL for a new file.
 */
const CACHE = "domaci-odmeny-v2";
const ASSETS = [
    "/assets/pico.min.css",
    "/assets/app.css?v=2",
    "/assets/htmx.min.js",
    "/favicon.svg",
    "/manifest.webmanifest",
    "/icons/icon.svg",
    "/icons/icon-maskable.svg"
];

self.addEventListener("install", (event) => {
    event.waitUntil(
        caches.open(CACHE)
            .then((cache) => cache.addAll(ASSETS))
            .then(() => self.skipWaiting())
    );
});

self.addEventListener("activate", (event) => {
    event.waitUntil(
        caches.keys()
            .then((keys) => Promise.all(
                keys.filter((key) => key !== CACHE).map((key) => caches.delete(key))
            ))
            .then(() => self.clients.claim())
    );
});

self.addEventListener("fetch", (event) => {
    const url = new URL(event.request.url);
    if (event.request.method !== "GET" || url.origin !== self.location.origin) {
        return;
    }

    const cacheable = url.pathname.startsWith("/assets/")
        || url.pathname.startsWith("/icons/")
        || url.pathname === "/favicon.svg"
        || url.pathname === "/manifest.webmanifest";

    if (!cacheable) {
        return;
    }

    event.respondWith(
        caches.match(event.request).then((cached) => cached || fetch(event.request).then((response) => {
            const copy = response.clone();
            caches.open(CACHE).then((cache) => cache.put(event.request, copy));
            return response;
        }))
    );
});
