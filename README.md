# Lian+ — APK downloads

This branch holds nothing but built APKs, so they have a direct download link.
It is not part of the source history; `main` is the code.

## Latest — v0.6.1

```
https://github.com/Mo3bdlaa/Lian-plus-Local-AI-on-Android-/raw/apk/lian-plus-v0.6.1-arm64-v8a.apk
```

| | |
|---|---|
| Version | 0.6.1 (versionCode 6010) |
| ABI | arm64-v8a |
| Minimum | Android 10 (API 29) |
| Size | 96 MB |
| SHA-256 | `03e7bf6073640825f58f4fea043cc5a731096469d60a22aa880c44ed71a307b6` |
| Signing key | `9C:2C:C2:D5:28:5A:93:DC:F3:45:52:7F:66:3E:BC:A1:C1:F7:4F:8C:5D:37:E7:66:B8:B8:1C:4D:5A:D3:3D:13` |

Installs over any earlier build from this branch — same key, higher versionCode.

## What changed in 0.6.1

**Each image model now uses its own settings.** Three numbers decide whether a
diffusion model makes a picture or a mess — step count, guidance scale and
resolution — and all three belong to the checkpoint, not to taste. The app had
one global default for every model: 4 steps at guidance 1.5, 512px. About right
for SD-Turbo, wrong for everything else.

The resolution default was the worst of it. Anything the version table did not
recognise fell through to 512, which is every family added since SDXL — all of
them 1024px models. Z-Image and Qwen-Image were being asked for half their
trained size, which is slower per useful pixel as well as visibly worse.

| | steps | guidance | size |
|---|---|---|---|
| Z-Image Turbo | 8 | 1.0 | 1024 |
| Z-Image | 20 | 5.0 | 1024 |
| Qwen-Image | 20 | 2.5 | 1024 |
| Flux | 20 | 1.0 | 1024 |
| SD-Turbo | 4 | 1.0 | 512 |

Every figure is the one in stable-diffusion.cpp's own documentation for that
family. A distilled model is trained to converge with no guidance at all, and
running it above 1.0 does not just look wrong — it doubles the work per step to
get there.

The family the engine reports is not always enough: "Z-Image" covers both the
base model and the Turbo distill, and they differ by 8 steps at scale 1 versus 20
at scale 5. The file name settles what the family does not carry. SD-Turbo is the
same problem the other way round — it reports as SD 2.x and is emphatically not
twenty steps at scale 7.

The values are written into the settings you can see and change, not applied
behind your back, and they are only replaced when you load a *different* model —
so tuning survives a reload.

## What changed in 0.6.0

**Image models that come in pieces now work.** SD 1.5 and SDXL ship everything
in one file. Everything since Flux — Qwen-Image, Z-Image — splits the pipeline:
the GGUF on the Hub is the diffusion transformer alone, and the text encoder and
VAE are separate downloads. The app used to hand the engine that one file and
report whatever it said on the way down.

Now the family is read out of the file — from its architecture key where there
is one, and from its tensor names where there is not, which is the only option
for the Z-Image GGUFs because they carry no metadata at all. From that the app
knows what else the model needs, says so by name before anything loads, and
passes the whole set to the engine when it has it.

Memory is judged on the set, not the transformer. A Qwen-Image checkpoint is
4 GB and its text encoder is twice that again — the decision is about twelve
gigabytes, and showing the first number alone is how a phone ends up holding
something it can never run.

**The browser lists safetensors.** Those VAEs and text encoders are published in
that format and the engine reads it directly, so filtering to GGUF made two
thirds of these pipelines invisible. Companion files are a deliberate download
now rather than a greyed-out row. A model split across several safetensors files
is grouped and marked unusable instead of being offered one part at a time.

**Picks can assemble a pipeline in one tap.** Z-Image Turbo is there — three
files from three repositories, about 6 GB together, 8 steps to a 1024px image.
Its text encoder is a stock Qwen3-4B-Instruct, so it stays a chat model too and
you are not paying for the same weights twice.

## What changed in 0.5.0

**The GPU is used.** Both engines carry a Vulkan backend. Settings shows the
compute devices the driver actually offered and lets you choose GPU or CPU and
how many layers to offload.

**The phone is measured, not guessed at.** A couple of seconds after startup, in
the background, it times a matrix multiply on the CPU and the GPU and measures
memory bandwidth and storage speed. Every "about N tokens/s" comes from your
phone after that, and real generations keep refining it. The GPU is only offered
when it measured at least 1.5× the CPU, and a driver that faults during the
probe is remembered and left alone.

**Free memory is measured too.** Nothing comes from a fraction of total RAM.
Ten gigabytes free means ten gigabytes usable.

**Models load when you need them, and stay loaded.** Nothing loads at startup;
if the measured budget holds both a text and an image model, both stay.

**Images are generated inside the conversation**, so a prompt can be refined in
the thread that produced it.

## Installing

Open the link on the phone and tap the downloaded file. Android will ask once
for permission to install from your browser.

You need roughly 1.5 GB free beyond the APK itself — the app is 96 MB, but the
first model you download is 1–5 GB on top.

## Note on the Releases page

The v0.1.0 asset under **Releases** was built before signing was configured and
carries a throwaway key, so it cannot be updated over. Use this branch until a
release is published with the signing secrets in place.

Full notes: [README on main](https://github.com/Mo3bdlaa/Lian-plus-Local-AI-on-Android-/blob/main/README.md)
