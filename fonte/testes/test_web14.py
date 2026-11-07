"""Navegador (sem APK): escanear com a câmera e virar PDF, lembrete que repete, etiquetas e tela Hoje."""
import asyncio, io, os
from playwright.async_api import async_playwright
from PIL import Image, ImageDraw

URL = 'http://localhost:8765/index.html'
HERE = os.path.dirname(os.path.abspath(__file__)) + '/'
results = []
def check(name, ok, extra=''):
    results.append((name, bool(ok))); print(('OK  ' if ok else 'FAIL'), name, extra)

def paper_photo(path, n):
    im = Image.new('RGB', (1200, 1600), (120, 110, 100))
    d = ImageDraw.Draw(im); d.rectangle([150, 150, 1050, 1450], fill=(225, 220, 210))
    for i in range(14): d.rectangle([220, 260 + i * 70, 220 + (600 if i % 3 else 800), 280 + i * 70], fill=(40, 40, 50))
    d.text((220, 200), f'Pagina {n}', fill=(0, 0, 0))
    im.save(path, 'JPEG', quality=85)

async def main():
    for k in (1, 2): paper_photo(HERE + f'papel{k}.jpg', k)
    async with async_playwright() as p:
        b = await p.chromium.launch(executable_path='/usr/bin/google-chrome')
        ctx = await b.new_context(viewport={'width': 390, 'height': 844}, device_scale_factor=2, is_mobile=True, has_touch=True, locale='pt-BR', timezone_id='America/Campo_Grande', color_scheme='light')
        await ctx.add_init_script("Object.defineProperty(Navigator.prototype,'webdriver',{get:()=>false});")
        page = await ctx.new_page()
        errors = []
        page.on('pageerror', lambda e: errors.append(str(e)))
        await page.route('https://fonts.**', lambda r: r.abort())
        await page.goto(URL); await page.wait_for_timeout(400)
        await page.click('#tOk'); await page.click('#tAccept'); await page.wait_for_timeout(200)
        await page.click('#gSkip'); await page.wait_for_timeout(200)
        await page.click('#suGo'); await page.wait_for_timeout(150)
        for _ in range(2):
            for d in '482916': await page.click(f'#pPad [data-d="{d}"]')
            await page.wait_for_timeout(150)
        await page.wait_for_selector('#rkVal', timeout=15000)
        await page.click('#rkAck'); await page.click('#rkDone'); await page.wait_for_timeout(500)
        if await page.is_visible('#bNo'): await page.click('#bNo')
        await page.wait_for_timeout(300)
        check('barra de abas embaixo', (await page.eval_on_selector_all('#bnav [data-tab]', 'els=>els.map(e=>e.dataset.tab)')) == ['notas', 'tarefas', 'hoje', 'cal', 'rel'])

        await page.click('#fab'); await page.wait_for_timeout(300)
        await page.fill('#title', 'Contrato do cliente')
        # escanear sem o scanner do Google: câmera + PDF montado no app
        await page.click('#attBtn'); await page.wait_for_timeout(300)
        await page.click('#aScan'); await page.wait_for_timeout(300)
        check('folha de escanear abre', await page.is_visible('#scG') is not None and 'Escanear documento' in await page.inner_text('#sheet'))
        for k in (1, 2):
            await page.set_input_files('#inScan', HERE + f'papel{k}.jpg'); await page.wait_for_timeout(700)
        check('duas páginas na prévia', await page.eval_on_selector_all('#scG img', 'e=>e.length') == 2)
        check('botão Criar PDF com 2 páginas', '2 páginas' in await page.inner_text('#scOk'))
        await page.screenshot(path=HERE + 'web_scan.png')
        await page.click('#scOk'); await page.wait_for_timeout(1500)
        att = await page.evaluate("noteById(cur.noteId).attachments")
        check('PDF criado na nota', att and att[-1]['type'] == 'application/pdf' and att[-1]['pages'] == 2, str(att)[:160])
        head = await page.evaluate("(async()=>{ const b=await IDB.get(noteById(cur.noteId).attachments[0].id); const t=await b.slice(0,8).text(); return [t,b.size]; })()")
        check('arquivo é um PDF de verdade', head[0].startswith('%PDF') and head[1] > 20000, str(head))
        # anexar um PDF do celular
        await page.set_input_files('#inPdf', files=[{'name': 'boleto.pdf', 'mimeType': 'application/pdf', 'buffer': b'%PDF-1.4\n%%EOF\n'}]); await page.wait_for_timeout(500)
        att = await page.evaluate("noteById(cur.noteId).attachments")
        check('PDF do celular anexado', len(att) == 2 and att[1]['name'] == 'boleto.pdf' and att[1]['type'] == 'application/pdf')

        # lembrete que repete na versão do navegador
        await page.click('#remBtn'); await page.wait_for_timeout(300)
        await page.click('#rmR [data-r="semana"]')
        s = await page.inner_text('#rmSum')
        check('navegador também repete', 'Próximo aviso' in s and ('toda' in s or 'todo' in s), s)
        await page.click('#rmOk'); await page.wait_for_timeout(300)
        check('repetição salva', await page.evaluate("noteById(cur.noteId).remindRepeat==='semana'"))
        body = await page.evaluate("evBody(noteById(cur.noteId))")
        check('Google Agenda recebe a regra de repetição', body.get('recurrence') == ['RRULE:FREQ=WEEKLY'], str(body.get('recurrence')))

        # etiqueta e urgente
        await page.click('#tagBtn'); await page.wait_for_timeout(300)
        await page.click('#tgUrg'); await page.fill('#tgName', 'Financeiro'); await page.click('#tgCol [data-c="roxo"]'); await page.click('#tgAdd'); await page.wait_for_timeout(200)
        await page.click('#tgOk'); await page.wait_for_timeout(200)
        await page.click('#backBtn'); await page.wait_for_timeout(300)
        chips = await page.inner_text('#chips')
        check('filtro da etiqueta e Urgentes', 'Financeiro' in chips and 'Urgentes' in chips, chips[:160])
        await page.screenshot(path=HERE + 'web_lista.png')
        check('sem erros de script', not errors, ' | '.join(errors[:3]))
        await b.close()
    fails = [r for r in results if not r[1]]
    print(f'\n{len(results)-len(fails)}/{len(results)} verificações passaram')

asyncio.run(main())
