---
title: USMAN AND UMAR Direct Browser Use Executor
emoji: "⚡"
colorFrom: blue
colorTo: purple
sdk: docker
app_port: 7860
---

# USMAN AND UMAR Direct Browser Use Executor

This FastAPI service runs the repository's `browser_use_interactive.py` helper in the same Python runtime. It does **not** forward requests to `OPEN_SWARM_EXECUTE_URL`, does not use `OPEN_SWARM_BEARER_TOKEN`, and has no mock-success path. Successful `/execute` responses contain the actual final text returned by Browser Use.

The helper is intentionally bounded to read-only public-web research. It requires one or more explicit public `http(s)` URLs in the prompt, restricts Browser Use navigation to the hostnames found in those URLs, and runs at most eight steps. It will not sign in, submit forms, send messages, publish content, make purchases, or modify accounts or data. Website content is untrusted and is not allowed to override the read-only policy.

## Run in Google Colab

Use `USMAN_COLAB_BACKEND.ipynb` from the repository. It installs the FastAPI and Browser Use dependencies, installs Chromium, and starts the service on port 7860 before starting the zrok tunnel.

Add these values in Colab **Secrets** and grant notebook access:

- `APP_CLIENT_TOKEN`: a strong random Bearer token. Configure the same value in the Android app.
- `OPENROUTER_API_KEY`: recommended for agent inference. The helper uses OpenRouter's free-model router by default; model availability and pricing may vary. If no key is provided, the helper requires an installed and running Ollama service and model in the runtime.
- `OPENROUTER_MODEL`: optional OpenRouter model slug; defaults to `openrouter/free`.

`OPEN_SWARM_EXECUTE_URL`, `OPEN_SWARM_BEARER_TOKEN`, and `ENABLE_MOCK_EXECUTION` are not used by this direct executor. After changing secrets, rerun the secrets cell and restart the FastAPI server so its process receives the current environment.

Keep the Colab runtime and zrok share running while you use the API. The tunnel URL is publicly reachable, so protect the Bearer token and only expose it to trusted clients.

## Docker or Render deployment

The Dockerfile installs Browser Use and Chromium and copies both `main.py` and `browser_use_interactive.py`. For Hugging Face Spaces or another Docker host, place the contents of `cloud-backend/` at the deployment repository root and set `APP_CLIENT_TOKEN` and `OPENROUTER_API_KEY` as secrets. For Render, the included manifest installs Chromium during the build; supply the same secrets in the Render dashboard. No `OPEN_SWARM_EXECUTE_URL` is needed.

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

## Local development

From `cloud-backend/`, install `requirements.txt`, install Browser Use and Chromium using the setup instructions in the Colab notebook, then run:

```sh
APP_CLIENT_TOKEN=local-test-token \
OPENROUTER_API_KEY=your-key \
python -m uvicorn main:app --host 0.0.0.0 --port 7860
```

Run the backend contract and authentication tests with:

```sh
python -m unittest -v test_auth.py
```
