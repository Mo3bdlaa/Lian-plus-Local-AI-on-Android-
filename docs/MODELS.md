# Choosing a model

## The short version

Open the **Device** tab. The "largest model" figure is the number to stay under.
Then open **Models → Recommended**, which is already filtered to that number.

## Device tiers

Tiers come from total RAM, since that is what decides whether the weights stay
resident.

| RAM | Tier | Realistic models | Context |
|---|---|---|---|
| < 3 GB | Unsupported | — | — |
| 3–4 GB | Minimal | 1B–2B at Q4 | 2K |
| 4–6 GB | Entry | up to 3B–4B at Q4 | 4K |
| 6–8 GB | Capable | up to 8B at Q4 | 8K |
| 8–12 GB | High end | 8B at Q4/Q5 | 8K–32K |
| 12 GB+ | Flagship | up to 14B at Q4, 8B at Q5/Q6 | 16K–64K |

A Galaxy S25 Ultra (12 GB) lands in **Flagship**.

## Quantisation

Quantisation is how many bits each weight keeps. Fewer bits means a smaller,
faster model and worse answers.

| Tag | Bits | Verdict |
|---|---|---|
| `Q8_0` | 8.5 | Near-lossless. Only worth it for small models. |
| `Q6_K` | 6.6 | Excellent. Good choice when the memory is there. |
| `Q5_K_M` | 5.7 | Very good. A sensible step up from Q4 on 12 GB devices. |
| `Q4_K_M` | 4.9 | **The default.** Best quality-per-byte for phones. |
| `Q4_K_S` | 4.6 | Slightly smaller, slightly worse. |
| `IQ4_XS` | 4.3 | Smaller again; a little slower to decode. |
| `Q3_K_M` | 3.9 | Noticeably degraded. Only if Q4 will not fit. |
| `Q2_K`, `IQ2_*` | ~2.5 | Usually not worth running. |

Rule of thumb: **a bigger model at Q4 beats a smaller model at Q8** at the same
file size.

## Speed

Generation is memory-bandwidth bound — every token reads the whole model. So
tokens/second tracks file size, not parameter count, and halving the file size
roughly doubles the speed.

Prompt processing is compute bound instead, which is where `i8mm` and `dotprod`
matter. Without them, expect prefill to be 20–30% slower; the app reports which
extensions it found on the Device screen.

Two things make the phone slower than the estimate: thermal throttling after a
few minutes of sustained generation (the app halves the thread count when the
device reports as warm), and other apps evicting the model's pages from the page
cache.

## Context size

The KV cache grows linearly with context and is allocated up front. Two levers:

- **Context window** (Settings → Engine) — the direct control.
- **KV cache precision** — `q8_0` roughly halves the memory a given context
  needs, at a small quality cost. `q4_0` halves it again, and is noticeable.

If a model refuses to load with "not enough memory for a *N*-token context",
lower one of the two.

## Image models

| Model | Size | Steps | Notes |
|---|---|---|---|
| SD Turbo | ~1.9 GB | 1–4 | The only one that is genuinely comfortable on a phone. |
| Stable Diffusion 1.5 | ~1.6 GB | 20–30 | Widely compatible; a minute or two per image. |
| SDXL Turbo | ~3.9 GB | 1–4 | 1024px and much better output. Needs 12 GB. |

Turbo checkpoints want a **guidance scale near 1.0** — the usual 7.5 produces
washed-out results with them.

Tiled VAE decoding is always on. Without it the decode step allocates a single
buffer large enough to get the process OOM-killed at 768px and above.

## Embedding models

Needed for document search. All are tiny.

| Model | Size | Dimensions |
|---|---|---|
| all-MiniLM-L6-v2 | 25 MB | 384 |
| BGE Small EN v1.5 | 36 MB | 384 |
| Nomic Embed Text v1.5 | 84 MB | 768, 8K input |

Without one, document search falls back to keyword matching.

## Sideloading

Drop `.gguf` files into the app's model directory and they are picked up on the
next launch:

```bash
adb push model.gguf /sdcard/Download/
# then use a file manager, or:
adb shell run-as com.lian.plus mkdir -p files/models/text
adb shell run-as com.lian.plus cp /sdcard/Download/model.gguf files/models/text/
```

The GGUF header is parsed on import, so architecture, trained context and chat
template are picked up automatically.
