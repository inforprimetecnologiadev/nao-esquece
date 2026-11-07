"""Simula o Chrome do celular com "Site para computador" ligado:
a página é desenhada com 980 px de largura numa tela de 412 px."""
import asyncio
from playwright.async_api import async_playwright

URL = 'http://localhost:8765/'
SHOT = '/tmp/claude-0/-home-claude/2130d5ca-9358-5b17-903f-31c60689c27a/scratchpad/'
results = []
def check(name, ok, extra=''):
    results.append((name, bool(ok)))
    print(('OK  ' if ok else 'FAIL'), name, extra)

SW, SH = 412, 892          # tela do celular (px de CSS)
VW = 980                   # largura que o Chrome usa no modo computador
VH = round(VW * SH / SW)
DESKTOP_UA = 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Safari/537.36'

async def main():
    async with async_playwright() as p:
        b = await p.chromium.launch(executable_path='/usr/bin/google-chrome', args=['--use-fake-ui-for-media-stream', '--use-fake-device-for-media-stream'])
        ctx = await b.new_context(viewport={'width': VW, 'height': VH}, screen={'width': SW, 'height': SH}, device_scale_factor=1, has_touch=True, user_agent=DESKTOP_UA, locale='pt-BR', color_scheme='dark')
        await ctx.add_init_script("Object.defineProperty(Navigator.prototype,'webdriver',{get:()=>false});")
        page = await ctx.new_page()
        errors = []
        page.on('pageerror', lambda e: errors.append(str(e)))
        await page.route('https://fonts.googleapis.com/**', lambda r: r.abort())
        await page.route('https://fonts.gstatic.com/**', lambda r: r.abort())
        await page.goto(URL + 'index.html'); await page.wait_for_timeout(400)
        z = await page.evaluate('window.__Z')
        check('detecta modo computador no celular', abs(z - VW / SW) < 0.02 and await page.evaluate("document.documentElement.classList.contains('fm')"), f'zoom={z:.3f}')
        await page.screenshot(path=SHOT + 'fm_termos.png')
        await page.click('#tOk'); await page.click('#tAccept'); await page.wait_for_timeout(200)
        await page.click('#gSkip'); await page.wait_for_timeout(200)
        await page.click('#suGo'); await page.wait_for_timeout(150)
        for _ in range(2):
            for d in '482916': await page.click(f'#pPad [data-d="{d}"]')
            await page.wait_for_timeout(150)
        await page.wait_for_selector('#rkVal', timeout=15000)
        await page.screenshot(path=SHOT + 'fm_chave.png')
        await page.click('#rkAck'); await page.click('#rkDone'); await page.wait_for_timeout(500)

        check('layout de celular (sem coluna de pastas)', not await page.is_visible('.side'))
        check('abas de filtro visíveis', await page.is_visible('#chips'))
        check('WIDE() falso no celular', await page.evaluate('!WIDE()'))
        lw = await page.evaluate("document.querySelector('.list').getBoundingClientRect().width")
        check('lista ocupa a tela inteira', abs(lw - VW) < 4, f'{lw:.0f}px')
        sw = await page.evaluate('document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1')
        check('sem rolagem lateral', sw)
        fab = await page.evaluate("(()=>{const r=document.querySelector('#fab').getBoundingClientRect();return [r.right,r.bottom,getComputedStyle(document.querySelector('#fab')).position]})()")
        check('botão Nova nota no canto de baixo, acima das abas', fab[2] == 'fixed' and fab[0] > VW - 120 and fab[1] > VH - 300, str(fab))
        bn = await page.evaluate("(()=>{const r=document.querySelector('#bnav').getBoundingClientRect();return [r.left,r.right,r.bottom]})()")
        check('abas embaixo ocupando a largura', abs(bn[2] - VH) < 4 and bn[0] < 2 and bn[1] > VW - 2, str(bn))
        fs = await page.evaluate("parseFloat(getComputedStyle(document.querySelector('.list h2, .list .ttl, .list header h2')||document.body).fontSize)")
        await page.screenshot(path=SHOT + 'fm_lista.png')

        # ajustes: folha que sobe de baixo
        await page.click('#setBtn'); await page.wait_for_timeout(300)
        r = await page.evaluate("(()=>{const r=document.querySelector('#sheet').getBoundingClientRect();return [r.left,r.right,r.bottom]})()")
        check('Ajustes abre como folha de baixo, largura total', r[0] < 2 and r[1] > VW - 2 and r[2] > VH - 2, str([round(x) for x in r]))
        await page.screenshot(path=SHOT + 'fm_ajustes.png')
        await page.keyboard.press('Escape'); await page.wait_for_timeout(200)

        # nota com checklist; marcar pelo círculo
        await page.click('#fab'); await page.wait_for_timeout(300)
        check('editor abre em tela cheia', await page.is_visible('#ed') and not await page.is_visible('.list'))
        await page.fill('#title', 'Teste celular')
        await page.click('#ed'); await page.keyboard.type('Item um')
        await page.click('#ckBtn'); await page.keyboard.press('End'); await page.keyboard.press('Enter'); await page.keyboard.type('Item dois')
        await page.wait_for_timeout(200)
        li = page.locator('#ed ul.ck > li').first
        lb = await li.bounding_box()
        await page.mouse.click(lb['x'] + 120 * z, lb['y'] + lb['height'] / 2)   # no texto: não marca
        await page.wait_for_timeout(150)
        check('tocar no texto não marca o item', await li.get_attribute('data-done') != '1')
        await page.mouse.click(lb['x'] + 12 * z, lb['y'] + lb['height'] / 2)    # no círculo: marca
        await page.wait_for_timeout(150)
        check('tocar no círculo marca o item', await li.get_attribute('data-done') == '1')
        await page.screenshot(path=SHOT + 'fm_editor.png')

        # Voltar do Android fecha o editor
        await page.go_back(); await page.wait_for_timeout(400)
        check('Voltar fecha o editor e volta à lista', not await page.evaluate('!!cur.noteId') and await page.is_visible('.list'))
        check('sem erros de script', not errors, ' | '.join(errors[:3]))
        await ctx.close()

        # central de ajuda e política no mesmo modo
        ctx = await b.new_context(viewport={'width': VW, 'height': VH}, screen={'width': SW, 'height': SH}, device_scale_factor=1, user_agent=DESKTOP_UA, locale='pt-BR', color_scheme='dark')
        page = await ctx.new_page()
        await page.route('https://fonts.googleapis.com/**', lambda r: r.abort())
        await page.route('https://fonts.gstatic.com/**', lambda r: r.abort())
        await page.goto(URL + 'suporte/'); await page.wait_for_timeout(500)
        check('central: zoom aplicado', await page.evaluate('window.__Z>2'))
        check('central: sem rolagem lateral', await page.evaluate('document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1'))
        check('central: menu do topo no modo celular', not await page.is_visible('.nav a[href="#perguntas"]'))
        h1 = await page.evaluate("parseFloat(getComputedStyle(document.querySelector('.hero h1')).fontSize)")
        check('central: título no tamanho de celular', 30 <= h1 <= 40, f'{h1}px')
        await page.screenshot(path=SHOT + 'fm_suporte.png')
        await page.goto(URL + 'privacidade.html'); await page.wait_for_timeout(300)
        check('política: zoom aplicado', await page.evaluate('window.__Z>2'))
        await ctx.close()

        # computador de verdade continua com 3 colunas
        ctx = await b.new_context(viewport={'width': 1280, 'height': 860}, locale='pt-BR')
        await ctx.add_init_script("Object.defineProperty(Navigator.prototype,'webdriver',{get:()=>false});")
        page = await ctx.new_page()
        await page.route('https://fonts.googleapis.com/**', lambda r: r.abort())
        await page.route('https://fonts.gstatic.com/**', lambda r: r.abort())
        await page.goto(URL + 'index.html'); await page.wait_for_timeout(300)
        check('computador: sem zoom', await page.evaluate('window.__Z===1 && !document.documentElement.classList.contains("fm")'))
        check('computador: layout largo', await page.evaluate('WIDE()'))
        await ctx.close()
        await b.close()
    fails = [r for r in results if not r[1]]
    print(f'\n{len(results)-len(fails)}/{len(results)} verificações passaram')

asyncio.run(main())
