# Local API

The **Server** tab starts an OpenAI-compatible HTTP API backed by the model
loaded on the device. Loopback only by default.

```
http://127.0.0.1:8080/v1
```

To reach it from a computer over USB:

```bash
adb forward tcp:8080 tcp:8080
```

To reach it from another device on the same Wi-Fi, switch on "Expose to the
local network" — and set an API key first, since anything on that network can
then connect.

## Authentication

None unless an API key is set. When one is, send it as usual:

```
Authorization: Bearer <key>
```

`/health` is always reachable without it.

## Endpoints

### `GET /health`

```json
{"status":"ok","engine":"ready","model":"Qwen_Qwen2.5-3B-Instruct-GGUF_qwen2.5-3b-instruct-q4_k_m","context":8192}
```

### `GET /v1/models`

Lists what is installed, with extra fields under `lian` (kind, size, quantisation,
trained context).

### `POST /v1/chat/completions`

Standard request and response shapes. `stream: true` gives server-sent events in
the usual `chat.completion.chunk` form, terminated by `data: [DONE]`.

```bash
curl http://127.0.0.1:8080/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{
    "model": "local",
    "stream": true,
    "messages": [{"role": "user", "content": "Explain mmap in two sentences"}],
    "temperature": 0.7,
    "max_tokens": 256
  }'
```

Honoured: `messages`, `stream`, `temperature`, `top_p`, `top_k`, `max_tokens`,
`frequency_penalty`, `presence_penalty`, `seed`, `stop`.

A `system` message replaces the device's configured system prompt for that
request. Both the plain string and the multimodal array form of `content` are
accepted; only `text` parts are read.

Tools and document retrieval run exactly as they do in the app, driven by the
device's own settings. A remote caller cannot switch on web search that the user
left off.

### `POST /v1/completions`

Raw completion. The prompt is sent to the model verbatim — no chat template, no
tools. Useful for fill-in-the-middle and for models with no template at all.

### `POST /v1/embeddings`

Needs an embedding model loaded, otherwise returns 503. `input` takes a string
or an array of strings. Vectors are L2-normalised, so a dot product is already
the cosine similarity.

### `POST /v1/images/generations`

Needs an image model loaded. Returns base64 PNG in `data[0].b64_json` — a file
path would be useless to a caller outside the app sandbox.

```bash
curl http://127.0.0.1:8080/v1/images/generations \
  -H 'Content-Type: application/json' \
  -d '{"prompt": "a lighthouse at dusk", "size": "512x512"}'
```

Step count, guidance and sampler come from the Images screen's settings.

## Errors

Standard OpenAI error envelope:

```json
{"error":{"message":"No model is loaded on the device.","type":"model_not_loaded","code":null}}
```

| Status | Meaning |
|---|---|
| 400 | Malformed JSON, or a required field is missing |
| 401 | Wrong or missing API key |
| 404 | Unknown endpoint |
| 503 | No model of the required kind is loaded |
| 500 | The engine failed |

## Using it from an OpenAI client

```python
from openai import OpenAI

client = OpenAI(base_url="http://127.0.0.1:8080/v1", api_key="not-needed")

stream = client.chat.completions.create(
    model="local",
    messages=[{"role": "user", "content": "Hello"}],
    stream=True,
)
for chunk in stream:
    print(chunk.choices[0].delta.content or "", end="")
```

## Notes

- One generation runs at a time. Concurrent requests queue rather than
  interleaving — the engine is single-context by design.
- The foreground notification exists so it is always visible that the phone is
  listening. Stopping it from the notification stops the server.
- CORS is permissive (`Access-Control-Allow-Origin: *`) so a local web page can
  call it; combined with loopback-only binding, this reaches no further than the
  device.
