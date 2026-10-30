"""OpenSwarm-compatible Manager/Browser/Coder agent team.

The public VRSEN OpenSwarm launcher is Node/Agency-Swarm based.  This module keeps
our Colab deployment Python-native while using the same specialist/orchestrator
shape: a Manager plans and delegates, Browser performs real Browser Use work,
and Coder handles non-browser reasoning with an OpenAI-compatible model.
"""
from __future__ import annotations

import json
import os
from typing import Any, Literal, TypedDict

from browser_use_interactive import (
    NVIDIA_DEFAULT_MODEL,
    _select_provider,
    run_browser_task,
)


class SwarmState(TypedDict, total=False):
    task_id: str
    prompt: str
    plugin_id: str | None
    allowed_domains: list[str]
    max_steps: int
    route: Literal["browser", "coder"]
    plan: str
    specialist_result: str
    final_answer: str
    iterations: int


def _model():
    """Create the configured real OpenAI-compatible model lazily."""
    from langchain_openai import ChatOpenAI

    provider, api_key, model, base_url = _select_provider(
        os.getenv("OPENROUTER_MODEL"),
        os.getenv("NVIDIA_API_KEY"),
        os.getenv("OPENROUTER_API_KEY"),
    )
    if provider == "Ollama":
        raise RuntimeError("OpenSwarm requires NVIDIA_API_KEY, OPENROUTER_API_KEY, or an explicitly configured OpenAI-compatible endpoint.")
    return ChatOpenAI(
        api_key=api_key,
        model=model or NVIDIA_DEFAULT_MODEL,
        base_url=base_url,
        temperature=0,
        max_tokens=2000,
    )


def _text(message: Any) -> str:
    content = getattr(message, "content", message)
    if isinstance(content, list):
        return "".join(part.get("text", "") if isinstance(part, dict) else str(part) for part in content)
    return str(content)


def _manager_decision(state: SwarmState) -> dict[str, Any]:
    prompt = state["prompt"]
    plugin = (state.get("plugin_id") or "").lower()
    if plugin == "fmhy_browser":
        route: Literal["browser", "coder"] = "browser"
    elif plugin == "openswarm_agent":
        decision = _text(_model().invoke([
            ("system", "You are the OpenSwarm Manager Agent. Choose exactly one specialist: browser for public web navigation or coder for reasoning/code generation. Return JSON only: {\"route\":\"browser|coder\",\"plan\":\"...\"}."),
            ("user", prompt),
        ]))
        try:
            data = json.loads(decision[decision.index("{"):decision.rindex("}") + 1])
            route = "browser" if data.get("route") == "browser" else "coder"
            return {"route": route, "plan": str(data.get("plan") or prompt), "iterations": state.get("iterations", 0) + 1}
        except (ValueError, json.JSONDecodeError):
            route = "browser" if "http://" in prompt or "https://" in prompt or "browse" in prompt.lower() else "coder"
    else:
        route = "browser"
    return {"route": route, "plan": prompt, "iterations": state.get("iterations", 0) + 1}


async def _browser_agent(state: SwarmState) -> dict[str, str]:
    result = await run_browser_task(
        state["prompt"],
        allowed_domains=state["allowed_domains"],
        model=NVIDIA_DEFAULT_MODEL if os.getenv("NVIDIA_API_KEY", "").strip() else None,
        max_steps=state["max_steps"],
        allow_fmhy_external=state.get("plugin_id") == "fmhy_browser",
    )
    return {"specialist_result": result}


def _coder_agent(state: SwarmState) -> dict[str, str]:
    response = _model().invoke([
        ("system", "You are the OpenSwarm Coder Agent. Solve the user's request carefully. If code is requested, return complete usable code and concise instructions. Never claim to have executed code or accessed a site when you did not."),
        ("user", state["plan"]),
    ])
    return {"specialist_result": _text(response)}


def _finalize(state: SwarmState) -> dict[str, str]:
    result = state.get("specialist_result", "").strip()
    if not result:
        raise RuntimeError("OpenSwarm specialist returned no final answer.")
    return {"final_answer": result}


async def run_openswarm_task(*, task_id: str, prompt: str, plugin_id: str | None, allowed_domains: list[str], max_steps: int) -> str:
    """Run the Manager -> specialist -> finalizer graph until an answer exists."""
    from langgraph.graph import END, START, StateGraph

    graph = StateGraph(SwarmState)
    graph.add_node("manager", _manager_decision)
    graph.add_node("browser_agent", _browser_agent)
    graph.add_node("coder_agent", _coder_agent)
    graph.add_node("finalize", _finalize)
    graph.add_edge(START, "manager")
    graph.add_conditional_edges("manager", lambda state: state["route"], {"browser": "browser_agent", "coder": "coder_agent"})
    graph.add_edge("browser_agent", "finalize")
    graph.add_edge("coder_agent", "finalize")
    graph.add_edge("finalize", END)
    compiled = graph.compile()
    state = await compiled.ainvoke({
        "task_id": task_id,
        "prompt": prompt,
        "plugin_id": plugin_id,
        "allowed_domains": allowed_domains,
        "max_steps": max_steps,
        "iterations": 0,
    })
    answer = str(state.get("final_answer", "")).strip()
    if not answer:
        raise RuntimeError("OpenSwarm completed without a final answer.")
    return answer
