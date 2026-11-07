# Código-fonte do Não Esquece (parte do site)

O app do Android (`android/`) abre o site do GitHub Pages (`site/`). O site é gerado a partir desta pasta.

## Arquivos
- `app.html`: o app inteiro (HTML, CSS e JavaScript num arquivo só).
- `build.py`: gera a pasta `dist/` (site, manifest, service worker, termos, privacidade e suporte).
- `suporte/index.html`: página de ajuda e contato.
- `icones/`: ícones copiados para o site.
- `textos/legal.py`: Termos, Privacidade, versão dos termos e Novidades (changelog).
- `textos/inject_legal.py`: coloca os textos de `legal.py` dentro do `app.html` e gera o `CHANGELOG.md` da raiz.
- `testes/`: testes automáticos (Playwright com o Chrome) do site e do app Android simulado.

## Publicar uma versão nova
1. Editar `app.html` (e `APP_VERSION`), `textos/legal.py` (novidades; e `TERMS_VERSION` se os termos mudarem) e a versão em `suporte/index.html`.
2. Trocar o nome do cache em `build.py` (`const CACHE = 'nao-esquece-X.Y.Z'`).
3. `python3 textos/inject_legal.py`
4. `python3 build.py 944695371064-igcp4f6e80qir29g9bakv7qsdjmqle73.apps.googleusercontent.com` (ID do cliente OAuth do app). Sem o ID, sai a versão de teste, que tem "Usar sem conta".
5. Copiar `dist/` (index.html, manifest.json, privacidade.html, termos.html, sw.js, suporte/index.html) para `../site/`.
6. Se mudou algo em `android/`: subir `versionCode`/`versionName` em `android/app/build.gradle`, rodar o workflow **Gerar APK**, assinar o APK do ramo `apk-build` com a chave (zipalign + apksigner, apelido `naoesquece`) e colocar em `../site/NaoEsquece.apk`.
7. Commit no `main`: o GitHub publica o site e cria a release `vX.Y.Z` com o APK.

## Testes
Servir o `dist/` em `http://localhost:8765` (`python3 -m http.server 8765` dentro de `dist/`) e rodar `python3 testes/test_*.py`.
- `test_apk.py` precisa do build com o ID do Google; os outros, do build de teste (sem ID).
- `test_apk.py` também usa `../android/app/src/androidTest/assets/teste.webm`.
- No emulador Android 14: workflow **Testar no emulador Android** (o resultado vai para o ramo `emulador-resultado`).

## Chave de assinatura do APK
Não fica neste repositório (ele é público). Está com o dono do app: `release.keystore` + senha, apelido `naoesquece`,
certificado SHA-1 `79:24:D3:AC:22:7B:F4:31:7E:94:B3:CF:DC:0E:AB:5B:9B:E3:B9:97`. Sem ela, um APK novo não instala por cima do atual.
