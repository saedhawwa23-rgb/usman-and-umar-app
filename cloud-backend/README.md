---
title: USMAN AND UMAR OpenSwarm Bridge
emoji: ⚡
colorFrom: blue
colorTo: purple
sdk: docker
app_port: 7860
---

# USMAN AND UMAR OpenSwarm Bridge

This is a Dockerized FastAPI bridge for the Android task manager. Hugging Face Spaces reads the Docker SDK metadata from the YAML block above and expects the Space's `README.md` and `Dockerfile` at the root of the Space repository. In this project, those deployment files live under `cloud-backend/`.

**This bridge does not install or run the OpenSwarm agent.** In live mode it forwards `{"task_id":"...","prompt":"..."}` to an existing OpenSwarm execution API configured by `OPEN_SWARM_EXECUTE_URL`. That upstream service must already be running and reachable over HTTPS. Hosting this bridge on a Space does not move the agent runtime or model onto that Space. The optional mock mode is for connection testing only and never represents real OpenSwarm output.

## Deploy to Hugging Face Spaces

1. Create a Space and choose **Docker** as its SDK. The Space's Docker build context is its repository root, so do not point the Docker build at this Android repository's top-level directory.
2. Clone the new Space repository and copy the contents of this project's `cloud-backend/` directory into the Space repository root. Include `Dockerfile`, `README.md`, `main.py`, and `requirements.txt`; do not copy a local `.env` file.

   ```sh
   git clone https://huggingface.co/spaces/<owner>/<space-name>.git hf-space
   cp cloud-backend/Dockerfile cloud-backend/README.md cloud-backend/main.py cloud-backend/requirements.txt hf-space/
   cd hf-space
   git add Dockerfile README.md main.py requirements.txt
   git commit -m "Deploy OpenSwarm bridge"
   git push
   ```

   The Space's root `README.md` must retain the `sdk: docker` and `app_port: 7860` metadata shown at the top of this file. See the [Docker Spaces guide](https://huggingface.co/docs/hub/en/spaces-sdks-docker) and [Spaces configuration reference](https://huggingface.co/docs/hub/en/spaces-config-reference).
3. In the Space's **Settings → Variables and secrets**, add `APP_CLIENT_TOKEN` as a secret. Set it to a strong random value and enter the same value in the Android app. Add `OPEN_SWARM_EXECUTE_URL` as a secret or variable containing the full HTTPS URL of the real OpenSwarm execution endpoint, including its path. Add `OPEN_SWARM_BEARER_TOKEN` as a secret only if the upstream API requires bearer authentication.
4. Leave `ENABLE_MOCK_EXECUTION` unset or set it to `false` for real use. Setting it to `true` returns an explicitly marked mock response and bypasses the upstream API; it must not be used to claim that an agent ran.
5. Wait for the Space build and container to start. Visit `https://<owner>-<space-name>.hf.space/health`; a healthy bridge returns `{"status":"ok"}`. `GET /` also returns basic service status. These checks confirm that the bridge process is running, not that the separate OpenSwarm API is reachable.
6. In USMAN AND UMAR → Task Orchestrator → Cloud OpenSwarm settings, enter the Space's HTTPS base URL in **Cloud Backend URL** and save the matching `APP_CLIENT_TOKEN`. Enter the base URL only; the Android app appends `/execute`.

The old `render.yaml` in this directory is not used by Hugging Face Spaces. Spaces uses the root-level Dockerfile and README metadata instead.

## Configuration and behavior

`POST /execute` requires `Authorization: Bearer <APP_CLIENT_TOKEN>`. The bridge permits up to 20 requests per source IP per minute in one process. The counter is in memory and resets when that process restarts. In live mode, missing `APP_CLIENT_TOKEN` or `OPEN_SWARM_EXECUTE_URL` produces a JSON HTTP 503 configuration error. The bridge rejects upstream URLs that are not HTTPS or that contain embedded credentials. Upstream calls time out after 55 seconds; the Android client waits up to 65 seconds. Errors are logged to the Space container logs and returned without exposing exception details.

The bridge is stateless and does not persist task data to its container filesystem. Hugging Face documents that free Spaces can sleep when idle and that available hardware, RAM, pricing, and account requirements depend on the selected Space configuration. Check the current [Spaces overview and hardware details](https://huggingface.co/docs/hub/en/spaces-overview) in your account; do not assume that a particular RAM amount or free runtime is available for every account.

## Local development

From the project root:

```sh
docker build -t usman-umar-openswarm-bridge cloud-backend
docker run --rm -p 7860:7860 \
  -e APP_CLIENT_TOKEN=local-test-token \
  -e ENABLE_MOCK_EXECUTION=true \
  usman-umar-openswarm-bridge
```

Then check `http://localhost:7860/health`. For Python tests without Docker:

```sh
cd cloud-backend
python -m pip install -r requirements.txt
python -m unittest -v test_auth.py
```

The mock response is labeled `[MOCK RESPONSE — NOT A REAL OPENSWARM RESULT]`. Disable mock mode and configure the real upstream before using the bridge for actual tasks.
