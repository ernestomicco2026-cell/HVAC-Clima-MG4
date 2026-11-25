#!/usr/bin/env python3
"""Controlla i testi di MG4 Clima (1.4.6). Uso: python3 controlla-testi.py  (dalla cartella MG4Clima)

1. ogni chiave di i18n.py ha 8 traduzioni non vuote;
2. ogni R.string.x usato nel codice esiste in i18n.py (e segnala le chiavi mai usate);
3. i segnaposti (%1$d, %1$s, %%) sono gli stessi in tutte le lingue;
4. nessun testo per l'utente scritto direttamente nel codice (toast, setText, makeText, text(...) della vista);
5. testi rimasti uguali all'inglese in un'altra lingua (da guardare: possono essere nomi propri o sigle).
Esce con errore se trova problemi dei punti 1-4.
"""
import glob
import importlib.util
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("i18n", os.path.join(HERE, "i18n.py"))
i18n = importlib.util.module_from_spec(spec)
spec.loader.exec_module(i18n)
S, LANGS = i18n.S, i18n.LANGS

errors, notes = [], []

# 1. traduzioni complete
for k, v in S.items():
    if len(v) != len(LANGS):
        errors.append("%s: %d traduzioni invece di %d" % (k, len(v), len(LANGS)))
        continue
    for lang, t in zip(LANGS, v):
        if not t or not t.strip():
            errors.append("%s: testo vuoto in %s" % (k, lang))

# 2. chiavi usate nel codice
src = {}
for p in glob.glob(os.path.join(HERE, "app/java/**/*.java"), recursive=True):
    with open(p, encoding="utf-8") as f:
        src[p] = f.read()
used = set()
for text in src.values():
    used |= set(re.findall(r"R\.string\.([a-z0-9_]+)", text))
xml_used = set()
for p in glob.glob(os.path.join(HERE, "app/AndroidManifest.xml")) + glob.glob(os.path.join(HERE, "app/res/xml/*.xml")):
    with open(p, encoding="utf-8") as f:
        xml_used |= set(re.findall(r"@string/([a-z0-9_]+)", f.read()))
for k in sorted(used | xml_used):
    if k not in S:
        errors.append("R.string.%s usato nel codice ma assente in i18n.py" % k)
unused = sorted(set(S) - used - xml_used)
if unused:
    notes.append("chiavi mai usate nel codice (%d): %s" % (len(unused), ", ".join(unused)))

# 3. segnaposti uguali in tutte le lingue
ph = re.compile(r"%(?:\d+\$)?[sdf]|%%")
for k, v in S.items():
    if len(v) != len(LANGS):
        continue
    ref = sorted(ph.findall(v[1]))
    for lang, t in zip(LANGS, v):
        if sorted(ph.findall(t)) != ref:
            errors.append("%s: segnaposti diversi in %s (%s invece di %s)" % (k, lang, ph.findall(t), ref))

# 4. testi per l'utente scritti nel codice
pat = re.compile(r'(?:toast|setText|makeText|setTitle|setMessage)\s*\((?:[^()"]*,\s*)?"([^"]*[A-Za-zÀ-ÿ][^"]*)"')
view_pat = re.compile(r'\btext\(c,\s*"([^"]*[A-Za-zÀ-ÿ]{3,}[^"]*)"')
allowed = {"LO", "HI", "OFF", "A", "AUTO", "AUTO ·", "A/C", "ECO", "–"}   # sigle uguali in tutte le lingue
for p, text in src.items():
    for n, line in enumerate(text.split("\n"), 1):
        if "Store.event" in line or "log(" in line or "lp.setTitle" in line:   # titoli interni delle finestre
            continue
        for m in list(pat.finditer(line)) + list(view_pat.finditer(line)):
            lit = m.group(1).strip()
            if lit in allowed or not lit:
                continue
            errors.append("%s:%d testo scritto nel codice: \"%s\"" % (os.path.relpath(p, HERE), n, lit))

# 5. uguali all'inglese
same = []
for k, v in S.items():
    if len(v) != len(LANGS):
        continue
    for lang, t in zip(LANGS, v):
        if lang != "en" and t == v[1] and re.search(r"[a-z]{4,}", t):
            same.append("%s[%s]" % (k, lang))
if same:
    notes.append("uguali all'inglese (%d, da guardare): %s" % (len(same), ", ".join(same)))

print("%d testi × %d lingue · %d chiavi usate nel codice" % (len(S), len(LANGS), len(used | xml_used)))
for n in notes:
    print("NOTA:", n)
for e in errors:
    print("ERRORE:", e)
if errors:
    sys.exit("%d problemi" % len(errors))
print("tutto a posto")
