# Archived: Qwen 3 local-inference experiment

This document records a retired implementation path. It is **not** a description of the current app or a build guide.

The Qwen 3 / llama.cpp experiment was removed in the cloud-first `3.0.0-cloud-ai` branch. The app source no longer includes the `:llama` module, Qwen JNI bridge, bundled GGUF model, LiteRT inference provider, or local model download/import UI. Release packaging must not fetch or include a model artifact.

The current architecture uses OpenRouter as the default cloud LLM provider and supports other configured providers. Android tools execute only through the app's registered tool dispatch and existing permission/approval controls. Optional Termux commands run in Termux through its explicit `RUN_COMMAND` intent; this is command execution, not local LLM inference.

Historical model and llama.cpp implementation details are intentionally omitted here so this page cannot be mistaken for operational instructions. See the root [README](../README.md) for current setup and verification boundaries.
