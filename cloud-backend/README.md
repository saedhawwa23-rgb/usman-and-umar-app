# Cloud OpenSwarm bridge

This FastAPI service is a thin HTTP bridge for the Android task manager. It uses **no paid model API** and does not synthesize a result. Every `/execute` request is POSTed to the real OpenSwarm endpoint configured with `OPEN_SWARM_EXECUTE_URL`; errors, empty replies, and timeouts remain failures rather than being reported as success.

## Configure Render

1. Create a Render Web Service using this repository and `cloud-backend/render.yaml` (or set the service root to `cloud-backend`). Select the free instance plan if available for your account/region.
2. Set `OPEN_SWARM_EXECUTE_URL` to the HTTPS URL of the actual OpenSwarm execution API, including its required path. This integration forwards JSON shaped as `{"task_id":"...","prompt":"..."}`. The OpenSwarm endpoint must accept that request shape; adapt this single forwarding point if your installation uses a different contract.
3. Generate a long random client token (for example, `python -c 'import secrets; print(secrets.token_urlsafe(32))'`). Save it in Render as `APP_CLIENT_TOKEN`, then enter the same value in the app's Cloud OpenSwarm settings. The app stores its copy using Android Keystore-backed encrypted credentials. Never commit or publish this token.
4. Set `OPEN_SWARM_BEARER_TOKEN` only if your OpenSwarm server requires bearer authentication. Render stores it as an environment secret; do not put it in the app, Git, or the Render URL.
5. Deploy. Verify `GET https://<your-render-host>/health` returns `{"status":"ok"}`.
6. In USMAN AND UMAR → Task Orchestrator → Configure Render server, save the base HTTPS URL (without `/execute`) and the client token. The Android app posts to `/execute` and waits up to 65 seconds.

The Render free tier may sleep between requests, so the first request can take longer or time out. A timeout is shown as an execution failure; the app does not claim a result that was not received. The bridge requires `X-App-Token` and limits each source IP to 20 requests per minute per instance. This in-memory limit is a basic abuse guard, not a distributed quota system. This bridge does not launch an OpenSwarm instance by itself: the actual upstream URL and any required authorization must be supplied by the operator.

## Local development

```sh
cd cloud-backend
python -m venv .venv
. .venv/bin/activate
pip install -r requirements.txt
export OPEN_SWARM_EXECUTE_URL='https://your-real-openswarm.example/execute'
uvicorn main:app --reload --port 8000
```

`GET /health` is a process health check, not proof that OpenSwarm is reachable. `/execute` reports upstream failures with HTTP error status and never substitutes a canned response.
