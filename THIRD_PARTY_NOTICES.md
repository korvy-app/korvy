# Third-party notices

Korvy is free software under the GNU GPL v3 or later (see LICENSE). It includes or uses the following
third-party work, under their own licenses.

## Included in this repository or in the app
| Component | Use | License |
|---|---|---|
| [llama.cpp](https://github.com/ggml-org/llama.cpp) (git submodule) | Runs the on-device assistant models | MIT |
| [whisper.cpp](https://github.com/ggml-org/whisper.cpp) (git submodule) | On-device speech recognition | MIT |
| [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (fetched by `scripts/fetch-libs.sh`) | Natural voices and keyword spotting | Apache License 2.0 |
| `app/src/main/assets/kws-pieces.tsv` | Word-piece vocabulary taken from the sherpa-onnx keyword-spotting model `sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01` (its `bpe.model`) | Apache License 2.0 |
| [Apache Commons Compress](https://commons.apache.org/proper/commons-compress/) | Unpacking downloaded voice packs | Apache License 2.0 |
| AndroidX, Jetpack Compose, Material components, Kotlin and kotlinx.coroutines | App framework | Apache License 2.0 |
| Google Play Billing Library (Google Play build only) | The optional Pro purchase | Android Software Development Kit License |

## Downloaded at the user's request (not part of the app)
The app shows each item's maker and license before download.
- Assistant models (Gemma, Qwen, Llama, Phi, SmolLM) from Hugging Face, each under its own license
  (Gemma Terms of Use, Apache 2.0, Llama Community License, MIT, ...).
- Whisper speech models (ggml conversions from the whisper.cpp project), MIT.
- Natural voices from the sherpa-onnx releases: Kokoro (Apache 2.0); Piper (MIT) voices LJSpeech (LJ Speech
  dataset, public domain), Norman (LibriVox recordings, public domain), and the Spanish voices Ald (Ald Mexican
  Spanish dataset, Unlicense) and Davefx (Davefx dataset, CC0). Only voices whose training data
  allows commercial use are offered.
- The wake-word model from the sherpa-onnx releases, Apache 2.0.

Online data (weather from Open-Meteo, news feeds from NPR and BBC, Yahoo Finance quotes, DuckDuckGo answers) is
fetched only when the user turns those sources on, and is subject to those services' terms.
