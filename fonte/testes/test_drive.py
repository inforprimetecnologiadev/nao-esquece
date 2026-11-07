"""Testa as pastas no Google Drive contra um Drive simulado (mesmos endereços e formatos da API v3)."""
import asyncio, io, json, re, itertools
from urllib.parse import urlparse, parse_qs
from email.parser import BytesParser
from email.policy import default
from playwright.async_api import async_playwright
from PIL import Image

URL = 'http://localhost:8765/index.html'
FILES = {}
ids = itertools.count(1)
CALLS = []
results = []
def check(name, ok, extra=''):
    results.append((name, bool(ok))); print(('OK  ' if ok else 'FAIL'), name, extra)

CORS = {'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Headers': '*', 'Access-Control-Allow-Methods': '*', 'Access-Control-Expose-Headers': 'Location'}
def new_id(): return 'f' + str(next(ids)).zfill(8) + 'xyz'
def meta_out(f): return {'id': f['id'], 'name': f['name'], 'trashed': f['trashed'], 'parents': f['parents'], 'mimeType': f['mimeType']}

def parse_multipart(req):
    ct = req.headers.get('content-type')
    msg = BytesParser(policy=default).parsebytes(b'Content-Type: ' + ct.encode() + b'\r\n\r\n' + req.post_data_buffer)
    parts = list(msg.iter_parts())
    return json.loads(parts[0].get_payload(decode=True)), parts[1].get_payload(decode=True)

def run_q(q):
    conds = q.split(' and ')
    out = []
    for f in FILES.values():
        ok = True
        for c in conds:
            c = c.strip()
            m = re.match(r"name='((?:\\'|[^'])*)'", c)
            if m and f['name'] != m.group(1).replace("\\'", "'").replace('\\\\', '\\'): ok = False
            m = re.match(r"mimeType='([^']*)'", c)
            if m and f['mimeType'] != m.group(1): ok = False
            m = re.match(r"'([^']*)' in parents", c)
            if m and m.group(1) not in f['parents']: ok = False
            if c == 'trashed=false' and f['trashed']: ok = False
        if ok: out.append(f)
    return out

CALS = {}; EVENTS = {}; LIMIT = {'once': True}
async def drive(route, req):
    u = urlparse(req.url); qs = parse_qs(u.query); m = req.method
    CALLS.append(f'{m} {u.path}')
    if m == 'OPTIONS': return await route.fulfill(status=204, headers=CORS)
    def J0(obj, status=200): return route.fulfill(status=status, headers={**CORS, 'Content-Type': 'application/json'}, body=json.dumps(obj))
    if u.path == '/drive/v3/files' and m == 'GET' and LIMIT['once']:
        LIMIT['once'] = False
        return await route.fulfill(status=429, headers={**CORS, 'Retry-After': '1'}, body='{"error":{"code":429}}')
    p = u.path
    if p == '/calendar/v3/calendars' and m == 'POST':
        cid = 'c' + str(next(ids)) + '@group.calendar.google.com'; CALS[cid] = json.loads(req.post_data); return await J0({'id': cid})
    mm = re.match(r'^/calendar/v3/calendars/([^/]+)$', p)
    if mm:
        cid = mm.group(1).replace('%40', '@')
        if cid not in CALS: return await J0({'error': 404}, 404)
        if m == 'GET': return await J0({'id': cid})
        if m == 'DELETE': CALS.pop(cid); return await route.fulfill(status=204, headers=CORS)
    if re.match(r'^/calendar/v3/users/me/calendarList/', p): return await J0({'id': 'ok'})
    mm = re.match(r'^/calendar/v3/calendars/([^/]+)/events(?:/([\w-]+))?$', p)
    if mm:
        cid = mm.group(1).replace('%40', '@'); eid = mm.group(2)
        if cid not in CALS: return await J0({'error': 404}, 404)
        if m == 'POST' and not eid:
            eid = 'ev' + str(next(ids)).zfill(6); EVENTS[eid] = dict(json.loads(req.post_data), cal=cid); return await J0({'id': eid})
        if eid and eid not in EVENTS: return await J0({'error': 404}, 404)
        if m == 'PUT': EVENTS[eid] = dict(json.loads(req.post_data), cal=cid); return await J0({'id': eid})
        if m == 'DELETE': EVENTS.pop(eid); return await route.fulfill(status=204, headers=CORS)
    def J(obj, status=200): return route.fulfill(status=status, headers={**CORS, 'Content-Type': 'application/json'}, body=json.dumps(obj))
    p = u.path
    if p == '/drive/v3/files':
        if m == 'GET': return await J({'files': [{'id': f['id']} for f in run_q(qs.get('q', [''])[0])]})
        if m == 'POST':
            b = json.loads(req.post_data or '{}'); fid = new_id()
            FILES[fid] = {'id': fid, 'name': b['name'], 'mimeType': b.get('mimeType', ''), 'parents': b.get('parents') or ['root'], 'trashed': False, 'content': b''}
            return await J({'id': fid})
    mm = re.match(r'^/drive/v3/files/([\w-]+)$', p)
    if mm:
        f = FILES.get(mm.group(1))
        if not f: return await J({'error': {'code': 404}}, 404)
        if m == 'GET':
            if qs.get('alt') == ['media']: return await route.fulfill(status=200, headers=CORS, body=f['content'])
            return await J(meta_out(f))
        if m == 'PATCH':
            b = json.loads(req.post_data or '{}')
            if 'name' in b: f['name'] = b['name']
            if 'trashed' in b: f['trashed'] = b['trashed']
            for a in ','.join(qs.get('removeParents', [])).split(','):
                if a and a in f['parents']: f['parents'].remove(a)
            for a in ','.join(qs.get('addParents', [])).split(','):
                if a and a not in f['parents']: f['parents'].append(a)
            return await J({'id': f['id']})
        if m == 'DELETE': FILES.pop(f['id'], None); return await route.fulfill(status=204, headers=CORS)
    if p == '/upload/drive/v3/files' and m == 'POST':
        meta, content = parse_multipart(req); fid = new_id()
        FILES[fid] = {'id': fid, 'name': meta['name'], 'mimeType': meta.get('mimeType', ''), 'parents': meta.get('parents') or ['root'], 'trashed': False, 'content': content}
        return await J({'id': fid})
    mm = re.match(r'^/upload/drive/v3/files/([\w-]+)$', p)
    if mm and m == 'PATCH':
        f = FILES.get(mm.group(1))
        if not f: return await J({'error': {'code': 404}}, 404)
        if qs.get('uploadType') == ['multipart']:
            meta, content = parse_multipart(req); f['content'] = content
            if 'name' in meta: f['name'] = meta['name']
        else: f['content'] = req.post_data_buffer
        return await J({'id': f['id']})
    return await J({'error': 'não simulado ' + m + ' ' + p}, 400)

def children(pid, alive=True): return [f for f in FILES.values() if pid in f['parents'] and (not alive or not f['trashed'])]
def by_name(name, parent=None): return [f for f in FILES.values() if f['name'] == name and not f['trashed'] and (parent is None or parent in f['parents'])]
def tree(pid='root', ind=''):
    for f in sorted(children(pid), key=lambda x: x['name']):
        print(ind + ('📁 ' if f['mimeType'].endswith('folder') else '📄 ') + f['name'])
        if f['mimeType'].endswith('folder'): tree(f['id'], ind + '   ')

async def device(b):
    ctx = await b.new_context(viewport={'width': 390, 'height': 844}, is_mobile=True, has_touch=True, locale='pt-BR', timezone_id='America/Campo_Grande')
    await ctx.grant_permissions(['microphone'], origin='http://localhost:8765')
    await ctx.add_init_script("Object.defineProperty(Navigator.prototype,'webdriver',{get:()=>false});")
    page = await ctx.new_page()
    errs = []; page.on('pageerror', lambda e: errs.append(str(e)))
    await page.route('https://fonts.**', lambda r: r.abort())
    await page.route('https://www.googleapis.com/**', drive)
    await page.goto(URL); await page.wait_for_timeout(400)
    await page.click('#tOk'); await page.click('#tAccept'); await page.wait_for_timeout(200)
    await page.click('#gSkip'); await page.wait_for_timeout(200)
    await page.click('#suGo'); await page.wait_for_timeout(150)
    for _ in range(2):
        for d in '482916': await page.click(f'#pPad [data-d="{d}"]')
        await page.wait_for_timeout(150)
    await page.wait_for_selector('#rkVal', timeout=15000)
    await page.click('#rkAck'); await page.click('#rkDone'); await page.wait_for_timeout(300)
    # entra "com o Google" (o login de verdade não roda aqui; simula o acesso já concedido)
    await page.evaluate("""()=>{ if(!cfg.email) dropUntouchedExamples(); token='tok'; tokenExp=Date.now()+3600e3; cfg.calOk=true; cfg.linked=true; cfg.email='teste@exemplo.com'; cfg.guest=false; saveCfg(); render(); }""")
    return page, errs

async def settle(page):
    await page.wait_for_timeout(300)
    for _ in range(120):
        if await page.evaluate("!syncing && !uploading && pendingMedia()===0 && !db.dirty"): return True
        await page.wait_for_timeout(250)
    return False

async def main():
    async with async_playwright() as p:
        b = await p.chromium.launch(executable_path='/usr/bin/google-chrome', args=['--use-fake-ui-for-media-stream', '--use-fake-device-for-media-stream'])
        A, errsA = await device(b)
        # nova nota na pasta Trabalho com checklist, áudio gravado e foto
        await A.evaluate("()=>{ for(const nm of ['Trabalho','Pessoal']) if(!liveFolders().some(f=>f.name===nm)) db.folders.push({id:uid(),name:nm,updatedAt:Date.now()}); changed(); cur.folder=liveFolders().find(f=>f.name==='Trabalho').id; render(); }")
        await A.click('#fab'); await A.wait_for_timeout(200)
        await A.fill('#title', 'Deveres de amanhã'); await A.dispatch_event('#title', 'input')
        await A.evaluate("()=>{ ed.innerHTML='<ul class=\"ck\"><li data-done=\"1\">Ir para o trabalho</li><li data-done=\"0\">Levar o crachá</li></ul><p>Fé em Deus.</p>'; onEdit(); }")
        await A.click('#micBtn'); await A.click('#mRec'); await A.wait_for_timeout(1600); await A.click('#rStop'); await A.wait_for_timeout(600)
        im = Image.new('RGB', (800, 600), (30, 90, 200)); buf = io.BytesIO(); im.save(buf, 'JPEG')
        await A.set_input_files('#inCam', files=[{'name': 'image.jpg', 'mimeType': 'image/jpeg', 'buffer': buf.getvalue()}]); await A.wait_for_timeout(400)
        await A.click('#saveBtn'); await A.wait_for_timeout(200)
        await A.evaluate("syncNow(true)")
        check('sincronizou tudo', await settle(A))
        print('\n--- Drive depois do 1º envio ---'); tree(); print()
        check('gerente de requisições esperou e repetiu após 429', await A.evaluate("RQ.retries")>=1, str(await A.evaluate("({total:RQ.total,retries:RQ.retries})")))
        root = by_name('Não Esquece')
        check('pasta raiz Não Esquece', len(root) == 1)
        rid = root[0]['id']
        check('arquivo de dados na raiz', len(by_name('Não Esquece - dados do app (não apague).json', rid)) == 1)
        trab = by_name('Trabalho', rid)
        check('pasta Trabalho dentro da raiz', len(trab) == 1)
        nd = [f for f in children(trab[0]['id']) if f['name'].startswith('Deveres de amanhã — ')]
        check('pasta da nota com data e hora no nome', len(nd) == 1 and re.search(r'— \d\d-\d\d-\d{4} \d\dh\d\d$', nd[0]['name']), nd[0]['name'] if nd else '')
        inside = sorted(f['name'] for f in children(nd[0]['id']))
        check('texto, foto e áudio dentro da pasta da nota', any(n.endswith('.txt') for n in inside) and any(n.startswith('Foto ') for n in inside) and any(n.startswith('Áudio ') for n in inside), str(inside))
        t = [f for f in children(nd[0]['id']) if f['name'].endswith('.txt')][0]['content'].decode('utf-8-sig')
        check('texto da nota legível com checklist e datas', '☑ Ir para o trabalho' in t and '☐ Levar o crachá' in t and 'Criada em:' in t and 'Pasta: Trabalho' in t)
        check('texto lista os anexos já enviados', 'Áudio ' in t and 'ainda no celular' not in t, t.split('Anexos nesta pasta:')[-1][:120].replace('\n', ' | '))
        note_dir = nd[0]['id']

        # lembrete -> Google Agenda
        await A.evaluate("()=>{ const n=liveNotes().find(n=>n.title==='Deveres de amanhã'); n.remindAt='2026-11-06T08:00'; n.remindBefore=15; n.updatedAt=Date.now(); changed(); }")
        await A.evaluate("syncNow(true)"); await settle(A)
        ev = list(EVENTS.values())
        check('agenda Não Esquece criada', len(CALS) == 1 and list(CALS.values())[0]['summary'] == 'Não Esquece')
        check('evento com aviso 15 min antes no horário certo', len(ev) == 1 and ev[0]['start']['dateTime'] == '2026-11-06T08:00:00' and ev[0]['reminders']['overrides'][0]['minutes'] == 15 and ev[0]['summary'] == 'Deveres de amanhã', json.dumps(ev[0], ensure_ascii=False)[:160] if ev else '')
        meta = json.loads([f for f in FILES.values() if f['name'].endswith('.json')][0]['content']).get('meta', {})
        check('id da agenda vai no arquivo de dados (outro celular reaproveita)', meta.get('calendarId') in CALS)
        # mover para outra pasta
        await A.evaluate("()=>{ const n=liveNotes().find(n=>n.title==='Deveres de amanhã'); n.folderId=liveFolders().find(f=>f.name==='Pessoal')?.id||null; n.updatedAt=Date.now(); changed(); }")
        await A.evaluate("syncNow(true)"); await settle(A)
        pes = by_name('Pessoal', rid)
        check('mover nota move a pasta no Drive (com os anexos)', pes and note_dir in [f['id'] for f in children(pes[0]['id'])] and len(children(note_dir)) == 3)
        # renomear pasta
        await A.evaluate("()=>{ const f=liveFolders().find(f=>f.name==='Pessoal'); f.name='Casa'; f.updatedAt=Date.now(); changed(); }")
        await A.evaluate("syncNow(true)"); await settle(A)
        check('renomear pasta renomeia no Drive', by_name('Casa', rid) and not by_name('Pessoal', rid))
        # concluir: texto diz Concluída
        await A.evaluate("()=>{ const n=liveNotes().find(n=>n.title==='Deveres de amanhã'); setDone(n,true); changed(); }")
        await A.evaluate("syncNow(true)"); await settle(A)
        t = [f for f in children(note_dir) if f['name'].endswith('.txt')][0]['content'].decode('utf-8-sig')
        check('nota concluída aparece no texto do Drive', 'Situação: Concluída em' in t)
        check('concluir apaga o lembrete da agenda', len(EVENTS) == 0)
        await A.evaluate("()=>{ const n=liveNotes().find(n=>n.title==='Deveres de amanhã'); setDone(n,false); changed(); }")
        await A.evaluate("syncNow(true)"); await settle(A)
        check('voltar a pendente recria o lembrete', len(EVENTS) == 1)
        # lixeira e restaurar
        await A.evaluate("()=>trashNotesAct(liveNotes().filter(n=>n.title==='Deveres de amanhã'))")
        await A.evaluate("syncNow(true)"); await settle(A)
        lix = by_name('Lixeira', rid)
        check('apagar move a pasta da nota para Lixeira no Drive', lix and note_dir in [f['id'] for f in children(lix[0]['id'])])
        await A.evaluate("()=>restoreNotes(trashNotes())")
        await A.evaluate("syncNow(true)"); await settle(A)
        check('restaurar devolve a pasta da nota', note_dir in [f['id'] for f in children(by_name('Casa', rid)[0]['id'])])

        # segundo celular, mesma conta
        B, errsB = await device(b)
        await B.evaluate("syncNow(true)"); await settle(B)
        titles = await B.evaluate("liveNotes().map(n=>n.title)")
        check('outro celular recebe as notas', 'Deveres de amanhã' in titles, str(titles))
        cnt = len([f for f in FILES.values() if f['name'].startswith('Deveres de amanhã — ') and not f['trashed']])
        check('sem pasta duplicada no Drive', cnt == 1, str(cnt))
        check('outro celular sem pasta raiz duplicada', len(by_name('Não Esquece')) == 1)
        check('outro celular usa a mesma agenda', len(CALS) == 1 and await B.evaluate("cfg.calendarId") in CALS)
        await B.evaluate("()=>{ const n=liveNotes().find(n=>n.title==='Deveres de amanhã'); openNote(n.id); }")
        await B.wait_for_timeout(1200)
        check('outro celular baixa o áudio do Drive', await B.locator('#atts audio').count() == 1)
        # editar no B e sincronizar no A: continua uma pasta só
        await B.evaluate("()=>{ const n=noteById(cur.noteId); n.title='Deveres de amanhã cedo'; n.updatedAt=Date.now(); changed(); closeEditor(); }")
        await B.evaluate("syncNow(true)"); await settle(B)
        await A.evaluate("syncNow(true)"); await settle(A)
        live_dirs = [f for f in FILES.values() if f['name'].startswith('Deveres de amanhã') and f['mimeType'].endswith('folder') and not f['trashed']]
        check('renomear a nota em outro celular renomeia a mesma pasta', len(live_dirs) == 1 and live_dirs[0]['id'] == note_dir and 'cedo' in live_dirs[0]['name'], str([f['name'] for f in live_dirs]))
        # apagar para sempre
        await A.evaluate("()=>{ trashNotesAct(liveNotes().filter(n=>n.title.startsWith('Deveres'))); purgeNotes(trashNotes()); }")
        await A.evaluate("syncNow(true)"); await settle(A)
        check('apagar de vez manda a pasta da nota para a lixeira do Drive', FILES[note_dir]['trashed'])
        print('\n--- Drive no fim ---'); tree(); print()
        check('sem erros de script', not errsA and not errsB, ' | '.join((errsA + errsB)[:3]))
        bad = [c for c in CALLS if 'não simulado' in c]
        await b.close()
    print(f'\n{sum(r[1] for r in results)}/{len(results)} verificações passaram · {len(CALLS)} chamadas ao Drive')

asyncio.run(main())
