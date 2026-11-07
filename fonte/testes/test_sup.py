import asyncio, json, re
from playwright.async_api import async_playwright

BASE = 'http://localhost:8765/suporte/'
SHOT = '/tmp/claude-0/-home-claude/2130d5ca-9358-5b17-903f-31c60689c27a/scratchpad/'
results = []
def check(name, ok, extra=''):
    results.append((name, bool(ok)))
    print(('OK  ' if ok else 'FAIL'), name, extra)

NOWD = "Object.defineProperty(Navigator.prototype,'webdriver',{get:()=>false});"

async def fill_valid(page, msg='O lembrete das 7h não tocou no meu celular ontem, mesmo com a agenda marcada.'):
    await page.fill('#fNome', 'Maria Teste')
    await page.fill('#fEmail', 'maria@example.com')
    await page.select_option('#fAssunto', 'Dúvida ou problema no app')
    await page.fill('#fMsg', msg)
    await page.check('#fOk')

async def main():
    async with async_playwright() as p:
        b = await p.chromium.launch(executable_path='/usr/bin/google-chrome')
        # ---------- celular, escuro, vindo do app ----------
        ctx = await b.new_context(viewport={'width': 360, 'height': 780}, device_scale_factor=2, is_mobile=True, has_touch=True, locale='pt-BR', color_scheme='dark')
        await ctx.add_init_script(NOWD)
        page = await ctx.new_page()
        errors = []
        page.on('pageerror', lambda e: errors.append(str(e)))
        page.on('console', lambda m: errors.append('console: ' + m.text) if m.type == 'error' and 'net::' not in m.text and 'Failed to load' not in m.text else None)
        await page.route('https://fonts.googleapis.com/**', lambda r: r.abort())
        await page.route('https://fonts.gstatic.com/**', lambda r: r.abort())
        posts = []
        mode = {'v': 'ok'}
        async def fs(route):
            req = route.request
            posts.append(json.loads(req.post_data or '{}'))
            if mode['v'] == 'ok':
                await route.fulfill(status=200, content_type='application/json', body='{"success":"true","message":"The form was submitted successfully."}')
            elif mode['v'] == 'activate':
                await route.fulfill(status=200, content_type='application/json', body='{"success":"false","message":"This form needs Activation. We\'ve sent you an email containing an \'Activate Form\' link."}')
            else:
                await route.abort()
        await page.route('https://formsubmit.co/**', fs)

        await page.goto(BASE + '?v=1.1.1&origem=app')
        await page.wait_for_timeout(300)
        check('título da página', 'Central de ajuda' in await page.title())
        n = await page.locator('#groups details').count()
        check('perguntas carregadas', n >= 20, str(n))
        cats = await page.locator('#cats button').count()
        check('assuntos (6 botões)', cats == 6, str(cats))
        check('versão veio do app', await page.input_value('#fVersao') == '1.1.1')
        sw = await page.evaluate('document.documentElement.scrollWidth')
        check('sem rolagem lateral em 360px', sw <= 360, str(sw))
        await page.screenshot(path=SHOT + 'sup_m_dark_top.png')

        # busca
        await page.fill('#q', 'pin')
        await page.wait_for_timeout(400)
        vis = await page.eval_on_selector_all('#groups details', 'ds=>ds.filter(d=>!d.hidden).length')
        check('busca "pin" filtra', 0 < vis < n, str(vis))
        check('busca destaca a palavra', await page.locator('#groups summary mark').count() > 0)
        check('contador da busca', 'encontrada' in await page.inner_text('#count'))
        await page.fill('#q', 'lembrete')
        await page.wait_for_timeout(400)
        check('busca sem acento acha "lembrete"', await page.is_visible('#q-lembrete'))
        await page.fill('#q', 'xyzqwerty')
        await page.wait_for_timeout(400)
        check('busca sem resultado mostra aviso', await page.is_visible('#empty'))
        await page.fill('#q', '')
        await page.wait_for_timeout(400)
        check('limpar busca volta tudo', await page.eval_on_selector_all('#groups details', 'ds=>ds.filter(d=>!d.hidden).length') == n)

        # assunto
        await page.click('#cats button[data-cat="google"]')
        await page.wait_for_timeout(100)
        g = await page.eval_on_selector_all('.group', 'gs=>gs.filter(g=>!g.hidden).map(g=>g.dataset.cat)')
        check('filtro por assunto', g == ['google'], str(g))
        await page.click('#cats button[data-cat="all"]')

        # atalho
        await page.click('.chip[href="#q-pin"]')
        await page.wait_for_timeout(300)
        check('atalho abre a resposta', await page.evaluate("document.getElementById('q-pin').open"))
        await page.screenshot(path=SHOT + 'sup_m_dark_faq.png')

        # formulário: validações
        await page.goto(BASE + '?v=1.1.1&origem=app&t=2#contato')
        await page.wait_for_timeout(200)
        await page.click('#fSend')
        check('envio rápido demais é barrado', 'Espere' in await page.inner_text('#fErr'))
        await page.wait_for_timeout(4000)
        await page.click('#fSend')
        check('pede o nome', 'nome' in await page.inner_text('#fErr'))
        await page.fill('#fNome', 'Maria Teste'); await page.fill('#fEmail', 'maria@')
        await page.click('#fSend')
        check('valida e-mail', 'e-mail válido' in await page.inner_text('#fErr'))
        await page.fill('#fEmail', 'maria@example.com')
        await page.select_option('#fAssunto', 'Pedido de titular (LGPD)')
        check('pedido LGPD mostra tipo de pedido', await page.is_visible('#fPedido'))
        await page.click('#fSend')
        check('exige tipo de pedido LGPD', 'tipo de pedido' in await page.inner_text('#fErr'))
        await page.select_option('#fPedido', 'Eliminação de dados')
        await page.fill('#fMsg', 'curta')
        await page.click('#fSend')
        check('mensagem mínima de 20', '20 caracteres' in await page.inner_text('#fErr'))
        await page.fill('#fMsg', 'Quero apagar meus dados. Minha chave é ABCD-EFGH-JKLM-NPQR-STUV, ok?')
        await page.click('#fSend')
        check('bloqueia chave de recuperação na mensagem', 'chave de recuperação' in await page.inner_text('#fErr'))
        await page.fill('#fMsg', 'Quero apagar meus dados de suporte enviados anteriormente, por favor.')
        await page.click('#fSend')
        check('exige consentimento', 'concordância' in await page.inner_text('#fErr'))
        await page.check('#fOk')
        await page.screenshot(path=SHOT + 'sup_m_dark_form.png')
        await page.click('#fSend')
        await page.wait_for_selector('#done:not([hidden])', timeout=5000)
        proto = await page.inner_text('#proto')
        check('protocolo no formato NE-AAAAMMDD-XXXX', re.fullmatch(r'NE-\d{8}-[A-Z2-9]{4}', proto) is not None, proto)
        d = posts[-1] if posts else {}
        check('envia para o FormSubmit com assunto e protocolo', d.get('_subject', '').startswith('[' + proto + ']') and 'LGPD' in d.get('_subject', ''), d.get('_subject'))
        check('resposta vai para o e-mail do usuário', d.get('_replyto') == 'maria@example.com')
        check('pedido LGPD leva prazo de 15 dias', 'Tipo de pedido (LGPD)' in d and '15 dias' in d.get('Prazo legal', ''))
        check('origem App e versão', d.get('Origem') == 'App (Ajustes)' and d.get('Versão do app') == '1.1.1')
        check('honeypot vazio e captcha desligado', d.get('_honey') == '' and d.get('_captcha') == 'false')
        check('texto de sucesso cita 5 dias úteis', '5 dias úteis' in await page.inner_text('#doneTxt'))
        check('rascunho apagado depois do envio', await page.evaluate("localStorage.getItem('ne.sup.draft')") is None)
        await page.screenshot(path=SHOT + 'sup_m_dark_done.png')

        # rascunho salvo
        await page.click('#again')
        await page.fill('#fMsg', 'rascunho que deve ficar guardado')
        await page.reload(); await page.wait_for_timeout(300)
        check('rascunho volta ao recarregar', await page.input_value('#fMsg') == 'rascunho que deve ficar guardado')

        # falha → alternativa por e-mail
        await page.wait_for_timeout(4000)
        mode['v'] = 'fail'
        await fill_valid(page)
        await page.click('#fSend'); await page.wait_for_timeout(600)
        check('falha mostra erro', 'Não deu para enviar' in await page.inner_text('#fErr') or 'Sem internet' in await page.inner_text('#fErr'), await page.inner_text('#fErr'))
        href = await page.get_attribute('#fMailto', 'href')
        check('falha oferece e-mail pronto', await page.is_visible('#fFallback') and href.startswith('mailto:inforprimetecnologia@gmail.com?subject='), href[:70])
        check('formulário mantém o texto após falha', len(await page.input_value('#fMsg')) > 20)
        mode['v'] = 'activate'
        await page.click('#fSend'); await page.wait_for_timeout(500)
        check('aviso de ativação do canal', 'sendo ativado' in await page.inner_text('#fErr'))

        # limite de 3 por hora
        mode['v'] = 'ok'
        await page.evaluate("localStorage.setItem('ne.sup.sent', JSON.stringify([Date.now(),Date.now(),Date.now()]))")
        before = len(posts)
        await page.click('#fSend'); await page.wait_for_timeout(300)
        check('limite de 3 envios por hora', '3 mensagens' in await page.inner_text('#fErr') and len(posts) == before)
        await page.evaluate("localStorage.removeItem('ne.sup.sent')")

        # honeypot preenchido → finge sucesso sem enviar
        await page.evaluate("document.getElementById('fHoney').value='spam'")
        before = len(posts)
        await page.click('#fSend'); await page.wait_for_timeout(300)
        check('robô no honeypot não envia', len(posts) == before and await page.is_visible('#done'))
        check('sem erros de script', not errors, ' | '.join(errors[:4]))
        await ctx.close()

        # ---------- navegador automatizado (webdriver) ----------
        ctx = await b.new_context(viewport={'width': 390, 'height': 800}, locale='pt-BR')
        page = await ctx.new_page()
        await page.route('https://fonts.googleapis.com/**', lambda r: r.abort())
        await page.route('https://fonts.gstatic.com/**', lambda r: r.abort())
        hits = []
        await page.route('https://formsubmit.co/**', lambda r: (hits.append(1), r.abort()))
        await page.goto(BASE); await page.wait_for_timeout(4200)
        await fill_valid(page)
        await page.click('#fSend'); await page.wait_for_timeout(300)
        check('bloqueia navegador controlado por robô', 'automação' in await page.inner_text('#fErr') and not hits)
        await ctx.close()

        # ---------- capturas: celular claro e computador ----------
        for name, vp, scheme in [('sup_m_light', {'width': 390, 'height': 844}, 'light'), ('sup_d_light', {'width': 1280, 'height': 860}, 'light'), ('sup_d_dark', {'width': 1280, 'height': 860}, 'dark')]:
            ctx = await b.new_context(viewport=vp, device_scale_factor=1, color_scheme=scheme, locale='pt-BR')
            await ctx.add_init_script(NOWD)
            page = await ctx.new_page()
            await page.goto(BASE); await page.wait_for_timeout(1200)
            sw = await page.evaluate('document.documentElement.scrollWidth')
            check(f'{name}: sem rolagem lateral', sw <= vp['width'], str(sw))
            await page.screenshot(path=SHOT + name + '.png')
            if name == 'sup_d_light':
                await page.goto(BASE + '#contato'); await page.wait_for_timeout(500)
                await page.screenshot(path=SHOT + 'sup_d_light_contato.png')
                await page.goto(BASE + '#lgpd'); await page.wait_for_timeout(500)
                await page.screenshot(path=SHOT + 'sup_d_light_lgpd.png')
            await ctx.close()

        # ---------- app: linha Ajuda e contato ----------
        ctx = await b.new_context(viewport={'width': 390, 'height': 844}, locale='pt-BR')
        await ctx.add_init_script(NOWD)
        page = await ctx.new_page()
        await page.route('https://fonts.googleapis.com/**', lambda r: r.abort())
        await page.route('https://fonts.gstatic.com/**', lambda r: r.abort())
        await page.goto('http://localhost:8765/index.html'); await page.wait_for_timeout(500)
        lnk = page.locator('a[href="suporte/"]')
        check('tela de termos tem link Ajuda e contato', await lnk.count() >= 1)
        tv = await page.evaluate('TERMS_VERSION + "|" + APP_VERSION')
        check('versões novas no app', tv == '2026-11-06.6|1.9.0', tv)
        await ctx.close()
        await b.close()
    fails = [r for r in results if not r[1]]
    print(f'\n{len(results)-len(fails)}/{len(results)} verificações passaram')

asyncio.run(main())
