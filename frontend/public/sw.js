// Service worker de l'application installable (PWA). Règle de sécurité : AUCUNE réponse d'API n'est mise en cache (données personnelles,
// jetons) ; seuls l'interface (index.html) et les ressources statiques versionnées (/assets/*, icônes) le sont, pour un démarrage rapide
// et un écran hors ligne lisible. Les données restent toujours lues en direct sur le serveur.
const CACHE = 'vas-shell-v1';
const SHELL = ['/', '/index.html', '/manifest.webmanifest', '/icons/icon-192.png', '/icons/icon.svg'];
const API = /^\/(admin|api|auth|portal|oauth|hooks|callbacks|actuator|dev|v3|swagger-ui)(\/|$)/;

self.addEventListener('install', (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', (e) => {
  e.waitUntil(caches.keys().then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k)))).then(() => self.clients.claim()));
});

self.addEventListener('fetch', (e) => {
  const url = new URL(e.request.url);
  if (e.request.method !== 'GET' || url.origin !== self.location.origin || API.test(url.pathname)) return; // réseau uniquement
  if (e.request.mode === 'navigate') {
    // page : réseau d'abord (version à jour), interface en cache si hors ligne
    e.respondWith(fetch(e.request).then((r) => { const copy = r.clone(); caches.open(CACHE).then((c) => c.put('/index.html', copy)); return r; })
      .catch(() => caches.match('/index.html')));
    return;
  }
  if (url.pathname.startsWith('/assets/') || url.pathname.startsWith('/icons/')) {
    // fichiers versionnés par le build : le cache suffit
    e.respondWith(caches.match(e.request).then((hit) => hit || fetch(e.request).then((r) => { if (r.ok) { const copy = r.clone(); caches.open(CACHE).then((c) => c.put(e.request, copy)); } return r; })));
  }
});
