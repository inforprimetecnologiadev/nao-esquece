#!/usr/bin/env python3
"""Gera a pasta dist/ (site para o Netlify) a partir de app.html.

Uso: python3 build.py [ID_DO_CLIENTE_GOOGLE]
"""
import json, os, re, shutil, sys

HERE = os.path.dirname(os.path.abspath(__file__))
DIST = os.path.join(HERE, 'dist')
os.makedirs(DIST, exist_ok=True)
for _ic in ('apple-touch-icon.png', 'favicon.png', 'icon-192.png', 'icon-512.png', 'icon-maskable.png'):  # ícones (pasta icones/)
    _src = os.path.join(HERE, 'icones', _ic)
    if os.path.exists(_src) and not os.path.exists(os.path.join(DIST, _ic)): shutil.copyfile(_src, os.path.join(DIST, _ic))
CLIENT_ID = sys.argv[1] if len(sys.argv) > 1 else ''

src = open(os.path.join(HERE, 'app.html'), encoding='utf-8').read()
assert "const GOOGLE_CLIENT_ID = '';" in src
src = src.replace("const GOOGLE_CLIENT_ID = '';", f"const GOOGLE_CLIENT_ID = '{CLIENT_ID}';")

cut = src.index('<div class="gate"')
head_part, body_part = src[:cut], src[cut:]
head_part = head_part.replace('<meta name="claude:viewport-fit-sides" content="cover">\n', '')

RESET = (":root{color-scheme:light;box-sizing:border-box;padding-top:env(safe-area-inset-top,0px);"
         "padding-bottom:env(safe-area-inset-bottom,0px)}html{scroll-padding-top:env(safe-area-inset-top,0px)}"
         "body{margin:0;padding:0;font:14px -apple-system,BlinkMacSystemFont,sans-serif;background:#fff;color:#000}"
         "img{max-width:100%}[hidden]:not([hidden=until-found i]){display:none!important}")

html = ('<!doctype html><html lang="pt-BR"><head><meta charset="utf-8">'
        '<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">'
        '<meta name="description" content="Bloco de notas com áudio, fotos, checklist e pastas salvas no Google Drive.">'
        '<link rel="manifest" href="manifest.json"><link rel="icon" href="favicon.png">'
        '<link rel="apple-touch-icon" href="apple-touch-icon.png"><meta name="theme-color" content="#0a1122">'
        f'<style>{RESET}</style>\n' + head_part + '</head><body>\n' + body_part + '</body></html>\n')
open(os.path.join(DIST, 'index.html'), 'w', encoding='utf-8').write(html)

manifest = {
    "id": "/",
    "name": "Não Esquece",
    "short_name": "Não Esquece",
    "description": "Anote, grave áudios, tire fotos e marque o que já fez. Tudo salvo no celular e no seu Google Drive.",
    "lang": "pt-BR",
    "start_url": "./",
    "scope": "./",
    "display": "standalone",
    "orientation": "portrait",
    "background_color": "#0a1122",
    "theme_color": "#0a1122",
    "categories": ["productivity"],
    "icons": [
        {"src": "icon-192.png", "sizes": "192x192", "type": "image/png", "purpose": "any"},
        {"src": "icon-512.png", "sizes": "512x512", "type": "image/png", "purpose": "any"},
        {"src": "icon-maskable.png", "sizes": "512x512", "type": "image/png", "purpose": "maskable"}
    ],
    "shortcuts": [
        {"name": "Nova nota", "short_name": "Nova nota", "url": "./?nova=1",
         "icons": [{"src": "icon-192.png", "sizes": "192x192", "type": "image/png"}]}
    ]
}
json.dump(manifest, open(os.path.join(DIST, 'manifest.json'), 'w', encoding='utf-8'), ensure_ascii=False, indent=2)

SW = """// Não Esquece — guarda o app no aparelho para abrir sem internet.
const CACHE = 'nao-esquece-1.9.0';
const SHELL = ['./', './index.html', './manifest.json', './icon-192.png', './icon-512.png', './icon-maskable.png', './apple-touch-icon.png', './favicon.png', './privacidade.html', './termos.html', './suporte/'];
self.addEventListener('install', e => { e.waitUntil(caches.open(CACHE).then(c => c.addAll(SHELL)).then(() => self.skipWaiting())); });
self.addEventListener('activate', e => { e.waitUntil(caches.keys().then(ks => Promise.all(ks.filter(k => k !== CACHE).map(k => caches.delete(k)))).then(() => self.clients.claim())); });
self.addEventListener('fetch', e => {
  const req = e.request; if (req.method !== 'GET') return;
  const url = new URL(req.url);
  // Nunca guardar chamadas do Google (login e Drive).
  if (/googleapis\\.com|accounts\\.google\\.com/.test(url.hostname)) return;
  if (url.origin === location.origin) {
    // App: rede primeiro (pega atualizações), cai para o cache quando estiver offline.
    e.respondWith(fetch(req).then(r => { if (r.ok) { const cp = r.clone(); caches.open(CACHE).then(c => c.put(req, cp)); } return r; })
      .catch(() => caches.match(req, {ignoreSearch: true}).then(r => r || caches.match('./index.html'))));
  } else if (/fonts\\.(googleapis|gstatic)\\.com/.test(url.hostname)) {
    // Fontes: cache primeiro.
    e.respondWith(caches.match(req).then(r => r || fetch(req).then(res => { const cp = res.clone(); caches.open(CACHE).then(c => c.put(req, cp)); return res; })));
  }
});
"""
open(os.path.join(DIST, 'sw.js'), 'w', encoding='utf-8').write(SW)

open(os.path.join(DIST, '_headers'), 'w').write("""/*
  X-Content-Type-Options: nosniff
  Referrer-Policy: strict-origin-when-cross-origin
  Permissions-Policy: microphone=(self), camera=(self)

/
  Cache-Control: no-cache
/index.html
  Cache-Control: no-cache
/sw.js
  Cache-Control: no-cache
/manifest.json
  Cache-Control: no-cache
/.well-known/assetlinks.json
  Content-Type: application/json
  Access-Control-Allow-Origin: *
""")
# O PWABuilder entrega um assetlinks.json: basta colocar na raiz do site; esta regra o publica no endereço que o Android procura.
open(os.path.join(DIST, '_redirects'), 'w').write("/.well-known/assetlinks.json  /assetlinks.json  200\n")
# páginas públicas dos textos legais (para o Google Cloud e para quem quiser ler sem o app)
PAGE = """<!doctype html><html lang="pt-BR"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>{title} · Não Esquece</title><link rel="icon" href="favicon.png">
{fit}<style>:root{{--bg:#eef2f8;--ink:#121a2b;--muted:#5a6579;--line:#d4dcea;--accent:#0a54c8}}
@media (prefers-color-scheme:dark){{:root{{--bg:#0a1122;--ink:#e4eaf5;--muted:#93a0b8;--line:#25314b;--accent:#4ab6ff;color-scheme:dark}}}}
body{{margin:0;background:var(--bg);color:var(--ink);font:16px/1.6 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif}}
main{{max-width:68ch;margin:0 auto;padding:32px max(18px,env(safe-area-inset-left)) 48px}}
h1{{font-size:28px;line-height:1.2;margin:0 0 4px}} h2{{font-size:19px;margin:26px 0 6px}} h3{{font-size:16px;margin:16px 0 4px}}
ul{{padding-left:1.2em}} li{{margin:5px 0}} .lg-meta{{color:var(--muted);font-size:14px}} .lg-url{{font-weight:600;word-break:break-all}}
a{{color:var(--accent)}} footer{{margin-top:36px;padding-top:14px;border-top:1px solid var(--line);color:var(--muted);font-size:14px}}</style></head>
<body><main><h1>{title}</h1>{body}<footer>Não Esquece · <a href="./">abrir o app</a> · <a href="{other_href}">{other}</a> · <a href="suporte/">Ajuda e contato</a></footer></main></body></html>
"""
import re as _re
FIT = _re.search(r'<script>\n/\* Celular com[\s\S]*?</script>\n', src).group(0)
def _tpl(i): return _re.search(r'<template id="%s">([\s\S]*?)</template>' % i, src).group(1)
open(os.path.join(DIST, 'privacidade.html'), 'w', encoding='utf-8').write(PAGE.format(fit=FIT, title='Política de Privacidade', body=_tpl('tplPrivacy'), other_href='termos.html', other='Termos de Uso'))
open(os.path.join(DIST, 'termos.html'), 'w', encoding='utf-8').write(PAGE.format(fit=FIT, title='Termos de Uso', body=_tpl('tplTerms'), other_href='privacidade.html', other='Política de Privacidade'))
# central de ajuda e contato (página própria, fora do app)
os.makedirs(os.path.join(DIST, 'suporte'), exist_ok=True)
shutil.copyfile(os.path.join(HERE, 'suporte', 'index.html'), os.path.join(DIST, 'suporte', 'index.html'))
for junk in ('logo.b64',):
    p = os.path.join(DIST, junk)
    if os.path.exists(p): os.remove(p)
print('dist pronto', 'com ID do Google' if CLIENT_ID else 'SEM ID do Google')
