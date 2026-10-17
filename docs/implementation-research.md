# Integration implementation notes

## OpenRouter

- The provider publishes a live model catalog at `GET https://openrouter.ai/api/v1/models`; a catalog response is **not** a test of the user's chat key or a model's tool support.
- Tool calling is a client-side loop: send `tools`, inspect assistant `tool_calls`, execute locally, append the assistant tool-call message and matching `role: tool` messages with `tool_call_id`, then request a final answer. Tool support varies by model.
- Sources: [OpenRouter model listing](https://openrouter.ai/docs/api-reference/models/get-models), [OpenRouter tool-calling guide](https://openrouter.ai/docs/guides/features/tool-calling).

## GitHub public discovery

- Public repository search uses `GET /search/repositories`, allows 10 anonymous search requests/minute, and supports up to 100 results/page. This app currently requests 20.
- Repository detail, README, and root contents are retrieved through GitHub REST. Current app calls are unauthenticated and therefore only promise public repository data. A 403/429 must be surfaced as a rate-limit outcome; a 404 may mean missing or private.
- Source: [GitHub REST search documentation](https://docs.github.com/en/rest/search/search#search-repositories).

## Termux `RUN_COMMAND`

- Official contract requires `com.termux.permission.RUN_COMMAND` on the calling app and `allow-external-apps=true` in Termux. A PendingIntent callback needs a unique request code and must be mutable on Android 12+ so the result can be returned.
- Results arrive nested in the `result` bundle, with `stdout`, `stderr`, `exitCode`, `err`, and `errmsg`; treating these as top-level extras is incorrect. Background commands return separate stdout/stderr. Termux 0.109+ is required for callback results.
- Sources: [Termux RUN_COMMAND wiki](https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent), [TermuxConstants.java](https://raw.githubusercontent.com/termux/termux-app/master/termux-shared/src/main/java/com/termux/shared/termux/TermuxConstants.java).

## Cloudflare Worker scope

- The repository contains a generic Cloudflare API connector, but this workspace has no Worker URL/configuration or deployment credential. The existence of connector code does not prove a deployed Worker is reachable or usable. Do not report Worker execution as verified until an endpoint and authorized credentials are configured and an integration request succeeds.
