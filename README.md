# Não Esquece

Bloco de notas para o celular: texto, checklist, áudios, fotos, documentos escaneados em PDF, leitura do texto de fotos e documentos (sem internet), assinatura e desenho com o dedo, pastas, etiquetas, urgentes, concluídas, lixeira, histórico de versões, nota secreta, barra de abas (Notas, Hoje, Calendário e Relatório), relatório em PDF do dia ou da semana e lembretes com alarme (que podem repetir), salvo no aparelho e no Google Drive.

- `site/` — o app (publicado no GitHub Pages em https://inforprimetecnologiadev.github.io/nao-esquece/)
- `site/suporte/` — central de ajuda e contato (formulário que chega por e-mail, via FormSubmit)
- `android/` — o APK nativo: as telas rodam dentro do próprio app (WebView do Android), sem usar o Chrome. Login do Google pela tela de contas do Android, digital/rosto, câmera, galeria, microfone, ditado, salvar e compartilhar pelo Android. Os lembretes tocam como alarme do próprio Android (aviso 1 minuto antes com som, vibração ou os dois) e, se a pessoa permitir, entram no calendário do celular, na agenda da mesma conta Google. Tem widget "Hoje" na tela inicial (com botões de nota e áudio rápidos), botões na barra de configurações rápidas do Android, scanner de documentos do Google e leitura de texto (ML Kit, dentro do aparelho)
- `.github/workflows/` — publica o site, compila o APK a cada atualização e testa o alarme num Android 14 de verdade (emulador)

- Versão atual: **1.5.0** (veja o [CHANGELOG](CHANGELOG.md))
- [Central de ajuda e contato](https://inforprimetecnologiadev.github.io/nao-esquece/suporte/) · suporte em até 5 dias úteis
- [Política de Privacidade](https://inforprimetecnologiadev.github.io/nao-esquece/privacidade.html) · [Termos de Uso](https://inforprimetecnologiadev.github.io/nao-esquece/termos.html)

- A verificação de domínio do app (Digital Asset Links) fica no repositório [inforprimetecnologiadev.github.io](https://github.com/inforprimetecnologiadev/inforprimetecnologiadev.github.io), em `.well-known/assetlinks.json`.
