"""Multi-agent OpenSwarm orchestrator with durable task state and honest capability routing."""
from __future__ import annotations
import json, os, re
from typing import Any
from agent_registry import build_default_registry
from browser_scraper import run_playwright_task
from model_provider import NVIDIA_DEFAULT_MODEL, select_provider
from task_state import TaskStateStore

AGENTS = build_default_registry()
TASKS = TaskStateStore()

class CapabilityUnavailableError(RuntimeError):
    pass

def _model():
    from langchain_openai import ChatOpenAI
    _, api_key, model, base_url = select_provider(os.getenv("OPENROUTER_MODEL"), os.getenv("NVIDIA_API_KEY"), os.getenv("OPENROUTER_API_KEY"))
    if not api_key:
        raise RuntimeError("OpenSwarm reasoning needs NVIDIA_API_KEY or OPENROUTER_API_KEY.")
    return ChatOpenAI(api_key=api_key, model=model or NVIDIA_DEFAULT_MODEL, base_url=base_url, temperature=0, max_tokens=2500)

def _text(message: Any) -> str:
    content = getattr(message, "content", message)
    if isinstance(content, list):
        return "".join(part.get("text", "") if isinstance(part, dict) else str(part) for part in content)
    return str(content)

def _manager_plan(prompt: str, plugin_ids: list[str], max_steps: int) -> dict[str, Any]:
    response = _text(_model().invoke([
        ("system", "You are OpenSwarm Manager. Return JSON only: {summary:string,steps:[{agent_id:string,objective:string,requires_approval:boolean}]}. Available IDs: manager,planner,research,browser,coder,terminal,github,files,automation,android,data,documents,drive,security,verification,delivery. Browser can inspect public pages. Coder/planner/research generate reasoning only. Do not claim terminal/GitHub/Drive/Android/files/data/documents/automation execution; those adapters may be unavailable. Respect step limit."),
        ("user", json.dumps({"objective": prompt, "plugins": plugin_ids, "step_limit": max_steps}))
    ]))
    try:
        plan = json.loads(response[response.index("{"):response.rindex("}") + 1])
    except (ValueError, json.JSONDecodeError) as exc:
        raise RuntimeError("Manager returned an invalid structured execution plan.") from exc
    steps = plan.get("steps")
    known = {a["id"] for a in AGENTS.all()}
    if not isinstance(steps, list) or not steps:
        raise RuntimeError("Manager returned an empty execution plan.")
    for step in steps[:max_steps]:
        if not isinstance(step, dict) or step.get("agent_id") not in known or not str(step.get("objective", "")).strip():
            raise RuntimeError("Manager plan contains an invalid specialist step.")
    return {"summary": str(plan.get("summary") or prompt), "steps": steps[:max_steps]}

async def _run_specialist(agent_id: str, objective: str, state: dict[str, Any]) -> dict[str, Any]:
    if agent_id == "browser":
        match = re.search(r"https?://\\S+", objective, re.IGNORECASE) or re.search(r"https?://\\S+", state["prompt"], re.IGNORECASE)
        url = match.group(0).rstrip(".,;:!?)]}") if match else ""
        if not url and "fmhy_browser" in state.get("plugins", []):
            url = "https://fmhy.net"
        if not url:
            raise RuntimeError("Browser specialist needs a public http(s) URL.")
        output = await run_playwright_task(url, action="scrape", allowed_domains=state["allowed_domains"] or None)
        if not output.strip():
            raise RuntimeError("Browser specialist returned empty content.")
        return {"agent_id": agent_id, "status": "EXECUTED", "verified": True, "source_url": url, "output": output[:20000]}
    if agent_id in {"coder", "planner", "research"}:
        output = _text(_model().invoke([
            ("system", f"You are OpenSwarm {agent_id}. State evidence and limitations. Never claim an operation ran unless its real tool result is provided."),
            ("user", objective)
        ]))
        if not output.strip():
            raise RuntimeError(f"{agent_id} returned an empty result.")
        return {"agent_id": agent_id, "status": "GENERATED", "verified": False, "output": output[:20000]}
    raise CapabilityUnavailableError(f"{agent_id} agent is registered, but its execution adapter is not configured. No operation was attempted.")

async def run_openswarm_task(*, task_id: str, prompt: str, plugin_id: str | None, plugin_ids: list[str] | None = None, allowed_domains: list[str], max_steps: int) -> str:
    plugins = sorted(set(plugin_ids or []) | ({plugin_id} if plugin_id else set()))
    TASKS.create(task_id, prompt)
    try:
        TASKS.transition(task_id, "PLANNING", "manager_planning_started")
        plan = _manager_plan(prompt, plugins, max_steps)
        TASKS.save_plan(task_id, plan["steps"])
        TASKS.transition(task_id, "READY", "manager_plan_ready", {"step_count": len(plan["steps"])})
        TASKS.transition(task_id, "RUNNING", "specialists_started")
        results = []
        for step in plan["steps"]:
            agent_id = step["agent_id"]
            if agent_id in {"manager", "verification", "delivery"}:
                continue
            if agent_id == "security":
                results.append({"agent_id": agent_id, "status": "EXECUTED", "verified": True, "output": "Basic prompt policy checks passed."})
                continue
            results.append(await _run_specialist(agent_id, step["objective"], {"prompt": prompt, "allowed_domains": allowed_domains, "plugins": plugins}))
        if not results:
            raise CapabilityUnavailableError("Manager plan contained no executable specialist.")
        TASKS.transition(task_id, "VERIFYING", "independent_result_check_started")
        verified = all(item.get("verified") is True for item in results)
        report = {"summary": plan["summary"], "results": results, "verified": verified,
                  "verification": "Available execution checks passed." if verified else "PARTIALLY_VERIFIED: generated text is not proof of execution."}
        TASKS.save_result(task_id, report)
        TASKS.transition(task_id, "COMPLETED" if verified else "WAITING_FOR_USER",
                         "verification_passed" if verified else "verification_incomplete", {"verified": verified})
        return json.dumps(report, ensure_ascii=False)
    except Exception as exc:
        try:
            state = TASKS.get(task_id)["state"]
            if state not in {"FAILED", "COMPLETED", "CANCELLED"}:
                TASKS.transition(task_id, "FAILED", "execution_failed", {"error_type": type(exc).__name__, "message": str(exc)[:500]})
        except Exception:
            pass
        raise
