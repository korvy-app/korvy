# Which spellings of "Korvy" does the model hear in each voice? One keyword line per spelling, labelled by spelling.
import sys, wave, numpy as np, sherpa_onnx, glob, collections

M, B = sys.argv[1], sys.argv[2].rstrip("/")
import os
PIECES = os.path.join(os.path.dirname(os.path.abspath(__file__)), "../../app/src/main/assets/kws-pieces.tsv")
P={}
for line in open(PIECES):
    p,s=line.rstrip('\n').split('\t'); P[p]=float(s)
def enc(text):
    out=[]
    for w in text.upper().split():
        w='▁'+w; n=len(w); best=[(-1e18,None)]*(n+1); best[0]=(0,None)
        for i in range(1,n+1):
            for j in range(max(0,i-20),i):
                sub=w[j:i]
                if sub in P and best[j][0]>-1e17:
                    sc=best[j][0]+P[sub]
                    if sc>best[i][0]: best[i]=(sc,j)
        seg=[];i=n
        while i>0: j=best[i][1]; seg.append(w[j:i]); i=j
        out+=seg[::-1]
    return " ".join(out)
spellings = sys.argv[3].split(",")
thr = sys.argv[4]
kw = "\n".join(f"{enc('hey '+s)} :2.0 #{thr} @{s}" for s in spellings)+"\n"
open(os.path.join(B, "keywords.txt"),"w").write(kw)
m="epoch-12-avg-2-chunk-16-left-64.int8.onnx"
kws = sherpa_onnx.KeywordSpotter(tokens=f"{M}/tokens.txt", encoder=f"{M}/encoder-{m}", decoder=f"{M}/decoder-{m}", joiner=f"{M}/joiner-{m}", num_threads=2, keywords_file=os.path.join(B, "keywords.txt"), provider="cpu")
table=collections.defaultdict(dict)
for path in sorted(glob.glob(f"{B}/*.wav")):
    phrase, voice = path.split('/')[-1][:-4].split('__')
    w = wave.open(path); a = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32)/32768
    s = kws.create_stream(); pad=np.zeros(8000,np.float32)
    s.accept_waveform(16000, np.concatenate([pad,a,pad,pad])); s.input_finished()
    hits=[]
    while kws.is_ready(s):
        kws.decode_stream(s); r=kws.get_result(s)
        if r: hits.append(r); kws.reset_stream(s)
    table[phrase][voice]=",".join(hits) or "·"
voices=sorted({v for d in table.values() for v in d})
print(f"threshold {thr}; spellings: " + "; ".join(f"{s}={enc(s)}" for s in spellings))
print(f"{'phrase':14}"+"".join(f"{v[:8]:>10}" for v in voices))
for ph in ["hey_korvy"]+[p for p in table if p!="hey_korvy"]:
    print(f"{ph:14}"+"".join(f"{table[ph].get(v,'')[:9]:>10}" for v in voices))
