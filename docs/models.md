# Assistant models: how they were chosen

Chosen 2026-11-25. Re-run when new small models appear (`scripts/model-eval/`).

## Method
- **Accuracy** (`scripts/model-eval/eval.py`, run with llama.cpp's server on a Mac): Korvy's real system prompt, the
  6 examples and the JSON grammar (exported from the app by `ModelEvalExport`), 46 requests in English and Spanish
  (`cases.json`), phrased the way people talk. Requests that Korvy's plain-code parser already handles are left
  out (2), since they never reach a model. "Right" = the correct action *and* the key detail (who, where, what
  time, the fact asked). Spanish answers are also checked for being in Spanish.
- **Speed** on a Pixel 4a (Snapdragon 730G: 2 fast + 6 slow cores, 6 GB): llama-bench from the same llama.cpp
  build as the app; reading = prompt tokens/s on 8 threads, writing = generated tokens/s on the 2 fast cores
  (what Korvy uses), load = time to load the file.
- **In the app** for the finalists: the saved fixed prompt must be reused (first words in ~2.5 s on the 4a).
- **Licence**: only models whose licence allows commercial use (Korvy sells Pro).

## Results (4-bit files)
| Model | Right (of 44) | Spanish answers in Spanish | Load (s) | Read (tok/s) | Write (tok/s) | Licence | Verdict |
|---|---|---|---|---|---|---|---|
| qwen35-4b | 42 | 5/6 | 20.4 | 19 | 3.2 | Apache 2.0 | **Kept:** smartest, flagship phones |
| gemma4-e2b-qat | 40 | 5/6 | 6.0 | 98 | 6.9 | Apache 2.0 | **Recommended (new default)** |
| gemma4-e2b | 40 | 5/6 | 5.7 | 74 | 6.8 | Apache 2.0 | Same model; the QAT file reads faster |
| smollm3-3b | 40 | 4/6 | 11.3 | 27 | 5.6 | Apache 2.0 | **Kept:** fully open, newer phones |
| qwen3-4b-2507 | 40 | 5/6 | 15.0 | 20 | 4.3 | Apache 2.0 | No better than Gemma 4 E2B, much slower |
| phi4-mini | 39 | 4/6 | 14.5 | 21 | 4.3 | MIT | No better, much slower |
| gemma4-e4b | 39 | 5/6 | 22.9 | 26 | 3.6 | Apache 2.0 | No better, much slower |
| qwen25-1.5b | 37 | 4/6 | 6.3 | 59 | 10.8 | Apache 2.0 | **Kept:** fast and light |
| qwen3-1.7b | 37 | 5/6 | 6.8 | 55 | 9.6 | Apache 2.0 | Ties Qwen 2.5 1.5B; not worth a re-download |
| gemma3-1b | 32 | 4/6 | 4.2 | 42 | 12.6 | Gemma Terms | **Kept:** smallest (Gemma Terms) |
| llama32-3b | 32 | 5/6 | 12.3 | 23 | 5.3 | Llama 3.2 Community | Retired: less accurate, slower |
| qwen35-2b | 31 | 0/6 | 10.1 | 55 | 7.0 | Apache 2.0 | Sends questions to web search; no Spanish |
| qwen25-0.5b | 21 | 0/6 | 2.2 | 79 | 24.1 | Apache 2.0 | Retired: too often wrong |
| llama32-1b | 21 | 0/6 | 5.2 | 82 | 13.2 | Llama 3.2 Community | Retired: too often wrong |
| qwen35-0.8b | 18 | 0/6 | 3.2 | 109 | 15.8 | Apache 2.0 | Too often wrong |

## In-app check (Pixel 4a, first words after the question)
- Gemma 4 E2B (QAT): 2.6–2.8 s, after warm-up and after a restart (saved prompt restored in ~25 ms); writes ~5.8 tok/s.
- Qwen 3.5 4B: 6.6 s after a restart; its hybrid layers can't be rewound part-way, so Korvy reloads the saved fixed
  prompt (~0.2 s) before each question instead of re-reading it (it was 45–57 s before that fix). Writes ~2.8 tok/s:
  flagship phones only.
- Qwen 2.5 1.5B: 2.5–2.7 s; writes ~8.5 tok/s.

## Notes
- Qwen 3.5 small models answer best but are slow on mid-range phones; the 0.8B/2B are too unreliable for Korvy.
- Gemma 4's "QAT" file (Google's quantization-aware 4-bit) is as accurate as the regular 4-bit file and reads prompts
  ~30% faster.
- Retired models stay usable for people who downloaded them (shown only when installed).
