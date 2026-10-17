# Security Plan

- Keep provider credentials in Keystore-backed storage; never place secrets in source, ordinary Room records, logs, reports, crash data, or Git history.
- Treat AI output, web pages, repositories, plugin metadata, archives, and API responses as untrusted input.
- Validate tool calls against registered schemas, permissions, approval policy, timeouts, resource limits, and cancellation behavior.
- Require explicit approval for destructive, external, financial, account, repository-write, accessibility-sensitive, or irreversible effects.
- Restrict network protocols, redirects, response sizes, and private/local network access for web retrieval.
- Protect archive extraction from traversal, unsafe symlinks, overwrite surprises, and decompression abuse.
- Keep Termux, built-in utilities, and embedded Linux as separate execution backends with truthful status.
- Use least-privilege Android permissions and explain accessibility/privacy implications before enabling services.
- Redact API keys, authorization headers, cookies, tokens, prompts containing secrets, and sensitive outputs from diagnostics.
- Add security regression tests for credential redaction, SSRF, archive paths, shell argument construction, permissions, and recovery.
