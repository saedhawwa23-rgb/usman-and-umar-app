# Cloud OpenSwarm request diagnostics

Use a fresh task ID when comparing Android Logcat with the Colab backend log. Never paste bearer tokens, API keys, or full request headers into issue reports.

## Collect evidence

1. In Android Logcat, filter by `CloudOpenSwarm`. A cloud send should log `dispatch_route=cloud_openswarm`, then `request_dispatch stage=http_dispatch method=POST .../execute task_id=...`. On completion it logs the HTTP status. Failure text includes the stage, exception class, sanitized request URL, HTTP status when available, and a redacted/truncated response body.
2. In Colab, inspect the Uvicorn output (`tail -n 200 /tmp/fastapi.log`). The backend emits `request_received`, `execute_auth`, `execute_started`, `execute_status`, and `request_completed` records for `/execute`. It never logs the Authorization header or prompt body.
3. Compare the same task ID and timestamp across both logs. The Colab route uses the configured bearer token in the HTTP `Authorization` header; the JSON body does not carry credentials.

## Interpret the boundary

| Evidence | Likely boundary |
| --- | --- |
| No `dispatch_route` entry | The chat send did not select Cloud OpenSwarm; inspect the selected chat target and plugin selection. |
| `dispatch_route` but no `request_dispatch` | Failure occurred before HTTP dispatch, such as local configuration, health selection, or request serialization. The chat diagnostic reports the failure stage. |
| `request_dispatch` followed by DNS/connect/network I/O failure, with no backend `request_received` | Android could not establish or complete transport to the tunnel. A timeout/reset is ambiguous: the POST may have reached the server, so check task history before retrying. |
| Android receives a non-JSON tunnel response (often 4xx/5xx/HTML) and there is no matching backend `request_received` | The tunnel or upstream rejected the request before FastAPI. Confirm the public tunnel URL is live and the `Bypass-Tunnel-Reminder: true` request header is present. Check `/tmp/localtunnel.log`. |
| `request_received`, then `execute_validation ... failed`, HTTP 422 | FastAPI received JSON but the payload did not match the `/execute` schema. Check required `task_id` and `prompt`, types, and field limits. |
| `request_received`, `execute_auth ... failed` (401) or `not_configured` (503) | FastAPI received the request, but bearer authentication failed or `APP_CLIENT_TOKEN` is missing on the backend. Compare configured credentials locally; do not log or share them. |
| Auth passes but `execution_status=rejected` (400/429) | Backend policy validation or rate limiting rejected the task before OpenSwarm execution. |
| `execute_started ... route=openswarm` followed by `execution_status=failed`/504 | The request reached OpenSwarm; inspect the traceback immediately preceding the status record in `/tmp/fastapi.log` for the server-side failure. |
| `execution_status=success status=200` and Android receives 200, but chat has no result | Investigate response parsing or conversation persistence in Android after HTTP completion. |

The backend log records task IDs, route/status, and validation/auth outcomes, but deliberately excludes prompts, request headers, API keys, and bearer tokens. Keep the tunnel URL and task ID aligned when comparing devices and logs.
