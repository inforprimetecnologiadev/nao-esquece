"""Copia os textos de legal.py para dentro do app.html (termos, privacidade, novidades) e gera o CHANGELOG.md."""
import importlib.util, os, re, sys

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location('legal', HERE + '/legal.py')
L = importlib.util.module_from_spec(spec); spec.loader.exec_module(L)

APP = os.path.join(HERE, '..', 'app.html')
s = open(APP, encoding='utf-8').read()

def put(tid, html):
    global s
    pat = re.compile(r'<template id="%s">.*?</template>' % tid, re.S)
    assert len(pat.findall(s)) == 1, tid
    s = pat.sub(lambda m: '<template id="%s">%s</template>' % (tid, html), s)

news = ''.join(f"<h2>Versão {v}</h2><p class='lg-meta'>{d}</p><ul>{''.join(f'<li>{i}</li>' for i in items)}</ul>" for v, d, items in L.CHANGELOG)
put('tplTerms', L.TERMS)
put('tplPrivacy', L.PRIVACY)
put('tplNews', news)
s, n = re.subn(r"TERMS_VERSION='[^']*'", "TERMS_VERSION='%s'" % L.TERMS_VERSION, s)
assert n == 1
open(APP, 'w', encoding='utf-8').write(s)

md = ['# Novidades do Não Esquece', '', 'Cada versão também aparece no app em **Ajustes › Sobre**.', '']
for v, d, items in L.CHANGELOG:
    md += [f'## {v} — {d}', ''] + [f'- {i}' for i in items] + ['']
open(os.path.join(HERE, '..', '..', 'CHANGELOG.md'), 'w', encoding='utf-8').write('\n'.join(md))
print('textos legais e novidades atualizados; termos', L.TERMS_VERSION)
