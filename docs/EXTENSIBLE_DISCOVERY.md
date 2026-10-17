# Extensible Discovery

USMAN AND UMAR now supports runtime discovery without replacing the existing provider catalog.

## Search AI

Settings → **Discover Extensions** → **Search AI** searches live GitHub repository metadata through the public GitHub REST API. The user selects a result, enters an OpenAI-compatible endpoint, model ID, and API key, then chooses **Add and Use**. The provider is persisted in the encrypted provider-key flow already used by the app and is routed through a generic Android-native OpenAI-compatible client.

Built-in providers and their API-key behavior remain unchanged. Discovered provider IDs are namespaced with `discovered:` and are accepted by the existing provider selection and fallback logic.

## Search GitHub Projects

The GitHub tab supports free-form repository search and direct `owner/repository` or repository URL lookup. Users can open/analyze a repository, add it to the supported-project list, and choose **Use** to save it as the active project context. The app never downloads or executes arbitrary repository code on the phone.

## Cloud boundary

The phone remains the lightweight UI/control surface. Existing cloud LLM providers continue to perform provider-side inference. If a future integration needs server-side indexing, long-running jobs, or large artifacts, the discovery repository is isolated behind a small repository boundary so a Vercel/Cloudflare Worker or equivalent backend can be added without changing the Compose UI or the built-in provider implementations. No backend is required for the current public GitHub metadata flow.

## Safety and compatibility

- Existing AI providers, API-key storage, and settings behavior are preserved.
- No fixed list is used for discovery results.
- No arbitrary downloaded code is installed or executed.
- The Android module remains configured for `armeabi-v7a` compatibility and the existing low-memory target.
