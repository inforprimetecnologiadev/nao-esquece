"""Aba Tarefas (1.6), no navegador: listas, concluir, estrela, ordenar, subtarefas, arrastar, Hoje/Calendário/Relatório, Drive."""
import asyncio, os, sys, json
from playwright.async_api import async_playwright

URL = 'http://localhost:8765/index.html'
HERE = os.path.dirname(os.path.abspath(__file__)) + '/'
SCHEME = sys.argv[1] if len(sys.argv) > 1 else 'light'
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
        await page.wait_for_timeout(2600)  # some o aviso "Pronto"

        tabs = await page.eval_on_selector_all('#bnav [data-tab]', 'e=>e.map(x=>x.dataset.tab)')
        check('barra de baixo com a aba Tarefas', tabs == ['notas', 'tarefas', 'hoje', 'cal', 'rel'], str(tabs))
        await page.click('#bnav [data-tab="tarefas"]'); await page.wait_for_timeout(400)
        check('lista começa vazia com desenho', 'Nenhuma tarefa ainda' in await page.inner_text('#notes') and await page.is_visible('.tkart'))
        check('botão de baixo vira Nova tarefa', 'Nova tarefa' in await page.inner_text('#fab'))
        await page.screenshot(path=HERE + f'tk_{SCHEME}_vazia.png')

        async def add(title, date=None, time=None, subs=(), details=None):
            await page.click('#fab'); await page.wait_for_timeout(350)
            await page.fill('#tdT', title)
            if details:
                await page.click('[data-qa="tdD"]'); await page.fill('#tdD', details)
            if date:
                await page.click('[data-qa="tdW"]'); await page.fill('#tdDay', date)
                if time: await page.fill('#tdHour', time)
                await page.wait_for_timeout(450)
            for i, s in enumerate(subs):
                if i == 0: await page.click('[data-qa="tdS"]')
                else: await page.click('#tdSubAdd')
                await page.wait_for_timeout(80)
                await page.fill('.tdsub:last-child input', s)
            await page.click('#tdOk'); await page.wait_for_timeout(400)

        await add('Ir ao mercado'); await add('Ir no médico'); await add('Ir ao banheiro')
        names = await page.eval_on_selector_all('#tkRows .tkgrp .tk:not(.sub) .tkt', 'e=>e.map(x=>x.textContent)')
        check('tarefas novas entram no topo', names == ['Ir ao banheiro', 'Ir no médico', 'Ir ao mercado'], str(names))
        check('contador na lista', (await page.inner_text('#tkTabs [data-tl="tl-main"] b')) == '3')
        check('contador na aba Tarefas da barra', (await page.evaluate("(b=>b&&!b.hidden?b.textContent:'')(document.querySelector('.bt[data-tab=tarefas] .bdg'))")) in ('3', ''), 'badge só se houver')

        # concluir
        mid = await page.evaluate("liveTasks().find(t=>t.title==='Ir ao mercado').id")
        med = await page.evaluate("liveTasks().find(t=>t.title==='Ir no médico').id")
        ban = await page.evaluate("liveTasks().find(t=>t.title==='Ir ao banheiro').id")
        await page.click(f'[data-tkc="{mid}"]'); await page.wait_for_timeout(800)
        txt = await page.inner_text('#notes')
        check('concluída vai para Concluídas (1)', 'Concluídas (1)' in txt and 'Concluída em:' in txt, txt[-120:])
        check('aviso com Desfazer', 'Desfazer' in await page.eval_on_selector('#toast', 'e=>e.textContent'))
        await page.click('#toast button'); await page.wait_for_timeout(300)
        check('Desfazer volta para pendentes', await page.evaluate(f"!taskById('{mid}').done"))
        await page.click(f'[data-tkc="{mid}"]'); await page.wait_for_timeout(800)
        await page.click(f'[data-tkc="{med}"]'); await page.wait_for_timeout(800)
        check('Concluídas (2), mais nova em cima', 'Concluídas (2)' in await page.inner_text('#notes') and (await page.eval_on_selector_all('.tkdone .tk.done .tkt', 'e=>e.map(x=>x.textContent)'))[0] == 'Ir no médico')
        await page.click('#tkDoneT'); await page.wait_for_timeout(250)
        check('Concluídas recolhe', await page.eval_on_selector_all('.tkdone .tk.done', 'e=>e.length') == 0)
        await page.click('#tkDoneT'); await page.wait_for_timeout(250)
        await page.click(f'[data-tkc="{ban}"]'); await page.wait_for_timeout(800)
        txt = await page.inner_text('#notes')
        check('tudo feito: Todas as tarefas concluídas / Bom trabalho!', 'Todas as tarefas concluídas' in txt and 'Bom trabalho!' in txt)
        await page.screenshot(path=HERE + f'tk_{SCHEME}_feito.png')
        # voltar uma das concluídas
        await page.click(f'.tk.done [data-tkc="{ban}"]'); await page.wait_for_timeout(500)
        check('tocar na concluída volta para pendentes', await page.evaluate(f"!taskById('{ban}').done"))

        # estrela
        await page.click(f'[data-tks="{ban}"]'); await page.wait_for_timeout(300)
        check('estrela marcada', await page.get_attribute(f'[data-tks="{ban}"]', 'aria-pressed') == 'true')
        await page.click('#tkTabs [data-tl="star"]'); await page.wait_for_timeout(400)
        txt = await page.inner_text('#notes')
        check('aba Com estrela mostra a tarefa', 'Com estrela' in txt and 'Ir ao banheiro' in txt and 'Marcadas com estrela recentemente' in txt.replace('MARCADAS COM ESTRELA RECENTEMENTE', 'Marcadas com estrela recentemente'), txt[:200])
        await page.screenshot(path=HERE + f'tk_{SCHEME}_estrela.png')
        await page.click(f'[data-tks="{ban}"]'); await page.wait_for_timeout(300)
        check('sem estrela: tela vazia da estrela', 'Nenhuma tarefa com estrela' in await page.inner_text('#notes'))
        await page.click(f'#tkTabs [data-tl="tl-main"]'); await page.wait_for_timeout(300)
        await page.click(f'[data-tks="{ban}"]'); await page.wait_for_timeout(300)

        # nova lista
        await page.click('#tlNew'); await page.wait_for_timeout(300)
        await page.fill('#nameIn', 'Comprar coisas de casa'); await page.click('#nf .btn.pri'); await page.wait_for_timeout(400)
        lid = await page.evaluate("cfg.tlist")
        check('lista nova criada e aberta', await page.evaluate(f"listById('{lid}').name") == 'Comprar coisas de casa' and await page.get_attribute(f'#tkTabs [data-tl="{lid}"]', 'aria-selected') == 'true')
        await add('Comprar refrigerante'); await add('Comprar amaciante'); await add('Comprar arroz', subs=['Tipo 1', 'Pacote de 5 kg'])
        check('tarefas na lista nova', await page.evaluate(f"liveTasks().filter(t=>t.list==='{lid}').length") == 3)
        check('contador da lista nova', (await page.inner_text(f'#tkTabs [data-tl="{lid}"] b')) == '3')
        subs = await page.evaluate("liveTasks().find(t=>t.title==='Comprar arroz').subs.map(s=>s.title)")
        check('subtarefas salvas', subs == ['Tipo 1', 'Pacote de 5 kg'], str(subs))
        arroz = await page.evaluate("liveTasks().find(t=>t.title==='Comprar arroz')")
        await page.click(f'[data-tksub="{arroz["id"]}|{arroz["subs"][0]["id"]}"]'); await page.wait_for_timeout(300)
        check('marcar subtarefa na lista: 1/2', '1/2' in await page.inner_text(f'.tkgrp[data-g="{arroz["id"]}"]'))
        await page.screenshot(path=HERE + f'tk_{SCHEME}_lista.png')

        # ordenar
        await page.click('#tkSort'); await page.wait_for_timeout(300)
        opts = await page.eval_on_selector_all('#sheet [data-ts] .grow', 'e=>e.map(x=>x.textContent)')
        check('Ordenar por: Minha ordem, Data, Prazo, Estrela, Título', opts == ['Minha ordem', 'Data', 'Prazo', 'Marcadas com estrela', 'Título'], str(opts))
        await page.click('#sheet [data-ts="titulo"]'); await page.wait_for_timeout(300)
        names = await page.eval_on_selector_all('#tkRows .tkgrp > .tk:not(.sub) .tkt', 'e=>e.map(x=>x.textContent)')
        check('ordem por título', names == ['Comprar amaciante', 'Comprar arroz', 'Comprar refrigerante'], str(names))
        await page.click('#tkSort'); await page.wait_for_timeout(300)
        await page.click('#sheet [data-ts="ordem"]'); await page.wait_for_timeout(300)

        # arrastar (mouse): segura a última e leva para o topo
        rows = await page.eval_on_selector_all('#tkRows > .tkgrp', 'e=>e.map(x=>{const r=x.getBoundingClientRect();return [x.dataset.g,r.x+r.width/2,r.y+r.height/2,r.height]})')
        last = rows[-1]
        await page.evaluate("TD.touchAt=0")
        await page.mouse.move(last[1] - 60, last[2]); await page.mouse.down(); await page.wait_for_timeout(600)
        for k in range(1, 16): await page.mouse.move(last[1] - 60, last[2] - (last[2] - rows[0][2] + 10) * k / 15); await page.wait_for_timeout(16)
        await page.mouse.up(); await page.wait_for_timeout(500)
        first = await page.eval_on_selector('#tkRows > .tkgrp', 'e=>e.dataset.g')
        check('segurar e arrastar muda a ordem', first == last[0], f'{first} vs {last[0]}')
        check('arrastar não abre a tarefa', await page.is_hidden('#sheet'))

        # abrir tarefa, editar, mover de lista
        await page.click(f'.tkgrp[data-g="{arroz["id"]}"] .tk:not(.sub) .tkt'); await page.wait_for_timeout(400)
        check('tocar abre os detalhes', await page.is_visible('#tdT') and await page.input_value('#tdT') == 'Comprar arroz')
        await page.screenshot(path=HERE + f'tk_{SCHEME}_detalhe.png')
        await page.fill('#tdD', 'Na promoção do mercado')
        await page.select_option('#tdL', 'tl-main')
        await page.evaluate("closeSheet()"); await page.wait_for_timeout(400)
        t = await page.evaluate(f"taskById('{arroz['id']}')")
        check('editar salva ao fechar e move de lista', t['list'] == 'tl-main' and t['notes'] == 'Na promoção do mercado', str(t)[:150])

        # data de hoje com hora -> Hoje, Calendário e alarme (navegador avisa)
        today = await page.evaluate("dkey(new Date())")
        soon = await page.evaluate("(()=>{const d=new Date(Date.now()+2*3600e3); return String(d.getHours()).padStart(2,'0')+':'+String(d.getMinutes()).padStart(2,'0')})()")
        tom = await page.evaluate("(()=>{const d=new Date(); d.setDate(d.getDate()+1); return dkey(d)})()")
        await page.click(f'#tkTabs [data-tl="tl-main"]'); await page.wait_for_timeout(300)
        same_day = await page.evaluate("new Date(Date.now()+2*3600e3).getDate()===new Date().getDate()")
        await add('Bater o ponto', date=today if same_day else tom, time=soon)
        await add('Entregar relatório', date=tom)
        bp = await page.evaluate("liveTasks().find(t=>t.title==='Bater o ponto')")
        check('tarefa com data e hora', bp.get('date') and bp.get('time') == soon, str(bp)[:120])
        meta = await page.inner_text(f'.tkgrp[data-g="{bp["id"]}"]')
        check('linha mostra quando', ('Hoje' in meta or 'Amanhã' in meta) and soon in meta, meta)
        # prazo vencido
        await page.evaluate("(()=>{ const t=liveTasks().find(x=>x.title==='Entregar relatório'); const d=new Date(); d.setDate(d.getDate()-2); t.deadline=dkey(d); t.updatedAt=Date.now(); changed(); renderList(); })()")
        await page.wait_for_timeout(200)
        check('prazo vencido em vermelho', await page.eval_on_selector_all('.tkm.late', 'e=>e.length') >= 1)
        await page.click('#tkSort'); await page.click('#sheet [data-ts="data"]'); await page.wait_for_timeout(300)
        gh = await page.eval_on_selector_all('#tkRows .tkgh', 'e=>e.map(x=>x.textContent)')
        check('ordem por data separa por dia', 'Sem data' in gh and len(gh) >= 2, str(gh))
        await page.screenshot(path=HERE + f'tk_{SCHEME}_data.png')
        await page.click('#tkSort'); await page.click('#sheet [data-ts="ordem"]'); await page.wait_for_timeout(300)

        if same_day:
            await page.click('#bnav [data-tab="hoje"]'); await page.wait_for_timeout(400)
            check('tarefa de hoje aparece no Hoje', 'Bater o ponto' in await page.inner_text('#notes'))
            await page.screenshot(path=HERE + f'tk_{SCHEME}_hoje.png')
        await page.click('#bnav [data-tab="cal"]'); await page.wait_for_timeout(400)
        await page.evaluate(f"cal.sel='{bp['date']}'; renderList()"); await page.wait_for_timeout(300)
        ctext = await page.inner_text('#notes')
        check('tarefa aparece no Calendário', 'Bater o ponto' in ctext and 'Nova tarefa' in ctext)
        await page.click('#calNewT'); await page.wait_for_timeout(400)
        check('Nova tarefa no dia escolhido do calendário', await page.input_value('#tdDay') == bp['date'])
        await page.evaluate("closeSheet()"); await page.wait_for_timeout(300)
        await page.click('#bnav [data-tab="rel"]'); await page.wait_for_timeout(400)
        rtxt = await page.inner_text('#notes')
        check('Relatório inclui as tarefas concluídas', 'Ir ao mercado' in rtxt and 'Ir no médico' in rtxt, rtxt[:300])
        pdf = await page.evaluate("(async()=>{ const d=repData(); return [d.done.length, d.done.some(n=>n._tk)]; })()")
        check('relatório conta as tarefas', pdf[0] >= 2 and pdf[1], str(pdf))

        # tarefa no Hoje: concluir direto no cartão
        await page.click('#bnav [data-tab="tarefas"]'); await page.wait_for_timeout(300)

        # repetir: concluir cria a próxima
        await page.click(f'.tkgrp[data-g="{bp["id"]}"] .tk:not(.sub) .tkt'); await page.wait_for_timeout(400)
        await page.click('#tdR [data-r="dia"]'); await page.wait_for_timeout(150)
        await page.evaluate("closeSheet()"); await page.wait_for_timeout(300)
        await page.click(f'[data-tkc="{bp["id"]}"]'); await page.wait_for_timeout(800)
        nx = await page.evaluate(f"(()=>{{ const t=taskById('{bp['id']}'); const n=t.nextId&&taskById(t.nextId); return n?[n.date,n.time,n.rep,n.done]:null; }})()")
        check('tarefa que repete: concluir cria a próxima', nx and nx[0] > bp['date'] and nx[1] == soon and nx[2] == 'dia' and nx[3] is False, str(nx))
        check('aviso diz quando é a próxima', 'próxima' in (await page.inner_text('#toast')).lower())

        # menu da lista
        await page.click(f'#tkTabs [data-tl="{lid}"]'); await page.wait_for_timeout(300)
        await page.click('#tkMore'); await page.wait_for_timeout(300)
        mt = await page.inner_text('#sheet')
        check('menu da lista: renomear, enviar, excluir', 'Renomear lista' in mt and 'Enviar a lista' in mt and 'Excluir a lista' in mt)
        await page.click('#lmRen'); await page.wait_for_timeout(300)
        await page.fill('#nameIn', 'Casa'); await page.click('#nf .btn.pri'); await page.wait_for_timeout(300)
        check('renomear lista', 'Casa' in await page.inner_text('#tkTabs'))
        txt = await page.evaluate(f"tkListText(listById('{lid}'))")
        check('texto da lista para o WhatsApp', txt.startswith('*Casa*') and '☐ Comprar refrigerante' in txt, txt)
        await page.click('#tkMore'); await page.wait_for_timeout(300)
        await page.click('#lmDel'); await page.wait_for_timeout(300)
        await page.click('#cYes'); await page.wait_for_timeout(400)
        check('excluir lista volta para Minhas tarefas', await page.evaluate("cfg.tlist") == 'tl-main' and not await page.evaluate(f"!!listById('{lid}')"))
        await page.click('#toast button'); await page.wait_for_timeout(400)
        check('Desfazer traz a lista e as tarefas de volta', await page.evaluate(f"!!listById('{lid}') && liveTasks().filter(t=>t.list==='{lid}').length===2"))

        # Drive: junta as tarefas do outro aparelho, ignora dado estranho
        m = await page.evaluate("""(()=>{ const r={folders:[],notes:[],tags:[],tasks:[{id:'tkR1',list:'tlR',title:'Do outro celular',order:0,createdAt:1,updatedAt:Date.now()+5},{id:'<x>',title:'ruim'},{id:'tkR2',title:'x'.repeat(900),done:true,doneAt:5,date:'2026-13-99',subs:[{id:'s1',title:'a',done:true}]}],tlists:[{id:'tlR',name:'Serviço',order:5,createdAt:1,updatedAt:Date.now()}]};
          const m=merge(db,r); return [m.tasks.length-liveTasks().length-TK().filter(t=>t.deleted).length, m.tlists.some(l=>l.id==='tlR'&&l.name==='Serviço'), (m.tasks.find(t=>t.id==='tkR2')||{}).title.length, !!(m.tasks.find(t=>t.id==='tkR2')||{}).date, m.tasks.some(t=>t.id==='<x>')]; })()""")
        check('Drive: tarefas e listas de outro aparelho entram', m[0] == 2 and m[1], str(m))
        check('Drive: dado estranho é limpo', m[2] == 500 and m[3] is False and m[4] is False, str(m))
        js = await page.evaluate("JSON.stringify(Object.keys(JSON.parse(JSON.stringify({tasks:db.tasks,tlists:db.tlists}))))")
        check('banco tem tarefas e listas', js == '["tasks","tlists"]')

        # tema e barra: 5 abas cabem
        over = await page.evaluate("[...document.querySelectorAll('.bt .bl')].some(e=>e.scrollWidth>e.clientWidth+1)")
        check('nomes das 5 abas cabem na barra', not over)
        check('sem erros de script', not errors, ' | '.join(errors[:3]))
        await b.close()
    fails = [r for r in results if not r[1]]
    print(f'\n{len(results)-len(fails)}/{len(results)} verificações passaram')

asyncio.run(main())
