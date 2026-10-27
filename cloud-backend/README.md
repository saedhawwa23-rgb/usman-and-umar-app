# Cloud OpenSwarm bridge

This FastAPI service is a thin HTTP bridge for the Android task manager. In live mode it forwards requests only to the real OpenSwarm execution endpoint configured by `OPEN_SWARM_EXECUTE_URL`; it does not use a paid model API or synthesize agent output. An explicit `ENABLE_MOCK_EXECUTION=true` switch is provided only for connection/UI testing. Mock replies are labeled as mock data, are never OpenSwarm results, and the switch is off by default.

## Configure Render

1. Create a Render Web Service using this repository and `cloud-backend/render.yaml` (or set the service root to `cloud-backend`). Select the free instance plan if available for your account/region.
2. For a temporary connection test, set `ENABLE_MOCK_EXECUTION=true` in the Render service environment and redeploy. Submit a cloud task from the app. The task output will say `[MOCK RESPONSE — NOT A REAL OPENSWARM RESULT]`; this confirms Android-to-Render request/response delivery only, not an OpenSwarm run. Set this variable back to `false` or remove it after testing.
3. For live execution, set `OPEN_SWARM_EXECUTE_URL` to the HTTPS URL of the actual OpenSwarm execution API, including its required path. This integration forwards JSON shaped as `{"task_id":"...","prompt":"..."}`. The OpenSwarm endpoint must accept that request shape; adapt this single forwarding point if your installation uses a different contract.
4. Set `APP_CLIENT_TOKEN` in Render and enter the same value in the app's Cloud OpenSwarm settings. The app stores its copy using Android Keystore-backed encrypted credentials. Never commit or publish this token.
5. Set `OPEN_SWARM_BEARER_TOKEN` only if your OpenSwarm server requires bearer authentication. Render stores it as an environment secret; do not put it in the app, Git, or the Render URL.
6. Deploy. Verify `GET https://<your-render-host>/health` returns `{"status":"ok"}`.
7. In USMAN AND UMAR → Task Orchestrator → Configure Render server, save the base HTTPS URL (without `/execute`) and the client token. The Android app posts to `/execute` and waits up to 65 seconds.

`/execute` requires `Authorization: Bearer <APP_CLIENT_TOKEN>` and limits each source IP to 20 requests per minute per instance. A missing `APP_CLIENT_TOKEN` or, in live mode, a missing `OPEN_SWARM_EXECUTE_URL` produces an intentional JSON HTTP 503 configuration error. Unexpected endpoint errors are logged in Render and returned as a generic JSON HTTP 500; the server does not return internal exception details to the app. The Render free tier may sleep between requests, so the first live request can take longer or time out. `GET /health` is a process health check, not proof that OpenSwarm is reachable.

## Local development

```sh
cd cloud-backend
python -m venv .venv
. .venv/bin/activate
pip install -r requirements.txt
export APP_CLIENT_TOKEN='test-only-local-token'
export ENABLE_MOCK_EXECUTION=true
uvicorn main:app --reload --port 8000
```

To use the real upstream instead, unset or set `ENABLE_MOCK_EXECUTION=false` and configure `OPEN_SWARM_EXECUTE_URL`. The mock mode is deliberately visible in the returned body and must not be used to represent a real agent result.
