import asyncio, sys
from playwright.async_api import async_playwright

URL = 'http://localhost:8765/index.html'
SHOT = '/tmp/claude-0/-home-claude/2130d5ca-9358-5b17-903f-31c60689c27a/scratchpad/'
results = []
def check(name, ok, extra=''):
    results.append((name, bool(ok), extra))
    print(('OK  ' if ok else 'FAIL'), name, extra)

async def main():
    async with async_playwright() as p:
        b = await p.chromium.launch(executable_path='/usr/bin/google-chrome', args=['--use-fake-ui-for-media-stream', '--use-fake-device-for-media-stream', '--autoplay-policy=no-user-gesture-required'])
        ctx = await b.new_context(viewport={'width': 390, 'height': 844}, device_scale_factor=2, is_mobile=True, has_touch=True, locale='pt-BR', timezone_id='America/Campo_Grande', color_scheme='dark')
        await ctx.grant_permissions(['microphone'], origin='http://localhost:8765')
        await ctx.add_init_script("Object.defineProperty(Navigator.prototype,'webdriver',{get:()=>false});")
        page = await ctx.new_page()
        errors = []
        page.on('pageerror', lambda e: errors.append(str(e)))
        page.on('console', lambda m: errors.append('console: ' + m.text) if m.type == 'error' else None)
        await page.route('https://fonts.googleapis.com/**', lambda r: r.abort())
        await page.route('https://fonts.gstatic.com/**', lambda r: r.abort())
        await page.goto(URL)
        await page.wait_for_timeout(400)
        await page.click('#tOk'); await page.click('#tAccept'); await page.wait_for_timeout(200)
        check('tela de entrada aparece', await page.is_visible('#gate'))
        await page.click('#gSkip'); await page.wait_for_timeout(200)
        await page.click('#suGo'); await page.wait_for_timeout(150)
        for _ in range(2):
            for d in '482916': await page.click(f'#pPad [data-d="{d}"]')
            await page.wait_for_timeout(150)
        await page.wait_for_selector('#rkVal', timeout=15000)
        await page.click('#rkAck'); await page.click('#rkDone'); await page.wait_for_timeout(400)
        await page.wait_for_timeout(300)
        chips = await page.eval_on_selector_all('#chips .chip', 'els=>els.map(e=>e.textContent.trim())')
        check('chips com Pendentes/Concluídas/Lixeira', any('Pendentes' in c for c in chips) and any('Concluídas' in c for c in chips) and any('Lixeira' in c for c in chips), str(chips))
        n_cards = await page.locator('#notes .note').count()
        check('4 notas de exemplo', n_cards == 4, str(n_cards))
        await page.screenshot(path=SHOT + 's2_lista.png')

        # concluir pelo círculo
        first = page.locator('#notes .note').nth(1)
        title = (await first.locator('h3').inner_text()).strip()
        await first.locator('.nchk').click()
        await page.wait_for_timeout(200)
        done_count = await page.evaluate("liveNotes().filter(n=>n.done).length")
        check('marcar concluída pelo círculo', done_count == 2, title)
        toast = await page.inner_text('#toast')
        check('aviso com data e hora + Desfazer', 'Concluída em' in toast and 'às' in toast and 'DESFAZER' in toast.upper(), toast)
        await page.click('#toast button')
        await page.wait_for_timeout(200)
        check('desfazer conclusão', await page.evaluate("liveNotes().filter(n=>n.done).length") == 1)

        # ver concluídas
        await page.click('#chips .chip[data-f="done"]')
        await page.wait_for_timeout(200)
        check('aba Concluídas mostra 1', await page.locator('#notes .note').count() == 1)
        await page.click('#chips .chip[data-f="all"]')
        await page.wait_for_timeout(200)

        # selecionar segurando o dedo
        box = await page.locator('#notes .note').nth(2).bounding_box()
        await page.mouse.move(box['x'] + box['width'] / 2, box['y'] + 20)
        await page.mouse.down(); await page.wait_for_timeout(650); await page.mouse.up()
        await page.wait_for_timeout(200)
        check('segurar abre seleção', await page.is_visible('#selBar') and await page.is_visible('#selAct'))
        await page.locator('#notes .note').nth(3).click()
        await page.wait_for_timeout(150)
        cnt = await page.inner_text('#selCount')
        check('selecionar 2 notas', '2 selecionadas' in cnt, cnt)
        await page.screenshot(path=SHOT + 's3_selecao.png')
        await page.click('#aTrash')
        await page.wait_for_timeout(300)
        check('2 notas na lixeira', await page.evaluate("trashNotes().length") == 2)
        check('seleção fecha', not await page.is_visible('#selBar'))
        await page.click('#chips .chip[data-f="trash"]')
        await page.wait_for_timeout(200)
        check('lixeira lista 2', await page.locator('#notes .note').count() == 2)
        await page.screenshot(path=SHOT + 's4_lixeira.png')
        await page.click('#selStart')
        await page.click('#selAll')
        await page.wait_for_timeout(100)
        await page.click('#aRestore')
        await page.wait_for_timeout(300)
        check('restaurar várias da lixeira', await page.evaluate("trashNotes().length") == 0 and await page.evaluate("liveNotes().length") == 4)

        # nova nota com checklist, salvar
        await page.click('#chips .chip[data-f="all"]')
        await page.click('#fab')
        await page.wait_for_timeout(300)
        await page.fill('#title', 'Deveres de amanhã')
        await page.click('#ed')
        await page.keyboard.type('Ir para o trabalho')
        await page.click('#ckBtn')
        await page.keyboard.press('End')
        await page.keyboard.press('Enter')
        await page.keyboard.type('Levar o crachá')
        await page.wait_for_timeout(200)
        html = await page.inner_html('#ed')
        check('checklist criado', 'class="ck"' in html and html.count('data-done') == 2, html[:200])
        li = page.locator('#ed ul.ck > li').first
        lb = await li.bounding_box()
        await page.mouse.click(lb['x'] + 12, lb['y'] + lb['height'] / 2)
        await page.wait_for_timeout(200)
        check('marcar item do checklist', await li.get_attribute('data-done') == '1')
        await page.click('#doneBtn')
        await page.wait_for_timeout(150)
        check('marcar a nota como concluída no editor', 'Concluída' in await page.inner_text('#doneBtn'))
        await page.click('#doneBtn')
        etime = await page.inner_text('#edTime')
        check('editor mostra data e hora', 'às' in etime and '/' in etime, etime)

        # gravar áudio (microfone falso do Chrome)
        await page.click('#micBtn')
        await page.wait_for_timeout(200)
        await page.click('#mRec')
        await page.wait_for_timeout(2300)
        check('barra de gravação visível', await page.is_visible('#recbar'), await page.inner_text('#rtime'))
        await page.screenshot(path=SHOT + 's5_gravando.png')
        await page.click('#rStop')
        await page.wait_for_timeout(800)
        auds = await page.evaluate("noteById(cur.noteId).attachments.filter(a=>a.type.startsWith('audio')).map(a=>a.name+'|'+a.duration+'|'+a.type)")
        check('áudio salvo na nota', len(auds) == 1, str(auds))
        check('player de áudio na nota', await page.locator('#atts audio').count() == 1)
        await page.screenshot(path=SHOT + 's6_editor.png', full_page=False)

        # anexar foto (simula a câmera)
        import base64, io
        from PIL import Image
        im = Image.new('RGB', (1200, 900), (40, 120, 200)); buf = io.BytesIO(); im.save(buf, 'JPEG')
        await page.set_input_files('#inCam', files=[{'name': 'image.jpg', 'mimeType': 'image/jpeg', 'buffer': buf.getvalue()}])
        await page.wait_for_timeout(500)
        names = await page.evaluate("noteById(cur.noteId).attachments.map(a=>a.name)")
        check('foto da câmera com data e hora no nome', any(n.startswith('Foto ') and 'h' in n for n in names), str(names))

        # recuperação ao reabrir o app
        await page.keyboard.press('End')
        await page.click('#ed')
        await page.keyboard.type(' (rascunho)')
        await page.wait_for_timeout(700)
        await page.reload()
        await page.wait_for_timeout(600)
        for d in '482916': await page.click(f'#pPad [data-d="{d}"]')
        await page.wait_for_timeout(2000)
        sheet = await page.inner_text('#sheet') if await page.is_visible('#sheet') else ''
        check('pergunta se quer continuar editando', 'Você estava escrevendo uma nota' in sheet, '')
        check('recuperação mostra data e hora', 'Última alteração em' in sheet and 'às' in sheet, sheet.split('\n')[3:6].__str__())
        await page.screenshot(path=SHOT + 's7_recuperar.png')
        await page.click('#rCont')
        await page.wait_for_timeout(400)
        check('continuar editando reabre a nota', await page.input_value('#title') == 'Deveres de amanhã')

        # botão Voltar do Android fecha o editor sem sair
        await page.go_back()
        await page.wait_for_timeout(400)
        check('Voltar fecha o editor', not await page.evaluate("!!cur.noteId"))
        check('continua no app depois do Voltar', page.url.startswith('http://localhost:8765'))

        # salvar explícito
        await page.click('#fab'); await page.wait_for_timeout(200)
        await page.click('#saveBtn'); await page.wait_for_timeout(150)
        check('salvar nota vazia avisa', 'Escreva algo' in await page.inner_text('#toast'))
        await page.fill('#title', 'Comprar botina')
        await page.dispatch_event('#title', 'input')
        await page.click('#saveBtn'); await page.wait_for_timeout(300)
        t = await page.inner_text('#toast')
        check('Salvar fecha e confirma com data e hora', 'Nota salva' in t and 'às' in t and not await page.evaluate("!!cur.noteId"), t)

        # tema: automático → claro → escuro
        seq = []
        for _ in range(3):
            await page.click('#themeBtn'); await page.wait_for_timeout(100)
            seq.append(await page.evaluate("cfg.theme"))
        check('tema alterna claro/escuro/automático', seq == ['light', 'dark', 'system'], str(seq))
        await page.click('#themeBtn')
        await page.wait_for_timeout(200)
        await page.screenshot(path=SHOT + 's8_lista_clara.png')

        # ajustes e menu da nota
        await page.click('#setBtn'); await page.wait_for_timeout(200)
        st = await page.inner_text('#sheet')
        check('ajustes têm Automático/Claro/Escuro', all(x in st for x in ['Automático', 'Claro', 'Escuro']))
        await page.keyboard.press('Escape')

        check('sem erros de script', not errors, ' | '.join(errors[:5]))
        await b.close()
    fails = [r for r in results if not r[1]]
    print(f'\n{len(results)-len(fails)}/{len(results)} verificações passaram')

asyncio.run(main())
