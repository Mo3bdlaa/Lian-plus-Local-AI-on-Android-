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

`CapabilityAnalyzer` budgets about 45% of total RAM for weights. Weights are
mmap'd, so they sit in the page cache rather than the app's heap — but the
kernel evicts them under pressure, and every eviction turns the next token into
a disk read. Forty-five percent keeps the working set resident while leaving
Android and the foreground app alive.

The tokens/second estimate treats generation as memory-bandwidth bound: one
pass over the weights per token, with a conservative sustained-bandwidth figure
per CPU capability class.

Thermal state is read before each model load; a warm phone gets half the
threads, because thermal throttling makes extra threads actively counter-
productive.
