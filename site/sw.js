// Não Esquece — guarda o app no aparelho para abrir sem internet.
const CACHE = 'nao-esquece-1.6.0';
const SHELL = ['./', './index.html', './manifest.json', './icon-192.png', './icon-512.png', './icon-maskable.png', './apple-touch-icon.png', './favicon.png', './privacidade.html', './termos.html', './suporte/'];
self.addEventListener('install', e => { e.waitUntil(caches.open(CACHE).then(c => c.addAll(SHELL)).then(() => self.skipWaiting())); });
self.addEventListener('activate', e => { e.waitUntil(caches.keys().then(ks => Promise.all(ks.filter(k => k !== CACHE).map(k => caches.delete(k)))).then(() => self.clients.claim())); });
self.addEventListener('fetch', e => {
  const req = e.request; if (req.method !== 'GET') return;
  const url = new URL(req.url);
  // Nunca guardar chamadas do Google (login e Drive).
  if (/googleapis\.com|accounts\.google\.com/.test(url.hostname)) return;
  if (url.origin === location.origin) {
    // App: rede primeiro (pega atualizações), cai para o cache quando estiver offline.
    e.respondWith(fetch(req).then(r => { if (r.ok) { const cp = r.clone(); caches.open(CACHE).then(c => c.put(req, cp)); } return r; })
      .catch(() => caches.match(req, {ignoreSearch: true}).then(r => r || caches.match('./index.html'))));
  } else if (/fonts\.(googleapis|gstatic)\.com/.test(url.hostname)) {
    // Fontes: cache primeiro.
    e.respondWith(caches.match(req).then(r => r || fetch(req).then(res => { const cp = res.clone(); caches.open(CACHE).then(c => c.put(req, cp)); return res; })));
  }
});
