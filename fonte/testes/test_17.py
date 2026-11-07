"""1.7: WhatsApp na ordem, PDF com áudio, Word (.docx/.doc) com MP3/MP4 dentro, planilha do relatório."""
import asyncio, os, sys, base64, struct, zipfile, io, re, subprocess, shutil, json
from playwright.async_api import async_playwright

URL = 'http://localhost:8765/index.html'
HERE = os.path.dirname(os.path.abspath(__file__)) + '/'
M = HERE + 'm17/'
OUT = HERE + 'out17/'
os.makedirs(OUT, exist_ok=True)
results = []
def check(name, ok, extra=''):
    results.append((name, bool(ok))); print(('OK  ' if ok else 'FAIL'), name, extra)
b64 = lambda p: base64.b64encode(open(p, 'rb').read()).decode()

# ---------- leitor de arquivo composto (CFB) só para conferir ----------
def cfb_read(data):
    assert data[:8] == bytes.fromhex('D0CF11E0A1B11AE1'), 'assinatura'
    ss = 1 << struct.unpack_from('<H', data, 30)[0]; ms = 1 << struct.unpack_from('<H', data, 32)[0]
    nfat, dirS = struct.unpack_from('<II', data, 44); cut = struct.unpack_from('<I', data, 56)[0]
    mfS, nmf, difS, ndif = struct.unpack_from('<IIII', data, 60)
    sec = lambda i: data[ss * (i + 1): ss * (i + 2)]
    difat = list(struct.unpack_from('<109I', data, 76)); s = difS
    for _ in range(ndif):
        v = struct.unpack('<128I', sec(s)); difat += v[:127]; s = v[127]
    fat = []
    for f in difat[:nfat]: fat += struct.unpack('<128I', sec(f))
    def chain(st):
        out = []
        while st < 0xFFFFFFFA: out.append(st); st = fat[st]
        return out
    rd = lambda st: b''.join(sec(i) for i in chain(st))
    d = rd(dirS); ents = []
    for i in range(len(d) // 128):
        e = d[i * 128:(i + 1) * 128]; nl = struct.unpack_from('<H', e, 64)[0]
        ents.append(dict(name=e[:max(0, nl - 2)].decode('utf-16le'), type=e[66], color=e[67], l=struct.unpack_from('<I', e, 68)[0], r=struct.unpack_from('<I', e, 72)[0],
                         c=struct.unpack_from('<I', e, 76)[0], clsid=e[80:96], start=struct.unpack_from('<I', e, 116)[0], size=struct.unpack_from('<I', e, 120)[0]))
    root = ents[0]; mini = rd(root['start'])[:root['size']] if root['start'] < 0xFFFFFFFA else b''
    mfat = []
    for i in chain(mfS) if nmf else []: mfat += struct.unpack('<128I', sec(i))
    def rmini(st, size):
        out = b''
        while st < 0xFFFFFFFA: out += mini[st * ms:(st + 1) * ms]; st = mfat[st]
        return out[:size]
    seen, stack = {}, [root['c']]
    while stack:
        i = stack.pop()
        if i >= 0xFFFFFFFA: continue
        e = ents[i]; seen[e['name']] = rmini(e['start'], e['size']) if e['size'] < cut else rd(e['start'])[:e['size']]
        stack += [e['l'], e['r']]
    return seen, root
def pkg_native(nat):  # conteúdo do Ole10Native (sem o tamanho na frente)
    i = 0; assert struct.unpack_from('<H', nat, 0)[0] == 2; i = 2
    z = lambda i: (nat[i:nat.index(b'\0', i)].decode('latin1'), nat.index(b'\0', i) + 1)
    label, i = z(i); path, i = z(i); i += 4
    tl = struct.unpack_from('<I', nat, i)[0]; i += 4 + tl
    dl = struct.unpack_from('<I', nat, i)[0]; i += 4
    return label, nat[i:i + dl]

async def main():
    async with async_playwright() as p:
        b = await p.chromium.launch(executable_path='/usr/bin/google-chrome')
        ctx = await b.new_context(viewport={'width': 390, 'height': 844}, device_scale_factor=2, is_mobile=True, has_touch=True, locale='pt-BR', timezone_id='America/Campo_Grande')
        await ctx.add_init_script("""Object.defineProperty(Navigator.prototype,'webdriver',{get:()=>false});
          window.__shared=[]; Object.defineProperty(navigator,'canShare',{configurable:true,value:()=>true});
          Object.defineProperty(navigator,'share',{configurable:true,value:async d=>{ window.__shared.push({app:d.app||'',text:d.text||'',files:(d.files||[]).map(f=>[f.name,f.type,f.size])}); }});""")
        page = await ctx.new_page()
        errors, wa = [], []
        page.on('pageerror', lambda e: errors.append(str(e)))
        await page.route('https://fonts.**', lambda r: r.abort())
        async def wa_route(r):
            wa.append(r.request.url); await r.abort()
        await ctx.route('https://api.whatsapp.com/**', wa_route)
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

        # nota como a do print: texto solto e depois checklist, com áudio, foto e vídeo
        await page.click('#fab'); await page.wait_for_timeout(300)
        await page.fill('#title', 'Teste 1')
        nid = await page.evaluate('cur.noteId')
        await page.evaluate(f"""(async()=>{{ const n=noteById('{nid}'); n.html='Chuva cai cai chuva<div>Está aqui o <b>aplicativo</b></div><ul class="ck"><li data-done="0">Vamos comprar</li><li data-done="1">Pão</li></ul><ol><li>Primeiro</li></ol>';
          const add=async(name,type,b64,extra)=>{{ const b=await (await fetch('data:'+type+';base64,'+b64)).blob(); const a=Object.assign({{id:uid(),name,type,size:b.size,addedAt:Date.now()}},extra||{{}}); await IDB.put(a.id,b); n.attachments=n.attachments||[]; n.attachments.push(a); }};
          await add('Foto 06-11-2026 19h00.png','image/png','{b64(M+"t.png")}');
          await add('Áudio 06-11-2026 18h18.mp3','audio/mpeg','{b64(M+"t.mp3")}',{{duration:3}});
          await add('196039.mp4','video/mp4','{b64(M+"t.mp4")}');
          await add('Áudio antigo.wav','audio/wav','{b64(M+"t.wav")}',{{duration:3}});
          n.updatedAt=Date.now(); changed(); ed.innerHTML=n.html; renderAtts(); }})()""")
        await page.wait_for_timeout(800)

        # texto do WhatsApp com a quebra de linha antes da checklist
        wt = await page.evaluate(f"waText(noteById('{nid}'))")
        check('WhatsApp: checklist em linha própria', '\n☐ Vamos comprar' in wt and '\n☑ Pão' in wt and 'chuva\nEstá' in wt, repr(wt))
        tx = await page.evaluate(f"txt(noteById('{nid}').html)")
        check('texto simples também quebra a linha', 'chuva\nEstá' in tx and '\n☐ Vamos comprar' in tx, repr(tx))
        check('ordem dos anexos igual à tela (áudios primeiro)', await page.evaluate(f"attOrder(noteById('{nid}')).map(a=>a.type)") == ['audio/mpeg', 'audio/wav', 'image/png', 'video/mp4'])
        check('cartão do áudio mostra MP3', await page.locator('#atts .aud-h .m3').count() == 1)

        # WhatsApp em dois passos
        await page.evaluate(f"sendWhatsApp(noteById('{nid}'))"); await page.wait_for_timeout(400)
        st = await page.inner_text('#sheet')
        check('janela mostra a ordem: texto, áudios, foto, vídeo', st.index('Texto da nota') < st.index('Áudio') < st.index('Foto') < st.index('Vídeo'), st[:300])
        await page.screenshot(path=OUT + 'wa1.png')
        await page.click('#wsGo'); await page.wait_for_timeout(800)
        check('passo 1 abre o WhatsApp com o texto', wa and 'text=' in wa[-1] and 'Vamos%20comprar' in wa[-1], str(wa[-1:])[:160])
        check('botão vira 2. Enviar os 4 anexos', '2. Enviar os 4 anexos' in await page.inner_text('#wsGo'))
        await page.screenshot(path=OUT + 'wa2.png')
        await page.click('#wsGo'); await page.wait_for_timeout(1500)
        sh = await page.evaluate('window.__shared')
        check('passo 2 manda os anexos na ordem, direto para o WhatsApp, sem repetir o texto',
              sh and sh[-1]['app'] == 'whatsapp' and sh[-1]['text'] == '' and [f[1] for f in sh[-1]['files']] == ['audio/mpeg', 'audio/wav', 'image/png', 'video/mp4'], str(sh[-1:])[:300])

        # PDF: áudio MP3 dentro (com tocador do Acrobat) e o WAV como anexo do PDF
        pdf64 = await page.evaluate(f"""(async()=>{{ const n=noteById('{nid}'); const pages=await renderPages(n,{{}}); const pgs=[];
            for(let i=0;i<pages.length;i++){{ const c=pages[i]; pgs.push({{w:c.width,h:c.height,data:await canvasJPEG(c),media:pages.media.filter(m=>m.page===i)}}); }}
            return blobB64(buildPDF(pgs)); }})()""")
        open(OUT + 'nota.pdf', 'wb').write(base64.b64decode(pdf64))
        from pypdf import PdfReader
        r = PdfReader(OUT + 'nota.pdf')
        att = r.attachments
        check('PDF abre e tem os anexos MP3, MP4 e WAV', set(k.split(' - ', 1)[-1] for k in att) >= {'Áudio 06-11-2026 18h18.mp3', '196039.mp4', 'Áudio antigo.wav'}, str(list(att))[:200])
        mp3in = [v for k, v in att.items() if k.endswith('.mp3')]
        check('MP3 dentro do PDF é o mesmo arquivo', mp3in and mp3in[0][0] == open(M + 't.mp3', 'rb').read())
        scr = []
        for pg in r.pages:
            for a in (pg.get('/Annots') or []):
                o = a.get_object()
                if o.get('/Subtype') == '/Screen':
                    scr.append(o['/A'].get_object()['/R'].get_object()['/C'].get_object()['/CT'])
        check('tocador do Acrobat para o áudio MP3 e para o vídeo', sorted(scr) == ['audio/mpeg', 'video/mp4'], str(scr))
        subprocess.run(['pdftoppm', '-r', '50', '-png', '-f', '1', '-l', '2', OUT + 'nota.pdf', OUT + 'pdfpg'], capture_output=True)

        # Word .docx
        dx = await page.evaluate(f"(async()=>blobB64(await buildDocx(noteById('{nid}'),{{'x':'y'}})))()")
        open(OUT + 'Teste 1.docx', 'wb').write(base64.b64decode(dx))
        z = zipfile.ZipFile(OUT + 'Teste 1.docx')
        check('docx é um ZIP íntegro', z.testzip() is None, str(z.namelist()))
        import xml.dom.minidom
        for nm in z.namelist():
            if nm.endswith('.xml') or nm.endswith('.rels'): xml.dom.minidom.parseString(z.read(nm))
        check('XML do docx bem formado', True)
        import docx
        d = docx.Document(OUT + 'Teste 1.docx')
        ps = [x.text for x in d.paragraphs]
        check('docx: título, texto e checklist', ps[0] == 'Teste 1' and any('Vamos comprar' in x and '☐' in x for x in ps) and any('☑' in x and 'Pão' in x for x in ps) and any(x.startswith('1.') and 'Primeiro' in x for x in ps), str(ps)[:300])
        emb = sorted(n for n in z.namelist() if n.startswith('word/embeddings/'))
        check('docx: 3 objetos embutidos (2 áudios e o vídeo)', len(emb) == 3, str(emb))
        want = {'Audio 06-11-2026 18h18.mp3': open(M + 't.mp3', 'rb').read(), '196039.mp4': open(M + 't.mp4', 'rb').read(), 'Audio antigo.wav': open(M + 't.wav', 'rb').read()}
        got = {}
        for e in emb:
            ss, root = cfb_read(z.read(e))
            assert root['clsid'] == bytes([0x0C, 0, 3, 0, 0, 0, 0, 0, 0xC0, 0, 0, 0, 0, 0, 0, 0x46])
            nat = ss['\x01Ole10Native']; size = struct.unpack_from('<I', nat, 0)[0]
            label, data = pkg_native(nat[4:4 + size]); got[label] = data
            check(f'objeto {label}: CompObj "Package"', b'Package' in ss.get('\x01CompObj', b''))
        check('docx: MP3, MP4 e WAV dentro são iguais aos originais', got == want, str({k: len(v) for k, v in got.items()}))
        dxml = z.read('word/document.xml').decode()
        check('docx: objeto Pacote com figura', dxml.count('ProgID="Package"') == 3 and dxml.count('<v:imagedata') == 3 and dxml.count('<pic:pic>') == 1)
        # Word .doc (RTF)
        dc = await page.evaluate(f"(async()=>blobB64(await buildDoc(noteById('{nid}'),{{}})))()")
        open(OUT + 'Teste 1.doc', 'wb').write(base64.b64decode(dc))
        rtf = open(OUT + 'Teste 1.doc', 'rb').read().decode('latin1')
        check('doc começa como documento do Word (RTF)', rtf.startswith('{\\rtf1') and rtf.rstrip().endswith('}'))
        objs = re.findall(r'\{\\\*\\objdata\n([0-9a-f\n]+)\}', rtf)
        got2 = {}
        for h in objs:
            o = bytes.fromhex(h.replace('\n', ''))
            assert struct.unpack_from('<II', o, 0) == (0x501, 2) and o[12:20] == b'Package\0'
            i = 20 + 8; ns = struct.unpack_from('<I', o, i)[0]; label, data = pkg_native(o[i + 4:i + 4 + ns]); got2[label] = data
        check('doc: MP3, MP4 e WAV dentro iguais aos originais', got2 == want, str({k: len(v) for k, v in got2.items()}))
        # LibreOffice abre os dois
        for f in ['Teste 1.docx', 'Teste 1.doc']:
            rr = subprocess.run(['soffice', '--headless', '--convert-to', 'pdf', '--outdir', OUT + 'lo', OUT + f], capture_output=True, text=True, timeout=180)
            pdf = OUT + 'lo/' + f.rsplit('.', 1)[0] + '.pdf'
            ok = os.path.exists(pdf) and len(PdfReader(pdf).pages) >= 1
            text = ''.join(pg.extract_text() or '' for pg in PdfReader(pdf).pages) if ok else ''
            check(f'LibreOffice abre o {f.split(".")[-1]} com o texto', ok and 'Vamos comprar' in text and 'Teste 1' in text, (rr.stderr or '')[-200:])
            if ok: shutil.copy(pdf, OUT + ('lo_' + f.split('.')[-1] + '.pdf'))

        # relatório em planilha
        await page.evaluate(f"""(()=>{{ const n=noteById('{nid}'); n.done=true; n.doneAt=Date.now()-3600e3+1234; n.updatedAt=Date.now(); changed();
          const t={{id:'tk'+uid(),list:'tl-main',title:'Ligar para o cliente',createdAt:Date.now()-7200e3,updatedAt:Date.now(),done:true,doneAt:Date.now()-1800e3+567,subs:[{{id:'s1',title:'a',done:true}},{{id:'s2',title:'b',done:false}}],star:true}}; TL(); TK().push(t);
          const d=new Date(); const t2={{id:'tk'+uid(),list:'tl-main',title:'Pagar boleto',createdAt:Date.now(),updatedAt:Date.now(),date:dkey(d),time:'00:01'}}; TK().push(t2); changed(); }})()""")
        await page.click('#backBtn'); await page.wait_for_timeout(300)
        await page.click('#bnav [data-tab="rel"]'); await page.wait_for_timeout(400)
        check('relatório tem o botão da planilha', await page.is_visible('#rpXls') and 'Planilha do Excel' in await page.inner_text('#rpXls'))
        await page.screenshot(path=OUT + 'relatorio.png')
        await page.click('#rpXls'); await page.wait_for_timeout(1500)
        check('planilha pronta para enviar ou salvar', 'Arquivo pronto' in await page.inner_text('#sheet') and '.xlsx' in await page.inner_text('#sheet'))
        await page.screenshot(path=OUT + 'planilha_pronta.png')
        xl = await page.evaluate("(async()=>{ const x=repSheets(rep.p); return [await blobB64(await xlsxBlob(x.sheets,'t')), x.r.done.map(n=>n.doneAt)]; })()")
        open(OUT + 'relatorio.xlsx', 'wb').write(base64.b64decode(xl[0]))
        import openpyxl, datetime
        wb = openpyxl.load_workbook(OUT + 'relatorio.xlsx')
        check('planilha com as abas Itens, Por dia e Resumo', wb.sheetnames == ['Itens', 'Por dia', 'Resumo'], str(wb.sheetnames))
        ws = wb['Itens']; head = [c.value for c in ws[1]]
        check('cabeçalho com data, hora e situação', head[:4] == ['Data', 'Hora', 'Dia da semana', 'Situação'] and 'Concluída em' in head, str(head))
        rows = list(ws.iter_rows(min_row=2, values_only=True))
        check('3 itens (nota, tarefa concluída, tarefa atrasada)', len(rows) == 3 and sorted(r[4] for r in rows) == ['Nota', 'Tarefa', 'Tarefa'], str([r[:6] for r in rows]))
        ci = head.index('Concluída em'); nota = [r for r in rows if r[5] == 'Teste 1'][0]
        dt = nota[ci]; real = datetime.datetime.fromtimestamp(xl[1][[i for i, x in enumerate(xl[1])][0]] / 1000) if False else None
        js_ms = await page.evaluate(f"noteById('{nid}').doneAt")
        import zoneinfo
        loc = datetime.datetime.fromtimestamp(js_ms / 1000, zoneinfo.ZoneInfo('America/Campo_Grande')).replace(tzinfo=None)
        check('data e hora com segundos certinhos', isinstance(dt, datetime.datetime) and abs((dt - loc).total_seconds()) < 1 and dt.second == loc.second, f'{dt} vs {loc}')
        check('formato dd/mm/aaaa hh:mm:ss', ws.cell(row=2, column=ci + 1).number_format == 'dd/mm/yyyy hh:mm:ss' and ws.cell(row=2, column=1).number_format == 'dd/mm/yyyy' and ws.cell(row=2, column=2).number_format == 'hh:mm:ss')
        check('filtro e cabeçalho fixo', ws.auto_filter.ref and ws.auto_filter.ref.startswith('A1:') and ws.freeze_panes == 'A2', f'{ws.auto_filter.ref} {ws.freeze_panes}')
        st = {r[5]: r[3] for r in rows}
        check('situação: Concluída e Atrasada', st.get('Teste 1') == 'Concluída' and st.get('Pagar boleto') == 'Atrasada' and st.get('Ligar para o cliente') == 'Concluída', str(st))
        tk = [r for r in rows if r[5] == 'Ligar para o cliente'][0]
        check('tarefa: lista, estrela e subtarefas', tk[6] == 'Minhas tarefas' and tk[8] == 'Com estrela' and tk[13] == '1 de 2 feitas', str(tk))
        check('nota: pasta, anexos e checklist', nota[6] == 'Sem pasta' and '2 áudios' in (nota[14] or '') and '1 foto' in nota[14] and nota[13] == '1 de 2 feitos', str(nota[6:16]))
        pdia = list(wb['Por dia'].iter_rows(min_row=2, values_only=True))
        check('Por dia: hoje com 2 concluídas e 1 atrasada', len(pdia) == 1 and pdia[0][2] == 2 and pdia[0][4] == 1 and pdia[0][7] == 3, str(pdia))
        rr = subprocess.run(['soffice', '--headless', '--convert-to', 'csv', '--outdir', OUT + 'lo', OUT + 'relatorio.xlsx'], capture_output=True, text=True, timeout=180)
        csv = open(OUT + 'lo/relatorio.csv', encoding='utf-8', errors='replace').read() if os.path.exists(OUT + 'lo/relatorio.csv') else ''
        check('LibreOffice abre a planilha', 'Teste 1' in csv and 'Concluída' in csv, csv[:200])
        await page.evaluate("closeSheet()")
        check('sem erros de script', not errors, ' | '.join(errors[:3]))
        await b.close()
    fails = [r for r in results if not r[1]]
    print(f'\n{len(results)-len(fails)}/{len(results)} verificações passaram')

asyncio.run(main())
