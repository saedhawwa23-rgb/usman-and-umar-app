---
title: USMAN AND UMAR Direct Browser Use Executor
emoji: "⚡"
colorFrom: blue
colorTo: purple
sdk: docker
app_port: 7860
---

# USMAN AND UMAR Direct Browser Use Executor

This FastAPI service runs the repository's `browser_use_interactive.py` helper in the same Python runtime. It does **not** forward requests to an external OpenSwarm endpoint and has no mock-success path. Successful `/execute` responses contain the actual final text returned by Browser Use.

The helper is intentionally bounded to read-only public-web research. It requires one or more explicit public `http(s)` URLs in the prompt, restricts Browser Use navigation to the hostnames found in those URLs, and runs at most eight steps. It will not sign in, submit forms, send messages, publish content, make purchases, or modify accounts or data. Website content is untrusted and is not allowed to override the read-only policy.

## Run in Google Colab

Use `USMAN_COLAB_BACKEND.ipynb` from the repository. It installs the FastAPI and Browser Use dependencies, installs Chromium and `cloudflared`, starts the server on port 7860, then launches a Cloudflare Quick Tunnel.

Add these values in Colab **Secrets** and grant notebook access:

- `APP_CLIENT_TOKEN`: a strong random Bearer token. Configure the same value in the Android app.
- `NVIDIA_API_KEY`: preferred inference provider. When present, every browser task uses `nvidia/nemotron-3.5-bolt-e4b` at `https://integrate.api.nvidia.com/v1/chat/completions`. The model is pinned; stale `NVIDIA_MODEL` values and request overrides are ignored.
- `OPENROUTER_API_KEY`: fallback inference provider when `NVIDIA_API_KEY` is unset. The default model is `openrouter/free`; override it with optional `OPENROUTER_MODEL`.

The notebook requires `APP_CLIENT_TOKEN` and at least one inference API key. It can use local Ollama as a final fallback when run outside the notebook without provider keys.

The Quick Tunnel cell prints a temporary `https://….trycloudflare.com` URL. Copy that base URL—without `/execute`—into the Android app's **Task Manager → Cloud Browser Use settings → Cloud Backend URL**. The app appends `/execute` automatically. Quick Tunnel URLs are not reserved and may change after the tunnel or Colab runtime restarts; update the Android setting when a new URL is printed. Keep the Colab runtime and tunnel process running while using the API. Protect the Android Bearer token because the tunnel URL is publicly reachable.

## API

`GET /health` returns `{"status":"ok"}` and only confirms the server is running.

`POST /execute` requires `Authorization: Bearer <APP_CLIENT_TOKEN>` and JSON with `task_id` plus `prompt`. Include the URL(s) the agent may visit in the prompt, for example:

```json
{
  "task_id": "task-123",
  "prompt": "Read https://docs.python.org/3/tutorial/ and summarize the installation guidance."
}
```

A successful response is `{"result":"<actual Browser Use result>"}`. Configuration, validation, rate-limit, and agent failures return JSON errors with appropriate HTTP status codes; errors do not become fake successful task output. The endpoint allows up to 20 requests per source IP per minute and runs one browser task at a time, with a 55-second execution timeout.

Full Python tracebacks are written to the backend server log for diagnosis and are not sent over the public API, where they could expose credentials or runtime internals. In the supplied Colab notebook the log is `/tmp/usman-fastapi.log`; inspect it with:

```bash
!tail -n 100 /tmp/usman-fastapi.log
```

## Docker or Render deployment

The Dockerfile installs Browser Use and Chromium and copies both `main.py` and `browser_use_interactive.py`. For Hugging Face Spaces or another Docker host, place the contents of `cloud-backend/` at the deployment repository root and set `APP_CLIENT_TOKEN` plus at least one of `NVIDIA_API_KEY` or `OPENROUTER_API_KEY` as secrets. For Render, the included manifest installs Chromium during the build; provide those secrets in the dashboard.

## Local development

From `cloud-backend/`, install `requirements.txt`, install Browser Use and Chromium using the setup instructions in the Colab notebook, then run:

```sh
APP_CLIENT_TOKEN=local-test-token \
NVIDIA_API_KEY=your-nvidia-key \
python -m uvicorn main:app --host 0.0.0.0 --port 7860
```

If NVIDIA is not configured, set `OPENROUTER_API_KEY` instead. Run the backend contract and provider-selection tests with:

```sh
python -m unittest -v test_auth.py
```
