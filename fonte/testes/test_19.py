"""1.9: temporizador "daqui a…" (navegador)."""
import asyncio, os, sys, datetime
from playwright.async_api import async_playwright

URL = 'http://localhost:8765/index.html'
HERE = os.path.dirname(os.path.abspath(__file__)) + '/'
SCHEME = sys.argv[1] if len(sys.argv) > 1 else 'dark'
results = []
def check(name, ok, extra=''):
    results.append((name, bool(ok))); print(('OK  ' if ok else 'FAIL'), name, extra)

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

        await page.click('#bnav [data-tab="hoje"]'); await page.wait_for_timeout(300)
        check('Hoje tem o botão Daqui a…', await page.is_visible('#tmNew'))
        await page.click('#tmNew'); await page.wait_for_timeout(500)
        check('abre a escolha do tempo com horas, minutos e segundos', await page.locator('#sheet .wsc').count() == 3 and await page.is_visible('#tmName'))
        await page.click('#tmP [data-p="10"]'); await page.wait_for_timeout(700)
        end_txt = await page.inner_text('#tmEnd')
        exp = await page.evaluate("hms(now()+600000)")
        check('atalho 00:10:00 mostra a hora em que vai tocar', 'daqui a 10 min' in end_txt and exp[:5] in end_txt, end_txt)
        # rolar a roda dos minutos até 40
        await page.evaluate("document.querySelector('#wM').scrollTop=40*52"); await page.wait_for_timeout(500)
        end_txt = await page.inner_text('#tmEnd')
        exp = await page.evaluate("hms(now()+2400000)")
        check('rolar os minutos para 40: toca daqui a 40 min', 'daqui a 40 min' in end_txt and exp[:5] in end_txt and await page.get_attribute('#wM', 'aria-valuenow') == '40', end_txt)
        await page.fill('#tmName', 'Reunião com o cliente')
        await page.screenshot(path=HERE + f'tm_{SCHEME}_escolher.png')
        t0 = await page.evaluate('now()')
        await page.click('#tmGo'); await page.wait_for_timeout(500)
        tk = await page.evaluate("liveTasks().find(t=>t.title==='Reunião com o cliente')")
        end = tk and tk.get('timer', {}).get('end')
        check('vira uma tarefa com alarme daqui a 40 min (com segundos)', tk and tk['timer']['auto'] and abs(end - (t0 + 2400000)) < 2500 and len(tk['time']) == 8, str(tk)[:200])
        at = await page.evaluate(f"localSec({end})")
        check('data e hora da tarefa batem com o fim do temporizador', tk['date'] + 'T' + tk['time'] == at)
        check('aviso diz a hora certa', 'Toca às' in await page.eval_on_selector('#toast', 'e=>e.textContent'))
        card = await page.inner_text('.tmcard.big')
        check('contagem regressiva grande no Hoje', 'Reunião com o cliente' in card and ('39:5' in card or '40:00' in card) and '40 min' in card, card)
        v1 = await page.inner_text('.tmcard.big .tmin b'); await page.wait_for_timeout(2200)
        v2 = await page.inner_text('.tmcard.big .tmin b')
        check('o tempo vai descendo', v1 != v2, f'{v1} → {v2}')
        await page.screenshot(path=HERE + f'tm_{SCHEME}_rodando.png')
        # pausar e continuar
        await page.click('.tmcard.big [data-tma="pause"]'); await page.wait_for_timeout(500)
        tk = await page.evaluate(f"taskById('{tk['id']}')")
        check('pausar: tira o alarme e guarda quanto falta', 'time' not in tk and 2300000 < tk['timer']['left'] < 2400000 and await page.locator('.tmcard.paused').count() == 1, str(tk.get('timer')))
        p1 = await page.inner_text('.tmcard .tmin b'); await page.wait_for_timeout(1500)
        check('pausado o tempo não anda', await page.inner_text('.tmcard .tmin b') == p1)
        await page.screenshot(path=HERE + f'tm_{SCHEME}_pausado.png')
        await page.click('.tmcard [data-tma="resume"]'); await page.wait_for_timeout(500)
        tk2 = await page.evaluate(f"taskById('{tk['id']}')")
        now_ = await page.evaluate('now()')
        check('continuar: volta de onde parou', abs(tk2['timer']['end'] - (now_ + tk['timer']['left'])) < 2000 and tk2['date'] + 'T' + tk2['time'] == await page.evaluate(f"localSec({tk2['timer']['end']})"))
        await page.click('.tmcard [data-tma="plus"]'); await page.wait_for_timeout(400)
        tk3 = await page.evaluate(f"taskById('{tk['id']}')")
        check('+1 min soma um minuto', abs(tk3['timer']['end'] - tk2['timer']['end'] - 60000) < 1500 and tk3['timer']['dur'] == 2460000)
        # tarefa na lista mostra a contagem
        await page.click('#bnav [data-tab="tarefas"]'); await page.wait_for_timeout(400)
        meta = await page.inner_text(f'.tkgrp[data-g="{tk["id"]}"]')
        check('lista de tarefas mostra a contagem regressiva', ':' in meta and await page.locator(f'.tkgrp[data-g="{tk["id"]}"] [data-cd]').count() == 1, meta)
        # cancelar e desfazer
        await page.click('#bnav [data-tab="hoje"]'); await page.wait_for_timeout(300)
        await page.click('.tmcard [data-tma="cancel"]'); await page.wait_for_timeout(300)
        check('cancelar apaga o temporizador avulso', await page.evaluate(f"!taskById('{tk['id']}')||taskById('{tk['id']}').deleted===true") and await page.locator('.tmcard').count() == 0)
        await page.click('#toast button'); await page.wait_for_timeout(300)
        check('Desfazer traz de volta', await page.locator('.tmcard').count() == 1)
        await page.click('.tmcard [data-tma="cancel"]'); await page.wait_for_timeout(300)

        # nota: lembrete › Daqui a… de 3 segundos
        await page.click('#bnav [data-tab="notas"]'); await page.wait_for_timeout(300)
        await page.click('#fab'); await page.wait_for_timeout(300)
        await page.fill('#title', 'Tirar o bolo do forno')
        nid = await page.evaluate('cur.noteId')
        await page.click('#remBtn'); await page.wait_for_timeout(300)
        check('lembrete tem o atalho Daqui a…', await page.is_visible('#rmQ [data-q="tm"]'))
        await page.click('#rmQ [data-q="tm"]'); await page.wait_for_timeout(600)
        await page.evaluate("document.querySelector('#wH').scrollTop=0; document.querySelector('#wM').scrollTop=0; document.querySelector('#wS').scrollTop=3*52"); await page.wait_for_timeout(500)
        check('3 segundos', 'daqui a 3 s' in await page.inner_text('#tmEnd'), await page.inner_text('#tmEnd'))
        await page.click('#tmGo'); await page.wait_for_timeout(400)
        n = await page.evaluate(f"noteById('{nid}')")
        check('nota ganha lembrete com segundos e temporizador', len(n['remindAt']) == 19 and n['timer']['dur'] == 3000, str(n.get('remindAt')) + str(n.get('timer')))
        chip = await page.inner_text('#remTxt')
        check('botão do lembrete mostra a contagem', '⏳' in chip and '00:0' in chip, chip)
        await page.screenshot(path=HERE + f'tm_{SCHEME}_nota.png')
        await page.wait_for_timeout(4200)
        chip2 = await page.inner_text('#remTxt')
        check('acabou: botão volta a mostrar o lembrete', '⏳' not in chip2, chip2)
        await page.click('#backBtn'); await page.wait_for_timeout(300)
        await page.click('#bnav [data-tab="hoje"]'); await page.wait_for_timeout(500)
        txt = await page.inner_text('#notes')
        check('Hoje mostra "Acabou às" com OK', 'Acabou às' in txt and 'Tirar o bolo do forno' in txt, txt[:200])
        await page.screenshot(path=HERE + f'tm_{SCHEME}_acabou.png')
        await page.click('.tmcard.done [data-tma="ok"]'); await page.wait_for_timeout(300)
        n = await page.evaluate(f"noteById('{nid}')")
        check('OK limpa o temporizador e o lembrete', 'timer' not in n and 'remindAt' not in n)

        # tarefa existente: Daqui a… na janela da tarefa
        await page.click('#bnav [data-tab="tarefas"]'); await page.wait_for_timeout(300)
        await page.click('#fab'); await page.wait_for_timeout(300); await page.fill('#tdT', 'Ligar para o banco'); await page.click('#tdOk'); await page.wait_for_timeout(400)
        tid = await page.evaluate("liveTasks().find(t=>t.title==='Ligar para o banco').id")
        await page.click(f'.tkgrp[data-g="{tid}"] .tk:not(.sub) .tkt'); await page.wait_for_timeout(400)
        if not await page.is_visible('#tdQ'): await page.click('[data-qa="tdW"]')
        await page.click('#tdQ [data-q="timer"]'); await page.wait_for_timeout(600)
        await page.click('#tmP [data-p="15"]'); await page.wait_for_timeout(300); await page.click('#tmGo'); await page.wait_for_timeout(400)
        t = await page.evaluate(f"taskById('{tid}')")
        check('janela da tarefa: Daqui a 15 min', t['timer']['dur'] == 900000 and not t['timer'].get('auto') and len(t['time']) == 8, str(t))
        # abrir e fechar a tarefa não estraga os segundos
        await page.click(f'.tkgrp[data-g="{tid}"] .tk:not(.sub) .tkt'); await page.wait_for_timeout(400)
        await page.evaluate("closeSheet()"); await page.wait_for_timeout(400)
        t2 = await page.evaluate(f"taskById('{tid}')")
        check('abrir e fechar a tarefa mantém o temporizador', t2['time'] == t['time'] and await page.evaluate(f"!!timerOf(taskById('{tid}'),true)"))
        # mudar a hora na mão vira lembrete comum
        await page.evaluate(f"(()=>{{ const x=taskById('{tid}'); x.time='23:59'; x.updatedAt=now(); changed(); renderList(); }})()")
        check('mudar a hora na mão: vira lembrete comum', not await page.evaluate(f"timerOf(taskById('{tid}'),true)"))
        # Drive: guarda o temporizador e a hora com segundos; descarta temporizador estranho
        m = await page.evaluate("""(()=>{ const r={folders:[],notes:[{id:'nR1',title:'x',html:'',createdAt:1,updatedAt:Date.now()+9,remindAt:'2030-01-02T03:04:05',timer:{dur:60000,end:1,label:'a'.repeat(200)}},{id:'nR2',title:'y',html:'',createdAt:1,updatedAt:Date.now()+9,timer:{dur:-5}}],tags:[],tasks:[{id:'tkR9',title:'t',createdAt:1,updatedAt:Date.now()+9,date:'2030-01-02',time:'03:04:05',timer:{dur:5000,left:3000,auto:true}}],tlists:[]};
          const m=merge(db,r); const a=m.notes.find(n=>n.id==='nR1'), b2=m.notes.find(n=>n.id==='nR2'), t=m.tasks.find(x=>x.id==='tkR9'); return [a.remindAt,a.timer&&a.timer.label.length,'timer' in b2,t.time,t.timer&&t.timer.left]; })()""")
        check('Drive guarda temporizador e segundos', m == ['2030-01-02T03:04:05', 80, False, '03:04:05', 3000], str(m))
        check('sem erros de script', not errors, ' | '.join(errors[:3]))
        await b.close()
    fails = [r for r in results if not r[1]]
    print(f'\n{len(results)-len(fails)}/{len(results)} verificações passaram')

asyncio.run(main())
