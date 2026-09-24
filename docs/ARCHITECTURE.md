# Architecture

## Layout

```
app/src/main/
  cpp/                 JNI bridges + CMake (llama.cpp, stable-diffusion.cpp)
  aidl/                Cross-process interface for the image worker
  java/com/lian/plus/
    core/device/       Hardware profiling and the capability verdict
    core/model/        Model registry, GGUF header parsing
    core/             LianRuntime (process-wide wiring), ChatOrchestrator
    hub/               Hugging Face client, resumable downloader, curated list
    llm/               llama.cpp binding, engine, chat templates, context manager
    image/             stable-diffusion binding, worker service, client
    rag/               Chunking, embeddings, vector search, pipeline
    tools/             Tool contract, registry, call parser, built-in tools
    server/            HTTP server and OpenAI-compatible routes
    data/              Room database and preferences
    ui/                Compose screens
native/
  llama.cpp/           submodule
  stable-diffusion.cpp/ submodule
```

## The native layer

Two shared libraries, each statically linking its own copy of ggml, compiled
with `-fvisibility=hidden` and linked with `-Wl,--exclude-libs,ALL` so only the
JNI entry points appear in the dynamic symbol table.

`liblian_llm.so` is built by adding llama.cpp as a CMake subdirectory.
`liblian_sd.so` is built through `ExternalProject_Add`, which gives
stable-diffusion.cpp its own CMake scope and therefore its own `ggml` target.

That indirection is not incidental. Both projects vendor ggml, usually at
different revisions. Adding both as subdirectories in one CMake project means
whichever is added second silently reuses the first one's ggml — the build
succeeds and produces a library compiled against headers that do not match the
code it links to. Keeping the scopes separate costs one extra CMake invocation
and removes the failure mode entirely.

`-DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+i8mm` enables the instructions that make
quantised matmuls fast on modern phones. ggml still probes at runtime, so a
device without them falls back to plain NEON rather than crashing.

### The Vulkan backend

Both engines are built with `GGML_VULKAN=ON`. Vulkan is the only GPU API worth
targeting here: every Android device from Android 10 onward ships a Vulkan 1.1
driver, where OpenCL is vendor-specific and absent on most retail ROMs.

The shaders are compiled on the build host by `glslc` — 1238 SPIR-V variants for
the text engine, 1611 for the image engine — and embedded in the libraries. That
is where the APK's size goes, and it is why `lian.vulkan=false` halves it.

Two header sets have to agree with the NDK's loader, which implements 1.3.275:
`Vulkan-Headers` for the C++ bindings ggml uses, and `SPIRV-Headers` for the
opcode definitions. Both are vendored as submodules pinned to exactly that tag.
Taking whatever the host happens to have installed produces a library that
compiles and then misbehaves against an older driver.

ggml gates the backend behind `find_package(SPIRV-Headers CONFIG REQUIRED)`.
Under the Android toolchain, `CMAKE_FIND_ROOT_PATH_MODE_PACKAGE` confines that
search to the sysroot, so a host-installed package is invisible however it was
installed — and because ggml treats the gate as optional at the top level, the
Vulkan backend was silently dropped from the Gradle build while the same command
line worked outside it. The build now generates a minimal
`SPIRV-HeadersConfig.cmake` pointing at the vendored copy and sets
`SPIRV-Headers_DIR` to it. The package only satisfies the gate; the headers
themselves reach the compiler through the include path either way.

**Where the GPU actually helps.** Diffusion is pure compute and gains several
times over the CPU. Token generation is memory-bandwidth bound, and on a phone
the GPU shares that bus with the CPU — offloading layers moves the work without
moving the bottleneck, and costs GPU memory that the image model may want. So
the offload is a per-model setting rather than a default, and `ComputeDevices`
reports what `ggml_backend_dev_*` actually enumerated, with each device's free
and total memory, instead of assuming a GPU exists.

### Why image generation runs in its own process

`ImageGenService` is declared with `android:process=":imagegen"`. Three reasons,
in order of how much they matter:

1. **Memory.** A diffusion run allocates well over a gigabyte transiently.
   Native heap is rarely handed back to the OS, so doing this in the main
   process would permanently inflate the app's footprint and make the language
   model the next thing Android kills. Killing a separate process reclaims
   every byte.
2. **Isolation of the two ggml copies.** The worker never loads
   `liblian_llm.so`, so the two static copies can never share an address space,
   whatever the linker flags say.
3. **Crash containment.** A native fault in the image engine takes down only
   the worker. The chat session survives and the client rebinds.

Results cross the boundary as a file path, not a byte array — a 1024×1024 PNG is
far past Binder's transaction limit, and both processes share the same app
sandbox anyway.

## Text generation

`LlmEngine` confines every native call to one dedicated thread. llama.cpp
contexts are not thread-safe, and pinning them to a thread is cheaper and
harder to get wrong than locking each entry point. A separate `Mutex` enforces
the logical rule that only one generation runs at a time, since the UI and the
HTTP server can both ask at once.

### Prefix caching

The native layer records which tokens are currently in the KV cache. Each
generation compares the new prompt against that record, keeps the common
prefix, drops the divergent tail with `llama_memory_seq_rm`, and decodes only
what is left. A follow-up question in a long conversation therefore costs the
new tokens rather than the whole transcript — the difference between a
multi-second pause before every reply and an immediate one.

### Streaming and UTF-8

Tokens are byte sequences, not characters: one token routinely ends halfway
through a multi-byte character, which matters constantly for Arabic and emoji.
The JNI layer accumulates bytes and only hands text to Kotlin at a complete
UTF-8 boundary, holding back the incomplete tail until the bytes that finish it
arrive.

### Stop sequences

Matched on decoded text rather than on tokens, because a stop string rarely
aligns with a token boundary. Output is held back while it could still be the
start of a stop sequence, so a partial match never flashes on screen and then
disappears.

## Context management

`ContextManager` decides what actually reaches the model:

1. The system prompt and retrieved passages are pinned — they are instructions,
   and dropping them changes behaviour.
2. The most recent turns are pinned, because a reply that has lost the question
   it answers is worse than no reply.
3. What remains is walked newest-first until the budget runs out.
4. The overflow is compressed into a running summary with one cheap extra
   generation, so older context degrades gradually instead of vanishing.

The budget reserves a quarter of the window (at least 256 tokens) for the reply,
plus an allowance for the chat template's control tokens.

Retrieved passages are inserted immediately before the newest user message,
which is where models attend to them most reliably.

## Tool calling

`ToolRegistry` renders a compact description of the active tools into the system
prompt — small models follow terse instructions far better than a full JSON
Schema dump. `ToolCallParser` accepts four syntaxes (`<tool_call>` tags, Llama's
`<function=…>`, fenced JSON, and a bare object whose `name` matches a registered
tool), because local models are inconsistent even within one family.

The loop runs for at most three rounds, then answers with what it has. Tool
output is streamed to the UI as a trail of one-line summaries; the raw JSON
never reaches the transcript, and the stream is held back the moment a reply
starts looking like a tool call.

Network tools are registered disabled. They are the only thing in the app that
sends the user's words off the device, and a remote API caller cannot switch
them on — tool availability is read from the device's own settings.

## Retrieval

Documents are chunked at paragraph boundaries first and sentence boundaries
second, with overlap so a fact that straddles a boundary stays findable from
either side. Embedding happens at import, not at query time; re-encoding the
corpus for every message would turn a millisecond search into a minute-long one.

The embedding model gets its own llama.cpp context. Switching one context
between generation and embedding modes would tear down the KV cache each time,
throwing away exactly the prefix cache that makes chat feel responsive — and
embedding models are tens of megabytes, so the second copy is cheap.

Vectors are L2-normalised in native code, so cosine similarity is a dot product
and search is a linear scan. For the few thousand chunks a phone holds, that
beats any index structure and needs no rebuild when a document is added.

When no embedding model is installed, search falls back to keyword matching
rather than disappearing.

## The HTTP server

Hand-rolled HTTP/1.1 on a `ServerSocket`. The only thing it has to do well is
stream tokens as they are produced; an embedded server framework would be dead
weight in the APK. One request per connection thread suits a server whose
bottleneck is a single-threaded engine.

It runs in a foreground service because Android suspends background processes
and every in-flight request would stall. Binding is loopback-only unless the
user explicitly opts into LAN exposure.

Both the chat screen and the API go through `ChatOrchestrator`, so a request
made over the API behaves exactly like one typed into the app.

## Capability analysis

Earlier versions budgeted a fixed 45% of *total* RAM for weights. That is wrong
in both directions: a phone with 10 GB genuinely free was refused models it
would have run comfortably, and a phone with 3 GB free out of 8 was happily
recommended something that could not fit. Total RAM says what the device was
sold with; it says nothing about what is available while the user has forty tabs
open.

`MemoryBudget` therefore derives nothing from a fraction of anything. Every
answer is read from `ActivityManager.MemoryInfo` at the moment the question is
asked:

```
free for a new model = availMem − threshold − runtime reserve
free if evicted      = availMem + resident − threshold − runtime reserve
```

`threshold` is the level at which Android itself starts killing background
processes. It is device-specific and the OS publishes it, which makes it the
correct margin to respect rather than one invented here. The runtime reserve
(384 MB) covers the KV cache, compute buffers, the UI and the JVM — the parts
that are not weights.

The verdict is five-valued rather than yes/no, because weights are mmap'd:
exceeding the budget degrades into paging from storage, not an immediate kill.
`TIGHT` means slow, not fatal, and is reported as such. `FITS_AFTER_EVICTION`
means the model fits once what is already loaded is released, which is a
different sentence to show the user than "too large".

`CapabilityAnalyzer` uses the same live reading, and falls back to a fraction of
total RAM only when the platform reports no available figure at all.

The tokens/second estimate treats generation as memory-bandwidth bound: one
pass over the weights per token, with a conservative sustained-bandwidth figure
per CPU capability class.

## Image pipelines

SD 1.x and SDXL ship the UNet, the text encoder and the VAE in one checkpoint.
Everything since Flux splits them: the GGUF published on the Hub is the
diffusion transformer alone, and the encoder and VAE are separate files, often
in separate repositories and several gigabytes each. Handing the engine that one
file produces a load failure with nothing in it to explain the cause.

`DiffusionArch` names the family and what it requires. Detection reads the file
rather than its name, in two steps, because neither is enough on its own:

* `general.architecture`, where a converter wrote one. The Qwen-Image 2.1 GGUF
  carries exactly three metadata keys, of which that is the only useful one.
* The tensor names otherwise. The Z-Image GGUFs published by the
  stable-diffusion.cpp author carry **no key/value pairs at all**, so the names
  are the only signal left — and they are what the engine itself matches on.
  `cap_embedder.`/`context_refiner.` is Z-Image, `double_blocks.` with
  `single_blocks.` is Flux, and `first_stage_model.` means the file is complete
  whatever else is in it.

The name is consulted last, as a guess for labelling a repository before
anything has been downloaded.

`ImagePipelineResolver` then matches the required components against what is
installed, preferring a companion from the same repository — a user with two
VAEs almost certainly wants the one that shipped beside this checkpoint, and the
other produces images that are subtly wrong rather than an error.

Two consequences are worth stating:

* **Which engine parameter the primary file takes follows from the family.** A
  complete checkpoint goes to `model_path`; a bare transformer goes to
  `diffusion_model_path`. Passing one as the other is the original failure, so
  it is decided from the detection rather than guessed at load time.
* **Memory is judged on the set.** A Qwen-Image transformer is 4 GB and its text
  encoder is twice that again. `ModelResidency` budgets the pipeline total,
  because weighing the transformer alone is how a device ends up holding a
  checkpoint it can never run.

A Qwen language model deserves a note: Qwen-Image and Z-Image condition on one,
and the Z-Image reference invocation loads a stock `Qwen3-4B-Instruct` GGUF as
its text encoder. So being usable as an encoder is recorded as an extra role
rather than a reclassification — the file stays a chat model, and the user does
not download the same weights twice. The inverse trap is sharper:
`qwen-image-2.1-UC-Q4_0.gguf` also contains "qwen", and reading that as the
encoder puts the checkpoint in the encoder slot and leaves the pipeline with no
transformer at all. A file that names its own diffusion family is never a
companion.

### Settings that belong to the model

Step count, guidance scale and output resolution are properties of a
checkpoint, not preferences. A distilled turbo model is trained to converge in a
handful of steps with no classifier-free guidance at all; running it at the
twenty steps and scale 7 that SD 1.5 wants takes five times as long and comes
out scorched, and CFG above 1.0 on such a model doubles the work per step to get
there. Flux is guided through a separate distilled-guidance input the engine
already defaults, so CFG on top of it is pure waste.

`ImageModelProfiles` holds one profile per family, every figure taken from the
reference invocation in stable-diffusion.cpp's own docs. Two directions of
ambiguity are worth naming, because the family string alone gets both wrong:

* "Z-Image" covers the base model and the Turbo distill, which differ by 8 steps
  at scale 1 versus 20 at scale 5. The file name settles it.
* SD-Turbo reports as "SD 2.x" and is a 512px four-step model, so following the
  family would ask it for twenty steps it does not need.

The resolution default used to be 512 for anything unrecognised, which is every
family added since SDXL — all of them 1024px models. It is now 1024, with 512
reserved for the families that really are 512.

`LianRuntime.loadImageModel` writes the profile into settings rather than
applying it invisibly, so the values on screen are the ones in use and remain
editable, and only when the loaded model changes, so a reload does not discard
tuning. Both entry points go through it.

## Measuring the device

`DeviceBenchmark` runs once, in the background, a couple of seconds after
startup, and the user is never asked to wait for it. It exists because none of
the cheap signals predict throughput: an SoC name, a set of Arm feature flags
and a GPU renderer string are all within reach for free and all wrong by up to a
factor of two, depending on the ROM, the thermal state and whether the driver
will actually run compute shaders.

Three measurements:

* **Matmul GFLOP/s**, per backend device, through `ggml_mul_mat` with F16
  weights against an F32 activation — the operation inference spends its time
  in, so the number transfers. The first dispatch is untimed: it pays for
  shader compilation and buffer residency, which is startup cost, not
  throughput.
* **Memory read bandwidth**, over a buffer larger than any phone's last-level
  cache and stepped one cache line at a time. This is what token generation is
  bound by — one full pass over the weights per token, from main memory. The
  buffer is scaled to free memory rather than skipped on a small device.
* **Storage read speed**, which decides whether a model too large to stay
  resident is merely slow or unusable, since mmap'd weights evicted under
  pressure are re-read from there.

The GPU verdict is the point. A device level with the CPU is not worth the
memory it takes from the image model, and on a shared memory bus a small margin
vanishes under thermal load, so offload is offered above 1.5x and not below.

**Surviving a driver fault.** A Vulkan driver that faults inside a compute
dispatch kills the process; no exception reaches Kotlin and nothing runs
afterwards. `BenchmarkStore` therefore arms a marker before the GPU is touched
and clears it after, suspending until each write reaches disk — a marker held in
memory would die with the process and tell us nothing. A marker still set at the
next launch is the only evidence such a crash leaves, and it is enough: the GPU
is not offered again until the user asks for it from the Device screen.

**Feedback from real work.** `recordGenerationSpeed` back-solves the bandwidth
that would explain an observed tokens/second and smooths it into the stored
figure at a quarter weight. The estimate for a model the user has not downloaded
therefore improves from the ones they have, and tracks the phone as it ages,
fills up and throttles. Generations under 24 tokens are ignored, being mostly
prompt processing and scheduling noise.

## Model residency

`ModelResidency` owns which models are in memory. Three rules, in order:

1. **Nothing loads until something is asked of it.** Opening the app loads no
   weights. A chat turn loads the text model; asking for an image loads the
   image model. Starting the app is therefore instant regardless of what is
   installed, and a session that only ever generates images never pays for the
   language model.
2. **If both fit, both stay.** A phone with the memory to hold a 4 GB text model
   and a 1.5 GB checkpoint at once keeps them both resident, so switching
   between text and images costs nothing.
3. **Eviction only when the measurement says so**, and never silently. When
   `MemoryBudget` reports the new model needs the space, the other is released
   and `ensure()` returns which one, so the UI can say what was given up rather
   than appearing to forget.

Thermal state is read before each model load; a warm phone gets half the
threads, because thermal throttling makes extra threads actively counter-
productive.
