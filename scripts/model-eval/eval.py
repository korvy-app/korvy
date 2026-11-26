#!/usr/bin/env python3
"""Scores candidate assistant models on Korvy's real prompt, examples and JSON grammar.

Usage: eval.py LLAMA_SERVER MODEL.gguf NAME [--out results.jsonl]
Needs app/build/model-eval.json (./gradlew testPlayDebugUnitTest --tests '*ModelEvalExport*') and cases.json.
Requests that Korvy's plain code handles (handledByCode) are skipped: they never reach a model.
Accuracy doesn't depend on the machine; speed is measured separately on a phone (llama-bench).
"""
import json, os, subprocess, sys, time, urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
server, model, name = sys.argv[1], sys.argv[2], sys.argv[3]
out_path = sys.argv[sys.argv.index("--out") + 1] if "--out" in sys.argv else os.path.join(HERE, "results.jsonl")

exp = json.load(open(os.path.join(ROOT, "app/build/model-eval.json")))
cases = [c for c in json.load(open(os.path.join(HERE, "cases.json"))) if not exp["handled"].get(c["q"], False)]
NOW = "(It's Wednesday, November 25, 2026, 4:05 PM.)"
PORT = 8791

proc = subprocess.Popen([server, "-m", model, "--port", str(PORT), "-c", "4096", "--jinja", "-t", "8",
                         "--no-webui", "-np", "1"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
try:
    for _ in range(240):
        try:
            if urllib.request.urlopen(f"http://127.0.0.1:{PORT}/health", timeout=2).status == 200: break
        except Exception: time.sleep(1)
    else:
        sys.exit(f"{name}: server didn't start")

    def ask(system, q):
        msgs = [{"role": "system", "content": system}]
        for u, a in exp["examples"]:
            msgs += [{"role": "user", "content": u}, {"role": "assistant", "content": a}]
        msgs.append({"role": "user", "content": f"{NOW} {q}"})
        body = {"messages": msgs, "grammar": exp["grammar"], "temperature": 0.2, "max_tokens": 220, "seed": 1,
                "chat_template_kwargs": {"enable_thinking": False}}
        req = urllib.request.Request(f"http://127.0.0.1:{PORT}/v1/chat/completions", json.dumps(body).encode(),
                                     {"Content-Type": "application/json"})
        r = json.load(urllib.request.urlopen(req, timeout=600))
        return r["choices"][0]["message"]["content"]

    SPANISH = [" el ", " la ", " de ", " es ", " que ", " los ", " las ", " un ", " una ", "á", "é", "í", "ó", "ú", "ñ", "¿", "¡"]
    score = {"n": 0, "action": 0, "full": 0, "es_lang": 0, "es_n": 0}
    rows = []
    for c in cases:
        system = exp["system_es"] if c["lang"] == "es" else exp["system_en"]
        raw = ask(system, c["q"])
        try:
            j = json.loads(raw); act, arg = j.get("action", ""), str(j.get("arg", ""))
        except Exception:
            act, arg = "(bad json)", raw
        ok_act = act == c["action"]
        ok_arg = ok_act and (c["arg"] is None or any(k.lower() in arg.lower() for k in c["arg"]))
        score["n"] += 1; score["action"] += ok_act; score["full"] += ok_arg
        if c.get("spanish"):
            score["es_n"] += 1
            score["es_lang"] += ok_act and any(w in f" {arg.lower()} " for w in SPANISH)
        rows.append({"q": c["q"], "want": c["action"], "got": act, "arg": arg[:160], "ok": ok_arg})
    with open(out_path, "a") as f:
        f.write(json.dumps({"model": name, "score": score, "rows": rows}, ensure_ascii=False) + "\n")
    print(f"{name:16s} actions {score['action']}/{score['n']}  fully right {score['full']}/{score['n']}  "
          f"Spanish answers in Spanish {score['es_lang']}/{score['es_n']}")
finally:
    proc.terminate(); proc.wait()
