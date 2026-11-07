"""Tema claro: barra de abas, calendário, relatório e assinatura (prints para conferir)."""
import asyncio, os, sys
from playwright.async_api import async_playwright

URL = 'http://localhost:8765/index.html'
HERE = os.path.dirname(os.path.abspath(__file__)) + '/'
SCHEME = sys.argv[1] if len(sys.argv) > 1 else 'light'

async def main():
    async with async_playwright() as p:
        b = await p.chromium.launch(executable_path='/usr/bin/google-chrome')
        ctx = await b.new_context(viewport={'width': 390, 'height': 844}, device_scale_factor=2, is_mobile=True, has_touch=True, locale='pt-BR', timezone_id='America/Campo_Grande', color_scheme=SCHEME)
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
        # dados de exemplo
        await page.evaluate("""(()=>{
          const now=Date.now(), d=new Date(); const iso=x=>{const t=new Date(x);t.setSeconds(0,0);return t.toISOString();};
          const mk=(t,o)=>Object.assign({id:'x'+Math.random().toString(36).slice(2),title:t,html:'',folder:null,createdAt:now,updatedAt:now,attachments:[]},o);
          db.notes.push(mk('Visita ao cliente',{remindAt:iso(now+3*3600e3),html:'Levar o contrato assinado'}));
          db.notes.push(mk('Trocar toner da impressora',{done:true,doneAt:now-3600e3,html:'Sala 2'}));
          db.notes.push(mk('Pagar boleto',{remindAt:iso(now-26*3600e3),urgent:true}));
          db.notes.push(mk('Reunião semanal',{remindAt:iso(now+2*86400e3)}));
          persist(); renderFolders(); renderList();
        })()""")
        await page.wait_for_timeout(400)
        tag = SCHEME
        await page.screenshot(path=HERE + f'luz_{tag}_notas.png')
        for t in ('hoje', 'cal', 'rel'):
            await page.click(f'#bnav [data-tab="{t}"]'); await page.wait_for_timeout(500)
            await page.screenshot(path=HERE + f'luz_{tag}_{t}.png')
        await page.click('#bnav [data-tab="notas"]'); await page.wait_for_timeout(300)
        await page.click('#fab'); await page.wait_for_timeout(300)
        await page.fill('#title', 'Entrega')
        await page.click('#attBtn'); await page.wait_for_timeout(300)
        await page.click('#aSig'); await page.wait_for_timeout(500)
        await page.fill('#dName', 'Maria Souza')
        await page.screenshot(path=HERE + f'luz_{tag}_assinatura.png')
        print('erros:', errors)
        await b.close()

asyncio.run(main())
