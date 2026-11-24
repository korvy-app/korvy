"""Build values-<qual>/strings.xml from a translation table.

Table format (UTF-8): one entry per line, `key=text`. Plurals: `key=one-text|other-text`
for keys that are <plurals> in English. Blank lines and lines starting with # are ignored.
Checks: key exists in English, placeholders (%1$s, %d, %.1f, %%) match the English exactly.
Usage: python3 mkres.py <table> <qualifier>   (run from the repo root)
"""
import re, sys
sys.path.insert(0, __file__.rsplit('/', 1)[0])
from strs import xe

EN = 'app/src/main/res/values/strings.xml'
table, qual = sys.argv[1], sys.argv[2]
en = open(EN, encoding='utf-8').read()
en_str = dict(re.findall(r'<string name="([^"]+)">(.*?)</string>', en))
en_pl = {k: re.findall(r'<item quantity="(\w+)">(.*?)</item>', body)
         for k, body in re.findall(r'<plurals name="([^"]+)">(.*?)</plurals>', en)}

def unesc(t):
    return (t.replace('\\u0020', ' ').replace("\\'", "'").replace('\\"', '"')
             .replace('&lt;', '<').replace('&gt;', '>').replace('&amp;', '&'))

PH = re.compile(r'%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[sdfx%]')
def ph(t): return sorted(PH.findall(t))

out, errors, seen = [], [], set()
for n, line in enumerate(open(table, encoding='utf-8'), 1):
    line = line.rstrip('\n')
    if not line.strip() or line.startswith('#'): continue
    if '=' not in line: errors.append(f'{n}: no ='); continue
    k, v = line.split('=', 1); k = k.strip()
    if k in seen: errors.append(f'{n}: duplicate {k}'); continue
    seen.add(k)
    if k in en_pl:
        parts = v.split('|')
        if len(parts) != 2: errors.append(f'{n}: plural {k} needs one|other'); continue
        for p in parts:
            if ph(p) != ph(unesc(en_pl[k][0][1])): errors.append(f'{n}: {k} placeholders {ph(p)}')
        out.append(f'    <plurals name="{k}"><item quantity="one">{xe(parts[0])}</item>'
                   f'<item quantity="other">{xe(parts[1])}</item></plurals>')
    elif k in en_str:
        if ph(v) != ph(unesc(en_str[k])): errors.append(f'{n}: {k} placeholders {ph(v)} vs {ph(unesc(en_str[k]))}')
        out.append(f'    <string name="{k}">{xe(v)}</string>')
    else:
        errors.append(f'{n}: unknown key {k}')

missing = [k for k in list(en_str) + list(en_pl) if k not in seen]
if errors:
    print('\n'.join(errors)); sys.exit(1)
import os
d = f'app/src/main/res/values-{qual}'; os.makedirs(d, exist_ok=True)
open(f'{d}/strings.xml', 'w', encoding='utf-8').write('<resources>\n' + '\n'.join(out) + '\n</resources>\n')
print(f'{qual}: {len(out)} entries; {len(missing)} left in English: {" ".join(missing)}')
