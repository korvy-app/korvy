"""Helper: add(key, text) appends to strings.xml (if new); edit(file, [(old, new)]) does exact replacements."""
import re
RES='app/src/main/res/values/strings.xml'
J='app/src/main/java/app/korvy/launcher/'
def xe(t):
    t=t.replace('&','&amp;').replace('<','&lt;').replace('>','&gt;').replace("'", "\\'").replace('"','\\"').replace('\n','\\n')
    if t[:1] in '@?': t='\\'+t
    if t != t.strip(' '):
        lead=len(t)-len(t.lstrip(' ')); trail=len(t)-len(t.rstrip(' ')); t='\\u0020'*lead+t.strip(' ')+'\\u0020'*trail
    return t
def add(pairs):
    x=open(RES).read(); have=set(re.findall(r'<string name="([^"]+)">', x)); out=''
    for k,v in pairs:
        if k in have: continue
        out+=f'    <string name="{k}">{xe(v)}</string>\n'
    open(RES,'w').write(x.replace('</resources>', out+'</resources>'))
def edit(f, pairs, imports=True):
    p=J+f; s=open(p).read()
    for a,b in pairs:
        assert a in s, (f, a[:90]); s=s.replace(a,b)
    if imports:
        for imp in ("import androidx.compose.ui.res.stringResource","import app.korvy.launcher.R"):
            if imp not in s and (imp.endswith("stringResource") or "package app.korvy.launcher." in s): s=s.replace("\nimport ","\n"+imp+"\nimport ",1)
    open(p,'w').write(s)
