"""Testa o app como se estivesse dentro do APK: window.NativeApp simulado (mesmos métodos do MainActivity.java)."""
import asyncio, base64, json, os
from playwright.async_api import async_playwright

HERE = os.path.dirname(os.path.abspath(__file__))
src = open(os.path.join(HERE, 'test_drive.py'), encoding='utf-8').read()
ns = {}
exec(src[:src.index('async def device')], ns)   # reaproveita o Drive simulado
drive, CORS = ns['drive'], ns['CORS']

URL = 'http://localhost:8765/index.html'
SHOT = HERE + '/'
results = []
def check(name, ok, extra=''):
    results.append((name, bool(ok))); print(('OK  ' if ok else 'FAIL'), name, extra)

MOCK = r"""
window.__calls=[];
const later=(f)=>setTimeout(f,40);
window.__al={notif:false,channels:true,exact:true,full:true,battery:false,vol:5,cal:false,sdk:34,pending:0,widgets:0};
window.__pdf='JVBERi0xLjQKJSVFT0YK';
window.NativeApp={
  info:()=>JSON.stringify({app:'android',version:'1.9.0',code:10,sdk:34}),
  alarmSet:j=>{ const l=JSON.parse(j); __calls.push(['alarmSet',l]); __al.pending=l.length; return String(l.length); },
  scanDoc:id=>{ __calls.push(['scanDoc']); later(()=>__nativeDone(id,true,JSON.stringify({b64:__pdf,pages:2}))); },
  viewFile:(id,name,mime,b64)=>{ __calls.push(['viewFile',name,mime,atob(b64)]); later(()=>__nativeDone(id,true,'')); },
  widgetPin:()=>{ __calls.push(['widgetPin']); return 'ok'; },
  widgetCount:()=>0,
  ocr:(id,mime,b64)=>{ __calls.push(['ocr',mime]); later(()=>__nativeDone(id,true,'NOTA FISCAL 4521\nEntregar na sala 2')); },
  quickList:()=>JSON.stringify(window.__quick||[]),
  quickData:(id,qid)=>{ later(()=>__nativeDone(id,true,'AAAAGGZ0eXBNNEEg')); },
  quickDone:q=>{ __calls.push(['quickDone',q]); window.__quick=(window.__quick||[]).filter(x=>x.id!==q); },
  quickOpen:m=>__calls.push(['quickOpen',m]),
  voiceCheck:(id,l)=>{ __calls.push(['voiceCheck',l]); later(()=>__nativeDone(id,true,JSON.stringify({api:34,st:window.__vst||'instalado'}))); },
  voiceDownload:l=>{ __calls.push(['voiceDownload',l]); setTimeout(()=>__voice('progresso','35'),150); setTimeout(()=>__voice('progresso','100'),900); setTimeout(()=>{ window.__vst='instalado'; __voice('ok',''); },1100); },
  voiceOpen:w=>{ __calls.push(['voiceOpen',w]); return true; },
  dictBegin:l=>{ __calls.push(['dictBegin',l]); const a=window.__dictAns||'ok'; if(a==='ok') setTimeout(()=>__dict('estado','ouvindo'),30); return a; },
  dictPause:()=>{ __calls.push(['dictPause']); setTimeout(()=>__dict('estado','pausado'),30); },
  dictResume:()=>{ __calls.push(['dictResume']); setTimeout(()=>__dict('estado','ouvindo'),30); },
  dictFinish:()=>{ __calls.push(['dictFinish']); setTimeout(()=>{ __dict('final','e mais isso'); __dict('estado','fim'); },60); },
  dictCancel:()=>__calls.push(['dictCancel']),
  toMp3:(id,mime,b64)=>{ __calls.push(['toMp3',mime,b64.length]); later(()=>__nativeDone(id,true,JSON.stringify({b64:window.__mp3||'',dur:3}))); },
  tileAdd:(id,w)=>{ __calls.push(['tileAdd',w]); later(()=>__nativeDone(id,true,'2')); },
  alarmState:()=>JSON.stringify(__al),
  alarmAsk:(id,w)=>{ __calls.push(['alarmAsk',w]); later(()=>{ if(w==='notif') __al.notif=true; if(w==='cal') __al.cal=true; __nativeDone(id,true,(w==='notif'||w==='cal')?'ok':'settings'); }); },
  alarmTest:m=>__calls.push(['alarmTest',m]),
  alarmEvents:()=>{ const e=window.__ev||'[]'; window.__ev='[]'; return e; },
  alarmClear:()=>__calls.push(['alarmClear']),
  calInfo:e=>JSON.stringify({perm:__al.cal,found:true,cal:e,account:e,google:true,syncOn:true}),
  calSync:(id,e,j)=>{ const o=JSON.parse(j); __calls.push(['calSync',e,o]); later(()=>__nativeDone(id,true,JSON.stringify({ok:true,perm:true,found:true,cal:e,account:e,google:true,syncOn:true,done:[...o.up.map(x=>x.id),...o.del]}))); },
  calWipe:id=>{ __calls.push(['calWipe']); later(()=>__nativeDone(id,true,'1')); },
  signIn:(id,scopes,hint,inter)=>{ __calls.push(['signIn',scopes,hint,inter]); const n=(+localStorage.getItem('__tk')||0)+1; localStorage.setItem('__tk',n);
    later(()=>__nativeDone(id,true,JSON.stringify({token:'tok-native-'+n,email:'maria@example.com',scopes}))); },
  clearToken:t=>__calls.push(['clearToken',t]),
  signOut:t=>__calls.push(['signOut',t]),
  bioState:()=>'ok',
  bioEnable:(id,sec)=>{ __calls.push(['bioEnable',sec.length]); localStorage.setItem('__bio',sec); later(()=>__nativeDone(id,true,'')); },
  bioUnlock:id=>{ __calls.push(['bioUnlock']); later(()=>__nativeDone(id,true,localStorage.getItem('__bio'))); },
  bioDisable:()=>__calls.push(['bioDisable']),
  saveFile:(id,name,mime,b64)=>{ __calls.push(['saveFile',name,mime,atob(b64)]); later(()=>__nativeDone(id,true,'Download/Não Esquece/'+name)); },
  share:(id,json)=>{ const o=JSON.parse(json); __calls.push(['share',o.title,o.text,o.files.map(f=>f.name+'|'+f.type+'|'+atob(f.b64))]); later(()=>__nativeDone(id,true,'')); },
  openExternal:u=>__calls.push(['openExternal',u]),
  dictate:id=>{ __calls.push(['dictate']); later(()=>__nativeDone(id,true,'comprar pão amanhã')); },
  setTheme:(d,c)=>__calls.push(['setTheme',d,c]),
};
"""

MP3B64 = base64.b64encode(open(HERE + '/m17/t.mp3', 'rb').read()).decode()
WEBMB64 = base64.b64encode(open('/home/claude/ne-repo/android/app/src/androidTest/assets/teste.webm', 'rb').read()).decode()
MAKE_TODAY = '''(()=>{ const t=Date.now(); const y=new Date(t-86400e3); y.setHours(9,0,0,0);
  const a={id:uid(),title:'Ligar para o fornecedor',html:'',createdAt:t,updatedAt:t,remindAt:toLocalInput(y),remindBefore:0};
  const h=new Date(t+40*60e3); const b={id:uid(),title:'Reunião rápida',html:'',createdAt:t,updatedAt:t,remindAt:toLocalInput(h),remindBefore:0};
  db.notes.push(a,b); changed(); render(); window.__late=a.id; window.__soon=b.id; })()'''

async def set_at(page, v):
    await page.fill('#rmDay', v[:10]); await page.fill('#rmHour', v[11:16])

async def calls(page, kind=None):
    c = await page.evaluate('window.__calls')
    return [x for x in c if kind is None or x[0] == kind]

async def pin(page, p='482916'):
    for d in p: await page.click(f'#pPad [data-d="{d}"]')

async def main():
    async with async_playwright() as p:
        b = await p.chromium.launch(executable_path='/usr/bin/google-chrome')
        ctx = await b.new_context(viewport={'width': 390, 'height': 844}, device_scale_factor=2, is_mobile=True, has_touch=True, locale='pt-BR', timezone_id='America/Campo_Grande', color_scheme='dark')
        await ctx.add_init_script("Object.defineProperty(Navigator.prototype,'webdriver',{get:()=>false});")
        await ctx.add_init_script(MOCK)
        page = await ctx.new_page()
        errs = []; page.on('pageerror', lambda e: errs.append(str(e)))
        await page.route('https://fonts.**', lambda r: r.abort())
        await page.route('https://www.googleapis.com/**', drive)
        await page.route('https://www.googleapis.com/oauth2/v3/userinfo', lambda r: r.fulfill(status=200, headers={**CORS, 'Content-Type': 'application/json'}, body=json.dumps({'email': 'maria@example.com', 'name': 'Maria'})))
        revokes = []
        async def on_revoke(r):
            revokes.append(r.request.url); await r.fulfill(status=200, headers=CORS, body='{}')
        await page.route('https://oauth2.googleapis.com/**', on_revoke)
        accounts = []
        async def on_accounts(r):
            accounts.append(r.request.url); await r.abort()
        await page.route('https://accounts.google.com/**', on_accounts)

        await page.goto(URL); await page.wait_for_timeout(500)
        check('reconhece que está no APK', await page.evaluate("IS_APK && document.documentElement.classList.contains('apk')"))
        st = await calls(page, 'setTheme')
        check('cor das barras do Android acompanha o tema', st and st[-1][1] == '1' and st[-1][2].startswith('#'), str(st[-1:] if st else st))

        # link "Ajuda e contato" na tela dos termos abre a página dentro do app (não sai para o navegador)
        await page.click('#terms a[href="suporte/"]'); await page.wait_for_timeout(150)
        oe = await calls(page, 'openExternal')
        check('link da tela de termos vai para o app', oe and oe[-1][1].endswith('/suporte/') and page.url.endswith('index.html'), str(oe[-1:]))

        await page.click('#tOk'); await page.click('#tAccept'); await page.wait_for_timeout(250)
        check('tela de entrada com Google', await page.is_visible('#gLogin'))
        await page.click('#gLogin'); await page.wait_for_timeout(600)
        si = await calls(page, 'signIn')
        check('login pela tela de contas do Android', len(si) == 1 and si[0][3] == '1' and 'drive.file' in si[0][1] and 'calendar.app.created' in si[0][1], str(si))
        check('não abre a página de login do Google', not accounts, str(accounts[:1]))
        await page.wait_for_selector('#suGo', timeout=8000)
        check('conta ligada com o e-mail certo', await page.evaluate("cfg.linked && cfg.email==='maria@example.com' && hasToken() && token==='tok-native-1' && cfg.calOk===true"))
        await page.click('#suGo'); await page.wait_for_timeout(150)
        for _ in range(2): await pin(page); await page.wait_for_timeout(150)
        await page.wait_for_selector('#rkVal', timeout=15000)
        await page.click('#rkAck'); await page.click('#rkDone'); await page.wait_for_timeout(400)
        check('oferece digital ou rosto do Android', await page.is_visible('#bYes'))
        await page.screenshot(path=SHOT + 'apk_bio.png')
        await page.click('#bYes'); await page.wait_for_timeout(500)
        check('digital ativada pelo Android', len(await calls(page, 'bioEnable')) == 1 and await page.evaluate("Vault.meta.bio && Vault.meta.bio.mode==='native'"))
        await page.wait_for_timeout(1500)
        check('sincronizou com o Drive', await page.evaluate("cfg.lastSync>0 && !!cfg.fileId"), await page.evaluate("JSON.stringify(cfg.lastErr)"))

        # acesso expirado (401): limpa o token no Android e reconecta sozinho, sem pedir nada
        await page.route('https://www.googleapis.com/drive/v3/about**', lambda r: r.fulfill(status=401, headers=CORS, body='{}'))
        r = await page.evaluate("api('https://www.googleapis.com/drive/v3/about?fields=user').then(()=>'ok',e=>e.message)")
        ct = await calls(page, 'clearToken')
        check('401 limpa o token guardado no Android', r == 'auth' and ct and ct[-1][1] == 'tok-native-1', str(ct))
        await page.evaluate("cfg.silentFailAt=0; syncNow(false)"); await page.wait_for_timeout(1500)
        si = await calls(page, 'signIn')
        check('reconecta sozinho (sem tela)', len(si) == 2 and si[1][3] == '0' and si[1][2] == 'maria@example.com', str(si[1:]))
        check('novo acesso em uso', await page.evaluate("hasToken() && token==='tok-native-2'"))

        # ditado
        await page.click('#fab'); await page.wait_for_timeout(300)
        await page.fill('#title', 'Mercado')
        await page.click('#ed')
        await page.click('#micBtn'); await page.wait_for_timeout(200)
        await page.click('#mDit'); await page.wait_for_timeout(400)
        await page.evaluate("__dict('final','comprar pão amanhã')"); await page.wait_for_timeout(100)
        await page.click('#dcDone'); await page.wait_for_timeout(600)
        txt = await page.inner_text('#ed')
        check('ditado do Android vira texto', 'Comprar pão amanhã' in txt, txt[:60])

        # salvar e compartilhar
        await page.evaluate("download('teste.txt','olá mundo','text/plain')"); await page.wait_for_timeout(300)
        sf = await calls(page, 'saveFile')
        check('salvar arquivo vai para Downloads', sf and sf[-1][1] == 'teste.txt' and sf[-1][3] == 'olá mundo'.encode('utf-8').decode('latin-1'), str(sf[-1:]))
        check('aviso de onde salvou', 'Downloads' in await page.inner_text('#toast'))
        await page.evaluate("shareText('Mercado','arroz e feijão')"); await page.wait_for_timeout(200)
        await page.evaluate("navigator.share({files:[new File(['abc'],'lista.txt',{type:'text/plain'})],text:'segue'})"); await page.wait_for_timeout(300)
        sh = await calls(page, 'share')
        check('compartilhar texto pelo Android', sh and sh[0][2] == 'arroz e feijão', str(sh[:1]))
        check('compartilhar arquivo pelo Android', len(sh) == 2 and sh[1][3] == ['lista.txt|text/plain|abc'], str(sh[1:]))
        await page.evaluate("history.back()"); await page.wait_for_timeout(400)

        # ---------- alarme do lembrete ----------
        check('no APK o app não cria agenda no Google', len(ns['CALS']) == 0, str(list(ns['CALS'])))
        await page.click('#fab'); await page.wait_for_timeout(300)
        await page.fill('#title', 'Reunião com o gerente')
        await page.click('#ed'); await page.keyboard.type('Levar o relatório impresso')
        await page.click('#remBtn'); await page.wait_for_timeout(300)
        sh = await page.inner_text('#sheet')
        shl = sh.lower(); check('lembrete fala do alarme do celular', all(x in shl for x in ['o alarme toca neste celular', 'tocar o alarme', 'só vibração', 'só som', '5 min antes']), sh[:160])
        check('folha avisa que as notificações estão desligadas', await page.is_visible('#alWarn'))
        check('padrão do alarme: na hora', await page.get_attribute('#rmB [data-m="0"]', 'aria-pressed') == 'true')
        at = await page.evaluate("toLocalInput(new Date(Date.now()+2*3600e3))")
        await set_at(page, at)
        await page.click('#rmB [data-m="5"]'); await page.click('#rmM [data-k="vibra"]')
        summ = await page.inner_text('#rmSum')
        check('resumo com a hora do aviso e do alarme', 'Aviso às' in summ and 'alarme às' in summ, summ)
        await page.screenshot(path=SHOT + 'apk_lembrete.png')
        await page.click('#rmOk'); await page.wait_for_timeout(900)
        nid = await page.evaluate("cur.noteId")
        al = await calls(page, 'alarmSet'); last = al[-1][1] if al else []
        mine = [x for x in last if x['id'] == nid]
        check('alarme marcado no Android', mine and mine[0]['at'] == at and mine[0]['before'] == 5 and mine[0]['mode'] == 'vibra' and mine[0]['title'] == 'Reunião com o gerente' and 'relatório' in mine[0]['text'], str(mine))
        asks = [x[1] for x in await calls(page, 'alarmAsk')]
        check('pede a permissão de notificação', asks[:1] == ['notif'], str(asks))
        await page.wait_for_selector('#aqYes', timeout=3000)
        check('oferece o calendário do celular na conta do app', 'maria@example.com' in await page.inner_text('#sheet'))
        await page.screenshot(path=SHOT + 'apk_calendario.png')
        await page.click('#aqYes'); await page.wait_for_timeout(900)
        cs = await calls(page, 'calSync')
        check('lembrete vai para o calendário da conta', cs and cs[-1][1] == 'maria@example.com' and any(u['id'] == nid and u['at'] == at for u in cs[-1][2]['up']), str(cs[-1:])[:200])
        check('calendário ligado', await page.evaluate("cfg.devCal===true && cfg.devCalInfo.google===true && cfg.remMode==='vibra'"))
        n0 = len(cs)
        await page.click('#ed'); await page.keyboard.press('End'); await page.keyboard.type(' e o crachá'); await page.wait_for_timeout(200)
        await page.evaluate("flushEdit()"); await page.wait_for_timeout(1200)
        al = await calls(page, 'alarmSet'); cs = await calls(page, 'calSync')
        check('texto novo vai para o alarme e para o calendário', 'crachá' in json.dumps([x for x in al[-1][1] if x['id'] == nid], ensure_ascii=False) and len(cs) > n0, str(len(cs)))
        await page.evaluate(f"window.__ev=JSON.stringify([{{id:'{nid}',act:'done'}}])")
        await page.evaluate("document.dispatchEvent(new Event('visibilitychange'))"); await page.wait_for_timeout(1400)
        check('"Concluída" na tela do alarme conclui a nota', await page.evaluate(f"noteById('{nid}').done===true"))
        al = await calls(page, 'alarmSet'); cs = await calls(page, 'calSync')
        check('nota concluída sai dos alarmes e do calendário', not any(x['id'] == nid for x in al[-1][1]) and nid in cs[-1][2]['del'], str(cs[-1:])[:200])
        await page.evaluate("history.back()"); await page.wait_for_timeout(400)
        await page.evaluate(f"__openFromNative('{nid}')"); await page.wait_for_timeout(1000)
        check('tocar no aviso abre a nota certa', await page.evaluate(f"cur.noteId==='{nid}'"))
        await page.evaluate("history.back()"); await page.wait_for_timeout(400)
        ns['CALS']['velha1@group.calendar.google.com'] = {}
        await page.evaluate("cfg.calendarId='velha1@group.calendar.google.com'; cfg.alarmsBy=undefined; saveCfg(); syncNow(false)")
        await page.wait_for_timeout(2500)
        check('agenda antiga "Não Esquece" do Google sai (sem aviso em dobro)', 'velha1@group.calendar.google.com' not in ns['CALS'] and await page.evaluate("cfg.calendarId==='' && cfg.alarmsBy==='android'"), str(list(ns['CALS'])))

        # ---------- lembrete que repete ----------
        await page.click('#fab'); await page.wait_for_timeout(300)
        await page.fill('#title', 'Bater o ponto')
        await page.click('#remBtn'); await page.wait_for_timeout(300)
        at8 = await page.evaluate("(()=>{ const d=new Date(); d.setHours(8,0,0,0); return toLocalInput(d); })()")
        await set_at(page, at8)
        await page.click('#rmR [data-r="uteis"]')
        summ = await page.inner_text('#rmSum')
        check('repetir: resumo mostra o próximo alarme', 'Próximo' in summ and 'dias úteis às 08:00' in summ, summ)
        await page.screenshot(path=SHOT + 'apk_repetir.png')
        await page.click('#rmOk'); await page.wait_for_timeout(700)
        pid = await page.evaluate("cur.noteId")
        al = await calls(page, 'alarmSet'); mine = [x for x in al[-1][1] if x['id'] == pid]
        check('repetição vai para o alarme do Android', mine and mine[0].get('repeat') == 'uteis' and mine[0]['at'] == at8, str(mine))
        check('chip mostra a repetição', 'Dias úteis às 08:00' in await page.inner_text('#remTxt'), await page.inner_text('#remTxt'))
        await page.evaluate("closeSheet()"); await page.wait_for_timeout(1500)
        cs = await calls(page, 'calSync')
        check('repetição vai para o calendário', any(u['id'] == pid and u.get('repeat') == 'uteis' for c in cs for u in c[2]['up']))
        # fechar a janela sem tocar em Salvar também salva
        await page.click('#remBtn'); await page.wait_for_timeout(300)
        at2 = await page.evaluate("toLocalInput(new Date(Date.now()+5*3600e3))")
        await set_at(page, at2); await page.click('#rmR [data-r=""]')
        await page.evaluate("closeSheet()"); await page.wait_for_timeout(500)
        check('fechar sem Salvar salva o lembrete com a hora escolhida', await page.evaluate(f"noteById('{pid}').remindAt") == at2 and await page.evaluate(f"!noteById('{pid}').remindRepeat") and 'Lembrete salvo' in await page.inner_text('#toast'), await page.inner_text('#toast'))
        await page.wait_for_timeout(400); await page.evaluate("closeSheet()"); await page.wait_for_timeout(200)

        # hora trocada sem aviso do campo (alguns Android não disparam evento): vale ao salvar
        await page.click('#remBtn'); await page.wait_for_timeout(300)
        await page.evaluate("document.querySelector('#rmHour').value='21:45'")
        await page.click('#rmOk'); await page.wait_for_timeout(400)
        check('hora escolhida vale mesmo sem evento do campo', (await page.evaluate(f"noteById('{pid}').remindAt"))[11:16] == '21:45', await page.evaluate(f"noteById('{pid}').remindAt"))
        await page.wait_for_timeout(400); await page.evaluate("closeSheet()"); await page.wait_for_timeout(200)
        await page.click('#remBtn'); await page.wait_for_timeout(300)
        await page.click('#rmQ [data-q="a8"]')
        await page.click('#rmOk'); await page.wait_for_timeout(400)
        check('atalho Amanhã 08:00', (await page.evaluate(f"noteById('{pid}').remindAt"))[11:16] == '08:00')
        await page.wait_for_timeout(400); await page.evaluate("closeSheet()"); await page.wait_for_timeout(200)

        # ---------- prioridade e etiquetas ----------
        await page.click('#tagBtn'); await page.wait_for_timeout(300)
        await page.click('#tgUrg'); await page.wait_for_timeout(150)
        await page.fill('#tgName', 'Cliente'); await page.click('#tgCol [data-c="verde"]'); await page.click('#tgAdd'); await page.wait_for_timeout(300)
        await page.screenshot(path=SHOT + 'apk_etiquetas.png')
        check('urgente e etiqueta na nota', await page.evaluate(f"(n=>n.urgent===true && n.tags.length===1 && tagById(n.tags[0]).name==='Cliente' && tagById(n.tags[0]).color==='verde')(noteById('{pid}'))"))
        await page.click('#tgOk'); await page.wait_for_timeout(200)
        check('chip da nota mostra Urgente · Cliente', await page.inner_text('#tagTxt') == 'Urgente · Cliente', await page.inner_text('#tagTxt'))
        await page.evaluate("history.back()"); await page.wait_for_timeout(400)
        chips = await page.inner_text('#chips')
        check('filtros Urgentes e Cliente aparecem', 'Urgentes' in chips and 'Cliente' in chips, chips[:200])
        card = await page.inner_text(f'.note[data-id="{pid}"]')
        check('cartão mostra Urgente e a etiqueta', 'Urgente' in card and 'Cliente' in card, card)
        tid = await page.evaluate(f"noteById('{pid}').tags[0]")
        await page.click(f'#chips [data-f="tag:{tid}"]'); await page.wait_for_timeout(300)
        ids = await page.evaluate("[...document.querySelectorAll('#notes .note[data-id]')].map(e=>e.dataset.id)")
        check('filtro por etiqueta mostra só as notas dela', ids == [pid], str(ids))
        check('menu da etiqueta', await page.is_visible('#tMore'))
        await page.click('#chips [data-f="urgent"]'); await page.wait_for_timeout(300)
        ids = await page.evaluate("[...document.querySelectorAll('#notes .note[data-id]')].map(e=>e.dataset.id)")
        check('Urgentes mostra a nota urgente', ids == [pid], str(ids))

        # ---------- tela Hoje ----------
        await page.evaluate(MAKE_TODAY)
        await page.click('#bnav [data-tab="hoje"]'); await page.wait_for_timeout(400)
        txt = await page.inner_text('#notes')
        late = await page.evaluate("__late")
        check('Hoje mostra as atrasadas em destaque', 'ATRASADAS' in txt.upper() and await page.is_visible(f'.hrow.late .note[data-id="{late}"]'), txt[:200])
        order = await page.evaluate("[...document.querySelectorAll('#notes .hrow .note')].map(e=>e.dataset.id)")
        soon = await page.evaluate("__soon")
        check('Hoje em ordem de horário', soon in order and order.index(soon) > 0, str(order))
        await page.screenshot(path=SHOT + 'apk_hoje.png')
        await page.evaluate("__todayFromNative()"); await page.wait_for_timeout(200)
        check('widget abre a aba Hoje', await page.evaluate("cur.tab==='hoje'"))

        # ---------- escanear documento ----------
        await page.evaluate(f"openNote('{pid}')"); await page.wait_for_timeout(400)
        await page.click('#attBtn'); await page.wait_for_timeout(300)
        await page.click('#aScan'); await page.wait_for_timeout(900)
        n_att = await page.evaluate(f"noteById('{pid}').attachments")
        check('scanner do Google vira PDF na nota', n_att and n_att[-1]['type'] == 'application/pdf' and n_att[-1]['pages'] == 2, str(n_att)[:200])
        check('PDF aparece nos anexos', await page.is_visible('#atts .pdft'))
        await page.screenshot(path=SHOT + 'apk_pdf.png')
        await page.click('#atts .att'); await page.wait_for_timeout(300)
        await page.click('#pdOpen'); await page.wait_for_timeout(700)
        vf = await calls(page, 'viewFile')
        check('abrir o PDF chama o leitor do celular', vf and vf[-1][2] == 'application/pdf' and vf[-1][3].startswith('%PDF'), str(vf[-1:])[:120])
        await page.evaluate("history.back()"); await page.wait_for_timeout(400)
        await page.evaluate("syncNow(false)"); await page.wait_for_timeout(3000)
        data = [f for f in ns['FILES'].values() if f['name'].startswith('Não Esquece - dados')]
        body = json.loads(data[0]['content']) if data else {}
        check('etiquetas e urgente vão para o Drive', any(t.get('name') == 'Cliente' for t in body.get('tags', [])) and any(n.get('urgent') for n in body.get('notes', [])), str(body.get('tags'))[:120])

        # ================= 1.5 =================
        # barra de navegação
        await page.click('#bnav [data-tab="notas"]'); await page.wait_for_timeout(300)
        tabs = await page.eval_on_selector_all('#bnav [data-tab] .bl', 'els=>els.map(e=>e.textContent.trim())')
        check('barra embaixo com Notas, Tarefas, Hoje, Calendário e Relatório', tabs == ['Notas', 'Tarefas', 'Hoje', 'Calendário', 'Relatório'], str(tabs))
        badge = await page.evaluate("(b=>b&&!b.hidden?b.textContent:'')(document.querySelector('#bnav [data-tab=hoje] .bdg'))")
        check('aba Hoje mostra quantos itens tem', badge != '', badge)
        await page.screenshot(path=SHOT + 'apk15_notas.png')
        await page.click('#bnav [data-tab="cal"]'); await page.wait_for_timeout(400)
        check('aba selecionada fica marcada', await page.get_attribute('#bnav [data-tab="cal"]', 'aria-selected') == 'true' and await page.get_attribute('#bnav [data-tab="notas"]', 'aria-selected') == 'false')
        cells = await page.eval_on_selector_all('#calGrid .cd', 'e=>e.length')
        check('calendário com o mês inteiro', cells in (35, 42), str(cells))
        check('hoje marcado no calendário', await page.is_visible('#calGrid .cd.t'))
        dots = await page.eval_on_selector_all('#calGrid .cd i b', 'e=>e.length')
        check('dias com lembrete ou concluída têm bolinha', dots >= 2, str(dots))
        await page.screenshot(path=SHOT + 'apk15_calendario.png')
        label = await page.inner_text('.calnav b')
        await page.click('#calNext'); await page.wait_for_timeout(300)
        check('próximo mês', await page.inner_text('.calnav b') != label)
        await page.click('#calToday'); await page.wait_for_timeout(300)
        check('volta para hoje', await page.inner_text('.calnav b') == label)
        await page.evaluate("history.back()"); await page.wait_for_timeout(400)
        check('Voltar do Android volta para a aba Notas', await page.evaluate("cur.tab==='notas'"))

        # relatório
        await page.click('#bnav [data-tab="rel"]'); await page.wait_for_timeout(400)
        rtxt = await page.inner_text('#notes')
        check('relatório mostra o resumo do dia', 'concluída' in rtxt.lower() and 'Gerar PDF e enviar' in rtxt, rtxt[:200])
        await page.screenshot(path=SHOT + 'apk15_relatorio.png')
        n_share = len(await calls(page, 'share'))
        await page.click('#rpGo'); await page.wait_for_timeout(2500)
        sh = await calls(page, 'share')
        okpdf = len(sh) > n_share and sh[-1][3] and sh[-1][3][0].split('|')[0].startswith('Relatório') and sh[-1][3][0].split('|')[1] == 'application/pdf' and sh[-1][3][0].split('|', 2)[2].startswith('%PDF')
        check('relatório vira PDF e abre o compartilhar (WhatsApp)', okpdf, str(sh[-1][3][0][:60]) if len(sh) > n_share else 'sem share')
        await page.click('#rpP [data-rp="semana"]'); await page.wait_for_timeout(200)
        check('relatório da semana', 'De ' in await page.inner_text('.rpper'))

        # nota rápida e áudio feitos fora do app
        await page.evaluate("window.__quick=[{id:'q1',type:'nota',text:'Ligar para o cliente\\nPedir o orçamento',at:Date.now()},{id:'q2',type:'audio',mime:'audio/mp4',dur:7,at:Date.now()}]")
        await page.evaluate("document.dispatchEvent(new Event('visibilitychange'))"); await page.wait_for_timeout(1500)
        qd = [x[1] for x in await calls(page, 'quickDone')]
        got = await page.evaluate("[db.notes.some(n=>n.title==='Ligar para o cliente'&&n.html.includes('orçamento')), db.notes.some(n=>n.title.startsWith('Áudio rápido')&&n.attachments&&n.attachments[0].type==='audio/mp4')]")
        check('nota rápida e áudio viram notas', got == [True, True] and sorted(qd) == ['q1', 'q2'], f'{got} {qd}')

        # ler texto da foto
        await page.click('#bnav [data-tab="notas"]'); await page.wait_for_timeout(300)
        await page.click('#fab'); await page.wait_for_timeout(300)
        await page.fill('#title', 'Entrega do fornecedor')
        oid = await page.evaluate("cur.noteId")
        await page.set_input_files('#inGal', SHOT + 'foto_ocr.jpg'); await page.wait_for_timeout(1500)
        oc = await page.evaluate(f"(noteById('{oid}').attachments||[]).map(a=>a.ocr||'')")
        check('texto da foto lido sozinho', oc and 'NOTA FISCAL 4521' in oc[0], str(oc))
        await page.click('#atts .att'); await page.wait_for_timeout(400)
        check('visualizador tem o botão Ler texto', await page.is_visible('#vText'))
        await page.click('#vText'); await page.wait_for_timeout(400)
        check('janela com o texto da foto', 'NOTA FISCAL 4521' in await page.inner_text('#sheet'))
        await page.click('#ocIns'); await page.wait_for_timeout(300)
        check('colocar o texto na nota', 'Entregar na sala 2' in await page.inner_text('#ed'))
        await page.evaluate("history.back()"); await page.wait_for_timeout(500)
        await page.fill('#q', 'fiscal'); await page.wait_for_timeout(300)
        found = await page.evaluate("[...document.querySelectorAll('#notes .note[data-id]')].map(e=>e.dataset.id)")
        check('busca acha o texto da foto', oid in found, str(found))
        await page.fill('#q', ''); await page.wait_for_timeout(200)

        # assinatura
        await page.evaluate(f"openNote('{oid}')"); await page.wait_for_timeout(400)
        await page.click('#attBtn'); await page.wait_for_timeout(300)
        await page.click('#aSig'); await page.wait_for_timeout(500)
        box = await page.eval_on_selector('#dCv', 'e=>{ const r=e.getBoundingClientRect(); return [r.x,r.y,r.width,r.height]; }')
        x, y, w, h = box
        await page.mouse.move(x + w * .2, y + h * .55); await page.mouse.down()
        for i in range(1, 30): await page.mouse.move(x + w * (.2 + i * .02), y + h * (.55 - 0.12 * ((i % 6) / 6)))
        await page.mouse.up()
        await page.fill('#dName', 'João da Silva')
        await page.screenshot(path=SHOT + 'apk15_assinatura.png')
        await page.click('#dSave'); await page.wait_for_timeout(800)
        sig = await page.evaluate(f"(noteById('{oid}').attachments||[]).filter(a=>a.kind==='assinatura')")
        check('assinatura salva na nota com o nome', len(sig) == 1 and sig[0]['signer'] == 'João da Silva' and sig[0]['type'] == 'image/png', str(sig)[:120])
        check('tela de assinatura fechou', not await page.is_visible('#draw'))

        # histórico
        await page.click('#ed'); await page.keyboard.press('End'); await page.keyboard.type(' Conferido.')
        await page.evaluate("flushEdit()"); await page.click('#backBtn'); await page.wait_for_timeout(700)
        hl = await page.evaluate(f"histGet('{oid}').then(L=>L.length)")
        check('versão antiga guardada ao fechar', hl >= 1, str(hl))
        await page.evaluate(f"openNote('{oid}')"); await page.wait_for_timeout(400)
        await page.click('#moreBtn'); await page.wait_for_timeout(300)
        await page.click('#oHist'); await page.wait_for_timeout(500)
        rows = await page.eval_on_selector_all('#sheet [data-hv]', 'e=>e.length')
        check('histórico lista as versões', rows >= 1, str(rows))
        await page.click('#sheet [data-hv="0"]'); await page.wait_for_timeout(300)
        await page.click('#hvOk'); await page.wait_for_timeout(500)
        check('voltar para a versão antiga', 'Conferido' not in await page.evaluate(f"noteById('{oid}').html"))

        # nota secreta
        await page.click('#moreBtn'); await page.wait_for_timeout(300)
        await page.click('#oSecret'); await page.wait_for_timeout(300)
        check('nota virou secreta', await page.evaluate(f"noteById('{oid}').secret===true"))
        await page.click('#backBtn'); await page.wait_for_timeout(500)
        card = await page.inner_text(f'.note[data-id="{oid}"]')
        check('cartão esconde o texto da nota secreta', 'Nota secreta' in card and 'NOTA FISCAL' not in card, card)
        nb = len(await calls(page, 'bioUnlock'))
        await page.click(f'.note[data-id="{oid}"]'); await page.wait_for_timeout(900)
        check('abrir nota secreta pede a digital', len(await calls(page, 'bioUnlock')) == nb + 1 and await page.evaluate(f"cur.noteId==='{oid}'"))
        al = await calls(page, 'alarmSet')
        await page.click('#backBtn'); await page.wait_for_timeout(400)

        # ================= 1.6: tarefas =================
        await page.click('#bnav [data-tab="tarefas"]'); await page.wait_for_timeout(400)
        check('aba Tarefas no app Android', 'Minhas tarefas' in await page.inner_text('#notes'))
        dd = await page.evaluate("(()=>{ const d=new Date(Date.now()+3*3600e3); return [dkey(d), String(d.getHours()).padStart(2,'0')+':'+String(d.getMinutes()).padStart(2,'0')]; })()")
        n_al = len(await calls(page, 'alarmSet'))
        await page.click('#fab'); await page.wait_for_timeout(350)
        await page.fill('#tdT', 'Ligar para o cliente')
        await page.click('[data-qa="tdW"]'); await page.fill('#tdDay', dd[0]); await page.fill('#tdHour', dd[1]); await page.wait_for_timeout(500)
        sm = await page.inner_text('#tdSum')
        check('janela da tarefa diz quando o alarme toca', 'alarme toca' in sm and dd[1] in sm, sm)
        await page.click('#tdOk'); await page.wait_for_timeout(1500)
        tid = await page.evaluate("liveTasks().find(t=>t.title==='Ligar para o cliente').id")
        check('aviso do alarme ao salvar a tarefa', 'Alarme' in await page.eval_on_selector('#toast', 'e=>e.textContent'))
        al = await calls(page, 'alarmSet')
        it = [x for x in (al[-1][1] if len(al) > n_al else []) if x['id'] == tid]
        check('tarefa com hora vira alarme do Android', it and it[0]['at'] == dd[0] + 'T' + dd[1] and 'Tarefa' in it[0]['text'] and it[0]['title'] == 'Ligar para o cliente', str(it)[:200])
        if await page.evaluate("__al.cal"):
            await page.evaluate("calSyncNow&&calSyncNow()") if await page.evaluate("typeof calSyncNow==='function'") else None
            await page.wait_for_timeout(5000)
            cs = await calls(page, 'calSync')
            check('tarefa entra no calendário do celular', any(any(u['id'] == tid for u in c[2]['up']) for c in cs), str([u['id'] for c in cs[-2:] for u in c[2]['up']])[:200])
        # Concluir pelo alarme
        await page.evaluate(f"window.__ev=JSON.stringify([{{act:'done',id:'{tid}'}}]); document.dispatchEvent(new Event('visibilitychange'))"); await page.wait_for_timeout(1500)
        check('Concluir no alarme marca a tarefa', await page.evaluate(f"taskById('{tid}').done===true") and 'pelo alarme' in await page.eval_on_selector('#toast', 'e=>e.textContent'))
        await page.wait_for_timeout(600)
        al = await calls(page, 'alarmSet')
        check('tarefa concluída sai dos alarmes', not any(x['id'] == tid for x in al[-1][1]))
        # tocar no aviso abre a tarefa
        await page.evaluate(f"(()=>{{ const t=taskById('{tid}'); tkSetDone(t,false); changed(); }})()")
        await page.click('#bnav [data-tab="notas"]'); await page.wait_for_timeout(300)
        await page.evaluate(f"__openFromNative('{tid}')"); await page.wait_for_timeout(1500)
        check('tocar no aviso abre a tarefa', await page.evaluate("cur.tab==='tarefas'") and await page.is_visible('#tdT') and await page.input_value('#tdT') == 'Ligar para o cliente')
        await page.screenshot(path=SHOT + 'apk16_tarefa.png')
        await page.evaluate("closeSheet()"); await page.wait_for_timeout(300)
        await page.click('#bnav [data-tab="notas"]'); await page.wait_for_timeout(300)

        # ================= 1.7: MP3 =================
        await page.evaluate(f"window.__mp3='{MP3B64}'")
        await page.click('#bnav [data-tab="notas"]'); await page.wait_for_timeout(300)
        await page.click('#fab'); await page.wait_for_timeout(300)
        await page.fill('#title', 'Gravação para MP3')
        mid = await page.evaluate("cur.noteId")
        await page.evaluate(f"""(async()=>{{ const b=await (await fetch('data:audio/webm;base64,{WEBMB64}')).blob(); await addRecording(cur.noteId,new Blob([b],{{type:'audio/webm;codecs=opus'}}),3); }})()""")
        await page.wait_for_timeout(2500)
        at = await page.evaluate(f"noteById('{mid}').attachments.map(a=>[a.name,a.type,a.size])")
        tm = [c for c in await calls(page, 'toMp3')]
        check('gravação vira MP3 sozinha no app Android', len(at) == 1 and at[0][1] == 'audio/mpeg' and at[0][0].endswith('.mp3') and tm and tm[-1][1].startswith('audio/webm'), str(at) + ' ' + str([x[:2] for x in tm[-2:]]))
        check('cartão do áudio mostra MP3', await page.locator('#atts .aud-h .m3').count() == 1)
        await page.click('#backBtn'); await page.wait_for_timeout(300)

        # ================= 1.8: ditado contínuo =================
        await page.click('#bnav [data-tab="notas"]'); await page.wait_for_timeout(300)
        await page.click('#fab'); await page.wait_for_timeout(300)
        await page.fill('#title', 'Ditado')
        did = await page.evaluate("cur.noteId")
        nd = len(await calls(page, 'dictate')); nf = len(await calls(page, 'dictFinish')); npz = len(await calls(page, 'dictPause')); nrs = len(await calls(page, 'dictResume'))
        await page.click('#micBtn'); await page.wait_for_timeout(250); await page.click('#mDit'); await page.wait_for_timeout(400)
        db_ = await calls(page, 'dictBegin')
        check('ditado usa o reconhecimento contínuo do app (sem a janela do Google)', db_ and db_[-1][1] == 'pt-BR' and len(await calls(page, 'dictate')) == nd and await page.is_visible('#dict'))
        await page.evaluate("__dict('parcial','bater o ponto'); __dict('nivel','6.5'); __dict('final','bater o ponto às oito'); __dict('estado','ouvindo')"); await page.wait_for_timeout(150)
        check('texto do celular aparece na janela', 'Bater o ponto às oito' in await page.inner_text('#dcText'))
        await page.evaluate("__dict('aviso','offline')"); await page.wait_for_timeout(100)
        check('sem internet: avisa que usa o reconhecimento do celular', 'próprio celular' in await page.inner_text('#dcTip'))
        await page.click('#dcPause'); await page.wait_for_timeout(250)
        check('Pausar chama o Android e mostra Continuar', len(await calls(page, 'dictPause')) == npz + 1 and 'Continuar' in await page.inner_text('#dcPause'))
        await page.click('#dcPause'); await page.wait_for_timeout(250)
        check('Continuar chama o Android', len(await calls(page, 'dictResume')) == nrs + 1 and 'Pausar' in await page.inner_text('#dcPause'))
        await page.click('#dcDone'); await page.wait_for_timeout(600)
        et = await page.evaluate("document.querySelector('#ed').innerText")
        check('Concluir espera o último trecho e coloca tudo na nota', len(await calls(page, 'dictFinish')) == nf + 1 and 'Bater o ponto às oito e mais isso' in et and not await page.is_visible('#dict'), repr(et))
        await page.evaluate("window.__dictAns='sem'")
        await page.click('#micBtn'); await page.wait_for_timeout(250); await page.click('#mDit'); await page.wait_for_timeout(900)
        check('celular sem reconhecimento: usa o ditado do Google de reserva', len(await calls(page, 'dictate')) == nd + 1)
        await page.evaluate("window.__dictAns='ok'")
        await page.click('#backBtn'); await page.wait_for_timeout(300)

        # ================= 1.9: temporizador e português do ditado =================
        await page.click('#bnav [data-tab="hoje"]'); await page.wait_for_timeout(300)
        n_al = len(await calls(page, 'alarmSet'))
        await page.click('#tmNew'); await page.wait_for_timeout(500)
        await page.evaluate("document.querySelector('#wH').scrollTop=0; document.querySelector('#wM').scrollTop=40*52; document.querySelector('#wS').scrollTop=0"); await page.wait_for_timeout(500)
        await page.fill('#tmName', 'Compromisso')
        await page.click('#tmGo'); await page.wait_for_timeout(1500)
        tk = await page.evaluate("liveTasks().find(t=>t.title==='Compromisso')")
        al = await calls(page, 'alarmSet')
        it = [x for x in (al[-1][1] if len(al) > n_al else []) if x['id'] == tk['id']]
        check('temporizador vira alarme do Android no segundo certo', it and it[0]['at'] == tk['date'] + 'T' + tk['time'] and len(it[0]['at']) == 19 and it[0]['text'] == 'Temporizador de 40 min', str(it)[:200])
        await page.click('.tmcard [data-tma="pause"]'); await page.wait_for_timeout(1200)
        al = await calls(page, 'alarmSet')
        check('pausar tira o alarme do Android', not any(x['id'] == tk['id'] for x in al[-1][1]))
        await page.click('.tmcard [data-tma="cancel"]'); await page.wait_for_timeout(400)
        # português do ditado: falta baixar -> tela obrigatória antes de ditar
        await page.evaluate("window.__vst='disponivel'")
        await page.click('#bnav [data-tab="notas"]'); await page.wait_for_timeout(300)
        await page.click('#fab'); await page.wait_for_timeout(300); await page.fill('#title', 'Ditar com português')
        nb = len(await calls(page, 'dictBegin'))
        await page.click('#micBtn'); await page.wait_for_timeout(250); await page.click('#mDit'); await page.wait_for_timeout(700)
        check('falta o português: tela obrigatória antes de ditar', await page.is_visible('#vgate') and 'Instale o português' in await page.inner_text('#vgate') and len(await calls(page, 'dictBegin')) == nb)
        check('sem botão de pular quando dá para baixar', await page.locator('#vgate #vgOk').count() == 0)
        await page.screenshot(path=SHOT + 'apk19_portugues.png')
        await page.click('#vgGo'); await page.wait_for_timeout(450)
        check('baixa com a porcentagem', '35%' in await page.inner_text('#vgate'), await page.inner_text('#vgate'))
        await page.screenshot(path=SHOT + 'apk19_baixando.png')
        await page.wait_for_timeout(2600)
        check('pronto: fecha e já abre o ditado', not await page.is_visible('#vgate') and len(await calls(page, 'dictBegin')) == nb + 1 and await page.is_visible('#dict'))
        await page.click('#dcCancel'); await page.wait_for_timeout(300)
        # Android antigo: abre as configurações de voz, avisa uma vez
        await page.evaluate("window.__vst='antigo'")
        await page.click('#micBtn'); await page.wait_for_timeout(250); await page.click('#mDit'); await page.wait_for_timeout(700)
        check('Android antigo: mostra o caminho nas configurações', await page.is_visible('#vgate') and 'configurações' in (await page.inner_text('#vgate')).lower())
        await page.click('#vgSet'); await page.wait_for_timeout(200)
        check('abre as configurações de voz', (await calls(page, 'voiceOpen'))[-1][1] == 'config')
        await page.click('#vgOk'); await page.wait_for_timeout(700)
        check('continua para o ditado', await page.is_visible('#dict'))
        await page.click('#dcCancel'); await page.wait_for_timeout(300)
        await page.click('#micBtn'); await page.wait_for_timeout(250); await page.click('#mDit'); await page.wait_for_timeout(700)
        check('não fica repetindo o aviso que não dá para resolver no app', not await page.is_visible('#vgate') and await page.is_visible('#dict'))
        await page.click('#dcCancel'); await page.wait_for_timeout(300)
        await page.evaluate("window.__vst='instalado'")
        await page.click('#backBtn'); await page.wait_for_timeout(300)

        # botões rápidos nos Ajustes
        await page.click('#setBtn'); await page.wait_for_timeout(300)
        check('Ajustes tem os botões rápidos', await page.is_visible('#qkNote') and await page.is_visible('#qkAud'))
        await page.click('#qkNote'); await page.wait_for_timeout(300)
        check('colocar o botão Nota rápida', (await calls(page, 'tileAdd'))[-1][1] == 'nota' and 'colocado' in await page.inner_text('#toast'))
        await page.evaluate("closeSheet()"); await page.wait_for_timeout(200)

        # Ajustes › Alarmes e calendário
        await page.click('#setBtn'); await page.wait_for_timeout(300)
        sheet = await page.inner_text('#sheet')
        check('Ajustes mostra Alarmes e calendário', all(x in sheet.lower() for x in ['alarmes e calendário', 'alarme na hora exata', 'alarme na tela bloqueada', 'testar o alarme', 'agenda da conta maria@example.com']), sheet[:300])
        await page.evaluate("document.querySelector('#alBlk').scrollIntoView({block:'center'})"); await page.wait_for_timeout(200)
        await page.screenshot(path=SHOT + 'apk_ajustes_alarme.png')
        await page.click('#aTest'); await page.wait_for_timeout(200)
        tt = await calls(page, 'alarmTest')
        check('Testar o alarme chama o Android', tt and tt[-1][1] == 'vibra', str(tt))
        await page.click('#aBat'); await page.wait_for_timeout(200)
        check('Bateria abre o pedido do Android', (await calls(page, 'alarmAsk'))[-1][1] == 'battery')
        check('Ajustes mostra o próximo alarme', 'próximo alarme' in (await page.inner_text('#alBlk')).lower())
        await page.click('#aWid'); await page.wait_for_timeout(200)
        check('botão do widget pede para colocar na tela inicial', len(await calls(page, 'widgetPin')) == 1)
        await page.evaluate("closeSheet()"); await page.wait_for_timeout(200)

        # Ajustes › Ajuda e contato
        await page.click('#setBtn'); await page.wait_for_timeout(300)
        sheet = await page.inner_text('#sheet')
        check('Sobre mostra a versão do app Android', 'app 1.9.0' in sheet and 'versão 1.9.0' in sheet)
        await page.click('#sHelp'); await page.wait_for_timeout(200)
        oe = await calls(page, 'openExternal')
        check('Ajuda e contato abre dentro do app', oe and '/nao-esquece/suporte/?v=1.9.0&origem=app' in oe[-1][1], str(oe[-1:]))

        # reabrir: tela de bloqueio já chama a digital
        await page.reload(); await page.wait_for_timeout(1500)
        check('ao abrir, desbloqueia com a digital', len(await calls(page, 'bioUnlock')) == 1 and not await page.is_visible('#secScr'))
        await page.wait_for_timeout(1200)
        si = await calls(page, 'signIn')
        check('ao abrir, reconecta ao Google sem tela', len(si) == 1 and si[0][3] == '0', str(si))
        check('ao abrir, os alarmes são remarcados no Android', len(await calls(page, 'alarmSet')) >= 1)

        # sair da conta
        await page.click('#setBtn'); await page.wait_for_timeout(300)
        if await page.is_visible('#sOut2'):
            await page.click('#sOut2'); await page.wait_for_timeout(400)
            await page.click('#cYes'); await page.wait_for_timeout(500)
            so = await calls(page, 'signOut')
            check('sair da conta esquece a conta no Android', len(so) == 1, str(so))
        else:
            check('sair da conta esquece a conta no Android', False, 'botão Sair não achado')
        check('sem erros de script', not errs, ' | '.join(errs[:3]))
        await b.close()
    fails = [r for r in results if not r[1]]
    print(f'\n{len(results)-len(fails)}/{len(results)} verificações passaram')

asyncio.run(main())
