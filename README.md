<div align="center">

<img src="brand/icon-512.png" width="128" alt="Lian+">

# Lian+

**Local AI. Limitless You.**

</div>


An Android app that downloads, serves and runs language and image models **entirely
on the phone**. No account, no API key, no inference server. It reads the device's
hardware first and tells you what it can realistically run before you spend four
gigabytes of mobile data finding out.

Built on [llama.cpp](https://github.com/ggml-org/llama.cpp) for text and
[stable-diffusion.cpp](https://github.com/leejet/stable-diffusion.cpp) for images.

---

## What it does

**Checks the device first.** Reads RAM, CPU cores and clocks, Arm extensions
(`dotprod`, `i8mm`), the Vulkan compute devices the driver actually offers, free
storage and thermal state, then gives a verdict: which model sizes fit, how much
context, how many threads, and a tokens/second estimate so the numbers are not a
surprise later.

Nothing in that verdict comes from a fraction of total RAM. Every "will this fit"
answer is measured at the moment it is asked — memory actually available, less
the threshold the system itself treats as low, less a reserve for the app. Ten
gigabytes free means ten gigabytes usable; one gigabyte free means you are told
honestly what fits in one gigabyte.

**Then measures it.** A couple of seconds after the app starts, in the
background, it times a matrix multiply on the CPU and on the GPU, measures
memory bandwidth and storage read speed, and keeps the result. You are never
asked to wait for it and never told it happened; the only difference is that
"about N tokens/s" stops being inferred from the CPU's feature flags and starts
coming from this phone. Every real generation feeds back into the same figure,
so it keeps improving — and tracks the device as it ages, fills up and
throttles.

**Finds and downloads models.** Searches the Hugging Face Hub for GGUF
repositories, lists every quantisation with its real size, marks the best fit for
your device and warns about the ones that will not fit. Downloads resume after
an interruption. There is also a short curated list, filtered to what the phone
can handle, for when you just want something that works.

**Chats, with the parts that make a small model useful:**

- *Context management* — the system prompt and the newest turns are pinned, older
  turns are fitted into whatever budget remains, and the overflow is compressed
  into a running summary rather than silently dropped.
- *Tool calling* — clock, calculator, device info, durable memory, document
  search, image generation, and optional web search and page fetch. Four
  different tool-call syntaxes are parsed, because local models are not
  consistent about which one they emit.
- *Retrieval over your own documents* — import a text file, it is chunked on
  sentence boundaries, embedded locally and searched by cosine similarity before
  each reply.
- *Prefix caching* — a follow-up question only re-evaluates the new tokens, not
  the whole conversation.

**Uses the GPU, when it is worth using.** Both engines carry a Vulkan backend,
and every Android phone from Android 10 onward ships a Vulkan 1.1 driver.
Diffusion is the clear win — it is pure compute, which is where a mobile GPU
beats the CPU several times over; token generation is bound by the memory bus
the two share anyway. The offload is offered on the strength of the measurement
rather than the renderer string: a GPU that timed at less than 1.5× the CPU is
not worth the memory it would take from the image model. Settings lists the
compute devices ggml found, with their memory, and when there is no usable one
it says which of the three reasons applies instead of showing a dead switch.

A driver that faults inside a compute dispatch takes the process with it and
leaves no exception behind. The probe arms a marker before it runs and clears it
after, so a marker still set at the next launch is evidence enough: that GPU is
left alone until you ask for it again.

**Loads models when you need them, and keeps them loaded.** Nothing loads at
startup. Ask a chat for an image and only the image model loads; type instead and
the text model loads, with the wait shown while it happens. If the measured
budget holds both, both stay resident. Only when it does not is one released —
and the app names which one, and why.

**Generates images** from a diffusion checkpoint, in a separate OS process so its
large transient allocations cannot take the language model down with them. Images
are generated inside the conversation, so a prompt can be refined in the thread
that produced it; tap one to generate again, edit the prompt, save or share.

Modern image models are not a checkpoint any more — Qwen-Image and Z-Image are a
diffusion transformer, a text encoder and a VAE in three separate files, often in
three separate repositories. The app reads which family a file belongs to, says
what else it needs before anything is downloaded, judges the memory question on
the whole set rather than on the transformer alone, and assembles the pipeline
for the engine. The curated list will queue all three parts in one tap.

**Serves an OpenAI-compatible API** on `127.0.0.1:8080`, so any tool that speaks
the OpenAI REST API can use the phone as its backend — with streaming, tools and
retrieval all working exactly as they do in the app. Optionally exposed to the
local network, behind an API key.

---

## Install

Direct download — **v0.6.0**, arm64-v8a, 96 MB:

```
https://github.com/Mo3bdlaa/Lian-plus-Local-AI-on-Android-/raw/apk/lian-plus-v0.6.0-arm64-v8a.apk
```

Open that on the phone and tap the downloaded file. The
[`apk` branch](https://github.com/Mo3bdlaa/Lian-plus-Local-AI-on-Android-/tree/apk)
always carries the current build and its checksum. Or build it yourself:

```bash
git clone --recurse-submodules https://github.com/Mo3bdlaa/Lian-plus-Local-AI-on-Android-
cd Lian-plus-Local-AI-on-Android-
./gradlew assembleRelease
adb install app/build/outputs/apk/release/app-release.apk
```

If you cloned without `--recurse-submodules`:

```bash
git submodule update --init --recursive
```

Requirements: JDK 17+, Android SDK 35, NDK 27.2.12479018, CMake 3.22.1, and
`glslc` (`apt install glslc`) to compile the Vulkan shaders. Without `glslc` the
build still succeeds — it prints a warning and produces a CPU-only APK.
See [docs/BUILD.md](docs/BUILD.md) for the details, including how to build
without the native engines for fast UI iteration.

Then, on the phone: open **Device** to see the verdict → **Models →
Recommended** → download one → **Chat**.

---

## بالعربي — البداية بسرعة

تطبيق أندرويد بيشغّل نماذج الذكاء الاصطناعي **على الموبايل نفسه**، من غير إنترنت
بعد التحميل ومن غير أي حساب أو مفتاح API.

**الخطوات:**

1. افتح تبويب **Device** — التطبيق بيقرأ إمكانيات الجهاز (الرام، المعالج، المساحة،
   الحرارة) ويقولك بالظبط أنهي أحجام نماذج تنفع عليه وكام توكن في الثانية متوقع.
2. افتح **Models → Recommended** — القائمة دي متفلترة على قد إمكانيات جهازك.
   اختار نموذج واضغط Download. التحميل بيكمّل من مكانه لو الاتصال قطع.
3. افتح **Chat** واتكلم عادي.

**للصور:** حمّل نموذج صور من نفس الشاشة (SD Turbo هو الأسرع على الموبايل — صورة في
٤ خطوات). تقدر تولّد الصور جوه الشات نفسه — بدّل زرار الكتابة لوضع الصورة واكتب
الوصف، وتقدر تعدّل الوصف وتعيد التوليد في نفس المحادثة.

**نماذج الصور الحديثة مش ملف واحد:** زي Qwen-Image و Z-Image، الموديل بيبقى تلات
ملفات — الـ transformer و الـ text encoder و الـ VAE، وساعات في تلات ريبو مختلفة.
التطبيق بيقرا الملف ويعرف هو من أنهي عيلة، ويقولك محتاج إيه كمان **قبل** ما تحمّل،
ويحسب الذاكرة على المجموعة كلها مش على ملف واحد. ومن تبويب **Picks** تقدر تحمّل
التلات ملفات بضغطة واحدة.

**الـ GPU:** التطبيق بيستخدم كارت الشاشة بتاع الموبايل عن طريق Vulkan. من تبويب
**Settings** تقدر تشوف الأجهزة اللي السواقة عرضتها وتختار GPU أو CPU وكام طبقة
تتنقل للـ GPU.

**القياس:** بعد ما تفتح التطبيق بثواني، بيقيس لوحده في الخلفية سرعة المعالج
والكارت والرام والتخزين، من غير ما يسألك أو يستناك. الأرقام اللي بتشوفها بعد كدا
(زي «حوالي كذا توكن في الثانية») بقت مقاسة من موبايلك انت، مش متوقعة من نوع
المعالج. وكل مرة بتولّد فيها كلام بيتحسّن الرقم أكتر.

**لو عايز تستخدم الموبايل كسيرفر:** تبويب **Server** بيشغّل API متوافق مع OpenAI
على `127.0.0.1:8080`، فأي برنامج بيتكلم مع OpenAI API يقدر يشتغل على الموبايل.

**ملاحظة عن Galaxy S25 Ultra:** بـ ١٢ جيجا رام الجهاز بيقع في فئة *Flagship* —
يعني نماذج لحد ١٤B بـ Q4، أو 8B بـ Q5/Q6، وسياق لحد ١٦ ألف توكن، وتوليد صور
بحجم ١٠٢٤ بكسل، مع Adreno 830 اللي بيشتغل عليه توليد الصور بالـ Vulkan.

الأرقام دي مش متكتبة في الكود — التطبيق بيقيس الرام المتاحة فعلاً في اللحظة
اللي بتسأله فيها، فلو الموبايل مزحوم بتطبيقات تانية هيقولك الحقيقة.

---

## What runs where

| | Process | Library |
|---|---|---|
| UI, chat, API server | main | — |
| Text generation, embeddings | main | `liblian_llm.so` (llama.cpp) |
| Image generation | `:imagegen` | `liblian_sd.so` (stable-diffusion.cpp) |

The split is deliberate — see
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md#why-image-generation-runs-in-its-own-process).

## Privacy

Conversations, documents and generated images live in the app's private storage
and are never uploaded. The app makes exactly two kinds of outbound request:

1. **Hugging Face**, to search for and download model files.
2. **Web search**, only if you switch it on in Settings — it is off by default,
   and the setting says plainly that it is the one feature that sends your words
   off the device.

Cloud backup is disabled for the app's data.

## Further reading

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — how the pieces fit, and why
- [docs/BUILD.md](docs/BUILD.md) — toolchain, build flags, troubleshooting
- [docs/MODELS.md](docs/MODELS.md) — picking a model and a quantisation
- [docs/API.md](docs/API.md) — the local OpenAI-compatible endpoints

## Licence

The app is MIT-licensed. The vendored engines keep their own licences (both MIT),
and every model you download carries the licence of its own repository — check it
before using a model commercially.
