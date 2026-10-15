// Minimal service worker: Chrome needs one with a fetch handler before it
// offers to install the page as a full-screen app. Everything goes to the
// network; the dashboard is live data, so nothing is cached.
self.addEventListener("install", () => self.skipWaiting());
self.addEventListener("activate", (e) => e.waitUntil(self.clients.claim()));
self.addEventListener("fetch", () => {});
