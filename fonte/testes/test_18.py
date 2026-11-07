"""1.8: ditado contínuo (navegador, com reconhecedor falso)."""
import asyncio, os, sys
from playwright.async_api import async_playwright

URL = 'http://localhost:8765/index.html'
HERE = os.path.dirname(os.path.abspath(__file__)) + '/'
SCHEME = sys.argv[1] if len(sys.argv) > 1 else 'dark'
results = []
def check(name, ok, extra=''):
    results.append((name, bool(ok))); print(('OK  ' if ok else 'FAIL'), name, extra)

FAKE = """Object.defineProperty(Navigator.prototype,'webdriver',{get:()=>false});
window.__sr=[]; class FakeSR{ constructor(){ window.__srInst=this; window.__sr.push('new'); } start(){ window.__sr.push('start'); setTimeout(()=>this.onstart&&this.onstart(),10); }
  stop(){ window.__sr.push('stop'); setTimeout(()=>this.onend&&this.onend(),30); } abort(){ window.__sr.push('abort'); } }
window.webkitSpeechRecognition=FakeSR; window.SpeechRecognition=FakeSR;
window.__say=(t,fin)=>{ window.__srInst.onresult({resultIndex:0,results:[{0:{transcript:t},isFinal:!!fin,length:1}]}); };"""

async def main():
    async with async_playwright() as p:
        b = await p.chromium.launch(executable_path='/usr/bin/google-chrome')
        ctx = await b.new_context(viewport={'width': 390, 'height': 844}, device_scale_factor=2, is_mobile=True, has_touch=True, locale='pt-BR', timezone_id='America/Campo_Grande', color_scheme=SCHEME)
        await ctx.add_init_script(FAKE)
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
        await page.wait_for_timeout(2600)

        await page.click('#fab'); await page.wait_for_timeout(300)
        await page.fill('#title', 'Testes')
        await page.click('#ed'); await page.keyboard.type('Fazendo testes')
        nid = await page.evaluate('cur.noteId')
        await page.click('#micBtn'); await page.wait_for_timeout(300)
        check('menu do microfone tem Ditar texto', 'Pausar e Concluir' in await page.inner_text('#sheet'))
        await page.click('#mDit'); await page.wait_for_timeout(400)
        check('abre a janela de ditado do app (sem a do Google)', await page.is_visible('#dict') and 'Ouvindo' in await page.inner_text('#dcState'), await page.inner_text('#dcState'))
        await page.evaluate("__say('comprar pão',false)"); await page.wait_for_timeout(100)
        check('texto aparece enquanto fala', 'comprar pão' in await page.inner_text('#dcText'))
        await page.evaluate("__say('comprar pão vírgula leite e café ponto final',true)"); await page.wait_for_timeout(100)
        check('pontuação falada vira vírgula e ponto', 'Comprar pão, leite e café.' in await page.inner_text('#dcText'), await page.inner_text('#dcText'))
        # silêncio: o reconhecedor encerra sozinho; o app volta a ouvir
        starts = await page.evaluate("__sr.filter(x=>x==='start').length")
        await page.evaluate("__srInst.onend()"); await page.wait_for_timeout(200)
        check('silêncio não para o ditado', await page.evaluate("__sr.filter(x=>x==='start').length") == starts + 1 and 'Ouvindo' in await page.inner_text('#dcState'))
        await page.evaluate("__say('nova linha ligar para o cliente amanhã',true)"); await page.wait_for_timeout(100)
        await page.wait_for_timeout(1300)
        check('cronômetro andando', (await page.inner_text('#dcTime')) not in ('0:00', ''), await page.inner_text('#dcTime'))
        await page.screenshot(path=HERE + f'dit_{SCHEME}_ouvindo.png')
        # pausar
        await page.click('#dcPause'); await page.wait_for_timeout(300)
        check('pausar: botão vira Continuar', 'Continuar' in await page.inner_text('#dcPause') and 'Pausado' in await page.inner_text('#dcState'))
        check('pausado: dá para corrigir o texto', await page.is_visible('#dcEdit') and 'Ligar para o cliente amanhã' in await page.input_value('#dcEdit') and '\n' in await page.input_value('#dcEdit'), repr(await page.input_value('#dcEdit')))
        t1 = await page.inner_text('#dcTime'); await page.wait_for_timeout(1200)
        check('cronômetro para na pausa', await page.inner_text('#dcTime') == t1)
        await page.screenshot(path=HERE + f'dit_{SCHEME}_pausado.png')
        await page.click('#dcEdit'); await page.keyboard.press('End'); await page.keyboard.type(' cedo')
        # continuar
        await page.click('#dcPause'); await page.wait_for_timeout(200)
        check('continuar volta a ouvir de onde parou', 'Ouvindo' in await page.inner_text('#dcState') and 'cedo' in await page.inner_text('#dcText'))
        await page.evaluate("__say('tudo certo ponto de exclamação',true)"); await page.wait_for_timeout(100)
        # concluir
        await page.click('#dcDone'); await page.wait_for_timeout(600)
        html = await page.evaluate(f"noteById('{nid}').html||document.querySelector('#ed').innerHTML")
        edtxt = await page.evaluate("document.querySelector('#ed').innerText")
        check('concluir fecha e coloca o texto na nota', not await page.is_visible('#dict') and 'Fazendo testes Comprar pão, leite e café.' in edtxt and 'Ligar para o cliente amanhã cedo tudo certo!' in edtxt, repr(edtxt))
        check('nova linha vira quebra de linha na nota', '<br>' in await page.evaluate("document.querySelector('#ed').innerHTML"))
        # cancelar pede confirmação e não coloca nada
        before = await page.evaluate("document.querySelector('#ed').innerText")
        await page.click('#micBtn'); await page.wait_for_timeout(200); await page.click('#mDit'); await page.wait_for_timeout(300)
        await page.evaluate("__say('isto não vai',true)"); await page.wait_for_timeout(100)
        await page.click('#dcCancel'); await page.wait_for_timeout(150)
        check('cancelar pede confirmação', 'Descartar?' in await page.inner_text('#dcCancel') and await page.is_visible('#dict'))
        await page.click('#dcCancel'); await page.wait_for_timeout(300)
        check('cancelar descarta', not await page.is_visible('#dict') and await page.evaluate("document.querySelector('#ed').innerText") == before and 'abort' in await page.evaluate('__sr'))
        # Voltar do celular não perde o que foi ditado
        await page.click('#micBtn'); await page.wait_for_timeout(200); await page.click('#mDit'); await page.wait_for_timeout(300)
        await page.evaluate("__say('lembrar da reunião',true)"); await page.wait_for_timeout(100)
        await page.evaluate("history.back()"); await page.wait_for_timeout(700)
        txt2 = (await page.evaluate("document.querySelector('#ed').innerText")).lower()
        check('Voltar guarda o texto ditado', not await page.is_visible('#dict') and 'lembrar da reunião' in txt2, repr(txt2[-60:]))
        check('sem erros de script', not errors, ' | '.join(errors[:3]))
        await b.close()
    fails = [r for r in results if not r[1]]
    print(f'\n{len(results)-len(fails)}/{len(results)} verificações passaram')

asyncio.run(main())
