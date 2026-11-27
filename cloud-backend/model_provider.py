"""Provider selection shared by the optional OpenSwarm reasoning path."""
from __future__ import annotations

import os

DEFAULT_LOCAL_MODEL = "llama3.1:8b"
NVIDIA_BASE_URL = "https://integrate.api.nvidia.com/v1"
NVIDIA_DEFAULT_MODEL = "nvidia/nemotron-3.5-bolt-e4b"
OPENROUTER_BASE_URL = "https://openrouter.ai/api/v1"
OPENROUTER_FREE_MODEL = "openrouter/free"


def select_provider(
    requested_model: str | None,
    nvidia_key: str | None,
    openrouter_key: str | None,
) -> tuple[str, str | None, str, str | None]:
    """Choose NVIDIA first, then OpenRouter, with local Ollama as last fallback."""
    override = requested_model.strip() if requested_model and requested_model.strip() else ""
    if nvidia_key:
        return "NVIDIA", nvidia_key, NVIDIA_DEFAULT_MODEL, NVIDIA_BASE_URL
    if openrouter_key:
        model_name = override or os.environ.get("OPENROUTER_MODEL", "").strip() or OPENROUTER_FREE_MODEL
        return "OpenRouter", openrouter_key, model_name, OPENROUTER_BASE_URL
    return "Ollama", None, override or DEFAULT_LOCAL_MODEL, None
