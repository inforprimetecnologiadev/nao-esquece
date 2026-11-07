"""1.6.1: cor das etiquetas que já existem e velocidade do áudio e do vídeo."""
import asyncio, os, sys, io, wave, struct, math, base64
from playwright.async_api import async_playwright

URL = 'http://localhost:8765/index.html'
HERE = os.path.dirname(os.path.abspath(__file__)) + '/'
SCHEME = sys.argv[1] if len(sys.argv) > 1 else 'dark'
results = []
def check(name, ok, extra=''):
    results.append((name, bool(ok))); print(('OK  ' if ok else 'FAIL'), name, extra)

def wav_b64(secs=3):
    b = io.BytesIO(); w = wave.open(b, 'wb'); w.setnchannels(1); w.setsampwidth(2); w.setframerate(8000)
    w.writeframes(b''.join(struct.pack('<h', int(8000 * math.sin(i / 8))) for i in range(8000 * secs))); w.close()
    return base64.b64encode(b.getvalue()).decode()

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
        await page.wait_for_timeout(2600)

        await page.click('#fab'); await page.wait_for_timeout(300)
        await page.fill('#title', 'Teste 1')
        nid = await page.evaluate('cur.noteId')
        # cria a etiqueta "Não esquecer" verde, como no print
        await page.click('#tagBtn'); await page.wait_for_timeout(300)
        await page.fill('#tgName', 'Não esquecer'); await page.click('#tgCol [data-c="verde"]'); await page.click('#tgAdd'); await page.wait_for_timeout(300)
        tid = await page.evaluate("liveTags().find(t=>t.name==='Não esquecer').id")
        tc = await page.evaluate("getComputedStyle(document.querySelector('#tagBtn')).getPropertyValue('--tc').trim()")
        check('botão da etiqueta usa a cor dela (verde)', tc.lower() == '#30a46c', tc)
        # muda a cor pelo lápis
        await page.click(f'[data-tge="{tid}"]'); await page.wait_for_timeout(300)
        check('lápis abre cor e nome da etiqueta', await page.is_visible(f'[data-ted="{tid}"]') and await page.input_value('[data-tgn]') == 'Não esquecer')
        await page.screenshot(path=HERE + f'v161_{SCHEME}_etiqueta.png')
        await page.click('[data-ec="roxo"]'); await page.wait_for_timeout(300)
        col = await page.evaluate(f"tagById('{tid}').color")
        tc = await page.evaluate("getComputedStyle(document.querySelector('#tagBtn')).getPropertyValue('--tc').trim()")
        check('trocar a cor vale para a etiqueta', col == 'roxo', col)
        check('botão da nota muda de cor na hora', tc.lower() == await page.evaluate("tagColor('roxo')"), tc)
        dot = await page.eval_on_selector(f'[data-tg="{tid}"] .dot', "e=>e.style.getPropertyValue('--tc')")
        check('bolinha da lista de etiquetas muda', dot == await page.evaluate("tagColor('roxo')"), dot)
        await page.fill('[data-tgn]', 'Não esquecer nunca'); await page.wait_for_timeout(200)
        check('renomear pelo campo', await page.evaluate(f"tagById('{tid}').name") == 'Não esquecer nunca' and 'Não esquecer nunca' in await page.inner_text('#tagTxt'))
        # urgente fica vermelho e continua mostrando a etiqueta
        await page.click('#tgUrg'); await page.wait_for_timeout(200)
        await page.click('#tgOk'); await page.wait_for_timeout(300)
        chip = await page.inner_text('#tagBtn')
        bg = await page.evaluate("getComputedStyle(document.querySelector('#tagBtn')).color")
        check('Urgente em vermelho junto com a etiqueta', 'Urgente' in chip and 'Não esquecer nunca' in chip and bg != '', chip + ' ' + bg)
        await page.screenshot(path=HERE + f'v161_{SCHEME}_nota.png')

        # áudio: 1x -> 1,5x -> 2x -> 1x
        await page.evaluate(f"""(async()=>{{ const b=await (await fetch('data:audio/wav;base64,{wav_b64()}')).blob(); await addRecording(cur.noteId,b,3); }})()""")
        await page.wait_for_timeout(900)
        sp = '#atts .spd[data-k="aud"]'
        check('áudio tem o botão de velocidade 1×', await page.is_visible(sp) and (await page.inner_text(sp)).strip() == '1×')
        await page.click(sp); await page.wait_for_timeout(150)
        r = await page.evaluate("document.querySelector('#atts audio').playbackRate")
        check('toque: 1,5×', (await page.inner_text(sp)).strip() == '1,5×' and r == 1.5, str(r))
        await page.click(sp); await page.wait_for_timeout(150)
        r = await page.evaluate("document.querySelector('#atts audio').playbackRate")
        check('toque de novo: 2×', (await page.inner_text(sp)).strip() == '2×' and r == 2, str(r))
        await page.screenshot(path=HERE + f'v161_{SCHEME}_audio.png')
        ok = await page.evaluate("(async()=>{ const a=document.querySelector('#atts audio'); a.muted=true; try{ await a.play(); }catch(e){ return 'erro '+e.message; } await new Promise(r=>setTimeout(r,500)); const v=[a.playbackRate,a.currentTime]; a.pause(); return v; })()")
        check('tocando em 2× de verdade', isinstance(ok, list) and ok[0] == 2 and ok[1] > 0.6, str(ok))
        # lembra a velocidade ao reabrir a nota
        await page.click('#backBtn'); await page.wait_for_timeout(400)
        await page.evaluate(f"openNote('{nid}')"); await page.wait_for_timeout(900)
        r = await page.evaluate("document.querySelector('#atts audio').playbackRate")
        check('app lembra a velocidade', (await page.inner_text(sp)).strip() == '2×' and r == 2 and await page.evaluate("cfg.audRate") == 2, str(r))
        await page.click(sp); await page.wait_for_timeout(150)
        check('terceiro toque volta para 1×', (await page.inner_text(sp)).strip() == '1×' and await page.evaluate("document.querySelector('#atts audio').playbackRate") == 1)

        # vídeo no visualizador
        vid = await page.evaluate("""(async()=>{ const n=noteById(cur.noteId); const a={id:uid(),name:'video.mp4',type:'video/mp4',size:10,addedAt:Date.now()};
          await IDB.put(a.id,new Blob([new Uint8Array(10)],{type:'video/mp4'})); n.attachments.push(a); changed(); await renderAtts(); return a.id; })()""")
        await page.evaluate(f"openViewer('{vid}')"); await page.wait_for_timeout(500)
        check('vídeo aberto tem o botão de velocidade', await page.is_visible('#vSpd') and (await page.inner_text('#vSpd')).strip() == '1×')
        seq = []
        for _ in range(4):
            await page.click('#vSpd'); await page.wait_for_timeout(120)
            seq.append([(await page.inner_text('#vSpd')).strip(), await page.evaluate("document.querySelector('#vbody video').playbackRate")])
        check('vídeo: 1,5× → 2× → 0,5× → 1×', seq == [['1,5×', 1.5], ['2×', 2], ['0,5×', 0.5], ['1×', 1]], str(seq))
        await page.click('#vSpd'); await page.wait_for_timeout(120)
        await page.screenshot(path=HERE + f'v161_{SCHEME}_video.png')
        await page.click('#vClose'); await page.wait_for_timeout(300)
        # foto não mostra o botão
        img = await page.evaluate("""(async()=>{ const n=noteById(cur.noteId); const c=document.createElement('canvas'); c.width=c.height=20; const b=await new Promise(r=>c.toBlob(r,'image/png'));
          const a={id:uid(),name:'foto.png',type:'image/png',size:b.size,addedAt:Date.now()}; await IDB.put(a.id,b); n.attachments.push(a); changed(); await renderAtts(); return a.id; })()""")
        await page.evaluate(f"openViewer('{img}')"); await page.wait_for_timeout(400)
        check('foto não tem botão de velocidade', not await page.is_visible('#vSpd'))
        await page.click('#vClose'); await page.wait_for_timeout(300)

        # apagar etiqueta pelo lápis (dois toques)
        await page.click('#tagBtn'); await page.wait_for_timeout(300)
        await page.click(f'[data-tge="{tid}"]'); await page.wait_for_timeout(200)
        await page.click('[data-tgd]'); await page.wait_for_timeout(150)
        check('primeiro toque pede confirmação', 'Toque de novo' in await page.inner_text('[data-tgd]') and await page.evaluate(f"!!tagById('{tid}')"))
        await page.click('[data-tgd]'); await page.wait_for_timeout(300)
        check('segundo toque apaga a etiqueta da nota', await page.evaluate(f"!tagById('{tid}') && !(noteById('{nid}').tags||[]).length"))
        await page.click('#tgOk'); await page.wait_for_timeout(300)
        check('sem erros de script', not errors, ' | '.join(errors[:3]))
        await b.close()
    fails = [r for r in results if not r[1]]
    print(f'\n{len(results)-len(fails)}/{len(results)} verificações passaram')

asyncio.run(main())
