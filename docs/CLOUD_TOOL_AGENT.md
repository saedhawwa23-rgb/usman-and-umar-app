# Cloud Tool Agent

USMAN AND UMAR uses configured LLM providers for planning and tool selection. OpenRouter is the default provider for new installs. The app itself no longer includes a local LLM, model assets, or a local inference runtime.

## Execution flow

1. The user submits a goal in the chat UI.
2. `AgentLoop` obtains a provider from `LLMProviderFactory` and requests a structured plan or tool-aware response.
3. `CloudToolAgent` sends registered connector schemas to the cloud provider and parses OpenAI-compatible tool-call responses.
4. The app resolves each requested tool against `UniversalToolRegistry`, validates arguments and connector policy, and checks risk/approval requirements.
5. Authorized connectors execute the request. Android system actions use the app's existing permission and accessibility boundaries; GitHub lookup uses public REST APIs; optional command execution is sent to Termux through `RUN_COMMAND`.
6. The app appends bounded tool results with their matching call IDs, then asks the provider for a final response.
7. The loop stops on completion, an authorization requirement, a connector failure, cancellation, or its configured step limit.

A provider/model may not support tools, and catalog availability is not proof that a specific tool call will work. The app must report failures clearly rather than silently claiming that work succeeded.

## Connector model

`UniversalToolRegistry` is provider-neutral. Connectors publish a display name and compact tool schemas; the cloud provider sees only the tool definitions needed for the current request. Connector implementations do not bypass the app's tool validation or approval policy.

The registry supports HTTP/MCP-backed services and a read-only registry introspection connector. Credentials are resolved through a credential reference during execution; secret values are not put into schemas, prompts, or routine execution logs.

## GitHub and Termux

GitHub discovery is restricted to public repository information through GitHub REST endpoints. Search, metadata, README, and root contents are distinct operations; opening a repository does not automatically save it or clone it. The user can explicitly **ADD** a repository to saved context, **USE** it as the active project, and separately request a clone.

Termux receives a validated executable/argument array, not concatenated shell text. The callback receiver captures stdout, stderr, exit status, and launch errors. Actual Android permission, Termux configuration, clone, and callback operation remain **UNVERIFIED — REQUIRES DEVICE** until tested on a phone with Termux installed and authorized.

## Security and approvals

- API credentials remain in the app's protected credential store.
- The selected provider receives prompt content and any supplied context over HTTPS for inference.
- Read-only or reversible tools can run only under the app's configured policy; sensitive/destructive actions remain approval-gated.
- Tool results are treated as untrusted data and bounded before being returned to the model.
- Termux is an optional execution backend and is not the reasoning engine.
