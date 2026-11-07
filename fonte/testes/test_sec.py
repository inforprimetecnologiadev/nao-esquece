import asyncio, re
from playwright.async_api import async_playwright

URL = 'http://localhost:8765/index.html'
SHOT = '/tmp/claude-0/-home-claude/2130d5ca-9358-5b17-903f-31c60689c27a/scratchpad/'
results = []
def check(name, ok, extra=''):
    results.append((name, bool(ok))); print(('OK  ' if ok else 'FAIL'), name, extra)
NOT_BOT = "Object.defineProperty(Navigator.prototype,'webdriver',{get:()=>false});"

async def pin(page, digits):
    for d in digits:
        await page.click(f'#pPad [data-d="{d}"]')
    await page.wait_for_timeout(150)

async def wait_text(page, sel, text, ms=8000):
    for _ in range(ms // 100):
        try:
            if text in (await page.inner_text(sel)): return True
        except Exception: pass
        await page.wait_for_timeout(100)
    return False

async def setup(page, pinv='482916'):
    """aceita os termos, entra sem conta e cria o PIN; devolve a chave de recuperação"""
    await page.click('#tOk'); await page.click('#tAccept'); await page.wait_for_timeout(200)
    await page.click('#gSkip'); await page.wait_for_timeout(200)
    await page.click('#suGo'); await page.wait_for_timeout(150)
    await pin(page, pinv); await pin(page, pinv)
    await page.wait_for_selector('#rkVal', timeout=15000)
    key = (await page.inner_text('#rkVal')).strip()
    await page.click('#rkAck'); await page.click('#rkDone'); await page.wait_for_timeout(300)
    if await page.is_visible('#bNo'): await page.click('#bNo'); await page.wait_for_timeout(200)
    return key

async def new_page(b, prf=None):
    ctx = await b.new_context(viewport={'width': 390, 'height': 844}, device_scale_factor=2, is_mobile=True, has_touch=True, locale='pt-BR', timezone_id='America/Campo_Grande', color_scheme='dark')
    await ctx.add_init_script(NOT_BOT)
    page = await ctx.new_page()
    errs = []; page.on('pageerror', lambda e: errs.append(str(e)))
    await page.route('https://fonts.**', lambda r: r.abort())
    cdp = None
    if prf is not None:
        cdp = await ctx.new_cdp_session(page)
        await cdp.send('WebAuthn.enable', {'enableUI': False})
        await cdp.send('WebAuthn.addVirtualAuthenticator', {'options': {'protocol': 'ctap2', 'ctap2Version': 'ctap2_1', 'transport': 'internal', 'hasResidentKey': True, 'hasUserVerification': True, 'isUserVerified': True, 'automaticPresenceSimulation': True, 'hasPrf': prf}})
    return ctx, page, errs

async def main():
    async with async_playwright() as p:
        b = await p.chromium.launch(executable_path='/usr/bin/google-chrome', args=['--use-fake-ui-for-media-stream', '--use-fake-device-for-media-stream'])

        # ---------- robô ----------
        ctx0 = await b.new_context(); pg0 = await ctx0.new_page(); await pg0.route('https://fonts.**', lambda r: r.abort())
        await pg0.goto(URL); await pg0.wait_for_timeout(500)
        check('navegador automatizado é bloqueado', 'Acesso bloqueado' in await pg0.inner_text('#secScr'))
        await ctx0.close()

        ctx, page, errs = await new_page(b)
        await page.goto(URL); await page.wait_for_timeout(500)
        # ---------- termos ----------
        check('termos aparecem na primeira abertura', await page.is_visible('#terms'))
        check('aceitar fica desligado até marcar', await page.is_disabled('#tAccept'))
        await page.click('#tReadPriv'); await page.wait_for_timeout(200)
        body = await page.inner_text('#rdBody')
        check('política completa (LGPD, direitos, Drive legível)', 'art. 18' in body and 'ANPD' in body and 'não são criptografadas de ponta a ponta' in body)
        await page.screenshot(path=SHOT + 'v_politica.png')
        await page.click('#rdClose')
        await page.screenshot(path=SHOT + 'v_termos.png')
        # ---------- PIN ----------
        await page.click('#tOk'); await page.click('#tAccept'); await page.wait_for_timeout(200)
        await page.click('#gSkip'); await page.wait_for_timeout(200)
        await page.screenshot(path=SHOT + 'v_setup.png')
        await page.click('#suGo'); await page.wait_for_timeout(150)
        await pin(page, '123456')
        check('PIN óbvio é recusado', 'fácil de adivinhar' in await page.inner_text('#pMsg'))
        await pin(page, '482916'); await pin(page, '482917')
        check('PINs diferentes são recusados', 'não são iguais' in await page.inner_text('#pMsg'))
        await pin(page, '482916'); await pin(page, '482916')
        await page.wait_for_selector('#rkVal', timeout=15000)
        key = (await page.inner_text('#rkVal')).strip()
        check('chave de recuperação no formato certo', re.fullmatch(r'[A-Z2-9]{4}(-[A-Z2-9]{4}){4}', key) is not None, key)
        check('continuar exige confirmar que guardou', await page.is_disabled('#rkDone'))
        await page.fill('#rkEmail', 'recupera@exemplo.com')
        hrefs = []
        await page.evaluate("()=>{ window.__mailto=[]; const o=HTMLAnchorElement.prototype.click; HTMLAnchorElement.prototype.click=function(){ if(this.href.startsWith('mailto:')){ window.__mailto.push(this.href); return; } return o.call(this); }; }")
        await page.click('#rkSend'); await page.wait_for_timeout(200)
        mt = await page.evaluate("window.__mailto[0]||''")
        from urllib.parse import unquote
        check('e-mail de recuperação abre o Gmail com a chave', mt.startswith('mailto:recupera%40exemplo.com') and key in unquote(mt), unquote(mt)[:90])
        await page.screenshot(path=SHOT + 'v_chave.png')
        await page.click('#rkAck'); await page.click('#rkDone'); await page.wait_for_timeout(400)
        check('app abre depois de criar o PIN', await page.locator('#notes .note').count() >= 3)
        # ---------- criptografia ----------
        enc = await page.evaluate("""async()=>{ const r=await IDB.rawGet('__db__'); const plain=localStorage.getItem('ne.db');
          const bytes=new Uint8Array(r.ct); let s=''; for(const x of bytes) s+=String.fromCharCode(x);
          return {enc:r.enc, plain:!!plain, leak:s.includes('Primeiro dia')}; }""")
        check('notas guardadas criptografadas (sem texto legível)', enc['enc'] == 1 and not enc['plain'] and not enc['leak'], str(enc))
        v = await page.evaluate("JSON.parse(localStorage.getItem('ne.vault'))")
        check('PIN e chave não ficam salvos', '482916' not in str(v) and key.replace('-', '') not in str(v).replace('-', ''))
        # anexo criptografado
        import io
        from PIL import Image
        await page.click('#notes .note >> nth=0'); await page.wait_for_timeout(300)
        im = Image.new('RGB', (300, 200), (200, 50, 50)); buf = io.BytesIO(); im.save(buf, 'JPEG')
        await page.set_input_files('#inCam', files=[{'name': 'image.jpg', 'mimeType': 'image/jpeg', 'buffer': buf.getvalue()}]); await page.wait_for_timeout(500)
        att = await page.evaluate("async()=>{ const a=noteById(cur.noteId).attachments[0]; const r=await IDB.rawGet(a.id); const b=await IDB.get(a.id); return {enc:r.enc, type:b&&b.type, size:b&&b.size}; }")
        check('fotos guardadas criptografadas e abrem no app', att['enc'] == 2 and att['type'] == 'image/jpeg' and att['size'] > 100, str(att))
        # lembrete
        await page.click('#remBtn'); await page.wait_for_timeout(200)
        await page.fill('#rmDay', '2026-11-06'); await page.fill('#rmHour', '08:00'); await page.click('#rmB [data-m="15"]'); await page.click('#rmOk'); await page.wait_for_timeout(200)
        check('lembrete salvo na nota', await page.evaluate("noteById(cur.noteId).remindAt==='2026-11-06T08:00'&&noteById(cur.noteId).remindBefore===15"), await page.inner_text('#remTxt'))
        await page.screenshot(path=SHOT + 'v_editor.png')
        await page.click('#saveBtn'); await page.wait_for_timeout(300)
        check('cartão mostra o lembrete', await page.locator('#notes .meta .rem').count() >= 1)
        await page.screenshot(path=SHOT + 'v_lista.png')
        # ---------- bloquear ao sair ----------
        await page.evaluate("cfg.lockAfter=0; saveCfg(); extLaunch=0")
        await page.evaluate("()=>{ Object.defineProperty(document,'visibilityState',{configurable:true,get:()=>'hidden'}); document.dispatchEvent(new Event('visibilitychange')); }")
        await page.wait_for_timeout(200)
        await page.evaluate("()=>{ Object.defineProperty(document,'visibilityState',{configurable:true,get:()=>'visible'}); document.dispatchEvent(new Event('visibilitychange')); }")
        await page.wait_for_timeout(200)
        check('sair do app bloqueia na hora', await page.is_visible('#pPad'))
        await pin(page, '482916'); await page.wait_for_timeout(1500)
        check('PIN certo desbloqueia', not await page.is_visible('#secScr'))
        # ---------- reabrir: PIN, erros e espera ----------
        await page.reload(); await page.wait_for_timeout(600)
        check('reabrir o app pede PIN', await page.is_visible('#pPad'))
        for i in range(5):
            await pin(page, '000111'); await page.wait_for_timeout(1300)
        msg = await page.inner_text('#pMsg')
        await page.wait_for_timeout(1600)
        msg2 = await page.inner_text('#pMsg')
        check('5 erros bloqueiam com espera', 'Tente de novo em' in msg2 and await page.is_disabled('#pPad [data-d="1"]'), msg2)
        await page.screenshot(path=SHOT + 'v_espera.png')
        await page.evaluate("Guard.set({fails:5,until:Date.now()-1})"); await page.wait_for_timeout(1200)
        # ---------- esqueci o PIN ----------
        await page.click('[data-act="forgot"]'); await page.wait_for_timeout(200)
        await page.fill('#fgKey', 'AAAA-BBBB-CCCC-DDDD-EEEE'); await page.click('#fgGo')
        check('chave errada é recusada', await wait_text(page, '#fgMsg', 'incorreta'))
        await page.evaluate("Guard.ok()")
        await page.fill('#fgKey', key.lower().replace('-', ' ')); await page.click('#fgGo')
        await page.wait_for_selector('#pPad', timeout=15000); await page.wait_for_timeout(200)
        await pin(page, '735102'); await pin(page, '735102'); await page.wait_for_timeout(2000)
        check('chave de recuperação cria PIN novo e abre o app', not await page.is_visible('#secScr') and await page.locator('#notes .note').count() >= 3)
        await page.reload(); await page.wait_for_timeout(600)
        await pin(page, '735102'); await page.wait_for_timeout(1800)
        check('PIN novo funciona depois de reabrir', not await page.is_visible('#secScr'))
        check('notas continuam lá depois de tudo', await page.evaluate("liveNotes().some(n=>n.remindAt==='2026-11-06T08:00')"))
        # ---------- ajustes ----------
        await page.click('#setBtn'); await page.wait_for_timeout(200)
        st = await page.inner_text('#sheet')
        check('ajustes com segurança, privacidade e versão', all(x in st for x in ['Trocar PIN', 'Chave e e-mail de recuperação', 'Cancelar inscrição', 'Política de Privacidade', 'versão 1.9.0', 'Ajuda e contato']))
        await page.screenshot(path=SHOT + 'v_ajustes.png', full_page=False)
        await page.click('#sProt'); await page.wait_for_timeout(200)
        check('proteções ativas mostram o gerente de requisições', 'por minuto' in await page.inner_text('#sheet'))
        await page.keyboard.press('Escape')
        # ---------- cancelar inscrição ----------
        await page.click('#setBtn'); await page.wait_for_timeout(200); await page.click('#sCancel'); await page.wait_for_timeout(200)
        await page.screenshot(path=SHOT + 'v_cancelar.png')
        check('cancelar exige confirmação', await page.is_disabled('#cxGo'))
        await page.click('#cxOk'); await page.fill('#cxType', 'cancelar'); await page.click('#cxGo'); await page.wait_for_timeout(200)
        await pin(page, '735102'); await page.wait_for_timeout(2500)
        check('cancelamento explica o que foi apagado', 'Inscrição cancelada' in await page.inner_text('#secScr'))
        left = await page.evaluate("Object.keys(localStorage).filter(k=>k.startsWith('ne.'))")
        check('nada fica no celular depois de cancelar', left == [], str(left))
        await page.click('#cxEnd'); await page.wait_for_timeout(700)
        check('depois de cancelar, volta para os termos', await page.is_visible('#terms'))
        check('sem erros de script', not errs, ' | '.join(errs[:3]))
        await ctx.close()

        # ---------- biometria (autenticador virtual do Chrome) ----------
        for prf in (True, False):
            ctx, page, errs = await new_page(b, prf=prf)
            await page.goto(URL); await page.wait_for_timeout(500)
            await page.click('#tOk'); await page.click('#tAccept'); await page.wait_for_timeout(200)
            await page.click('#gSkip'); await page.wait_for_timeout(200)
            await page.click('#suGo'); await page.wait_for_timeout(150)
            await pin(page, '482916'); await pin(page, '482916')
            await page.wait_for_selector('#rkVal', timeout=15000)
            await page.click('#rkAck'); await page.click('#rkDone'); await page.wait_for_timeout(300)
            check(f'biometria oferecida ({"PRF" if prf else "sem PRF"})', await page.is_visible('#bYes'))
            await page.click('#bYes'); await page.wait_for_timeout(1500)
            mode = await page.evaluate("Vault.meta.bio&&Vault.meta.bio.mode")
            check(f'biometria ativada no modo esperado', mode == ('prf' if prf else 'device'), str(mode))
            await page.reload(); await page.wait_for_timeout(2500)
            check(f'reabrir desbloqueia com a digital ({mode})', not await page.is_visible('#secScr') and await page.locator('#notes .note').count() >= 3)
            check('sem erros de script (biometria)', not errs, ' | '.join(errs[:3]))
            await ctx.close()
        await b.close()
    print(f'\n{sum(r[1] for r in results)}/{len(results)} verificações passaram')

asyncio.run(main())
