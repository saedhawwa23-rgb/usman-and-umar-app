"""SQLite-backed task lifecycle and event journal for OpenSwarm."""
from __future__ import annotations

import json
import os
import sqlite3
import threading
import time
from typing import Any

TASK_STATES = {"PENDING", "PLANNING", "READY", "RUNNING", "VERIFYING", "COMPLETED",
               "WAITING_FOR_USER", "PAUSED", "FAILED", "CANCELLED", "RETRYING"}
_ALLOWED = {
    "PENDING": {"PLANNING", "CANCELLED"},
    "PLANNING": {"READY", "WAITING_FOR_USER", "FAILED", "CANCELLED"},
    "READY": {"RUNNING", "WAITING_FOR_USER", "CANCELLED"},
    "RUNNING": {"VERIFYING", "WAITING_FOR_USER", "PAUSED", "RETRYING", "FAILED", "CANCELLED"},
    "VERIFYING": {"COMPLETED", "RETRYING", "FAILED", "WAITING_FOR_USER"},
    "WAITING_FOR_USER": {"READY", "RUNNING", "CANCELLED", "FAILED"},
    "PAUSED": {"READY", "RUNNING", "CANCELLED"},
    "RETRYING": {"READY", "RUNNING", "FAILED", "CANCELLED"},
    "FAILED": {"RETRYING"},
    "COMPLETED": set(),
    "CANCELLED": set(),
}


class TaskStateStore:
    def __init__(self, path: str | None = None) -> None:
        self.path = path or os.getenv("OPENSWARM_DB_PATH", "/tmp/usman-openswarm.sqlite3")
        self._lock = threading.RLock()
        self._initialize()

    def _connect(self):
        connection = sqlite3.connect(self.path, timeout=15)
        connection.row_factory = sqlite3.Row
        return connection

    def _initialize(self) -> None:
        with self._lock, self._connect() as db:
            db.execute("""CREATE TABLE IF NOT EXISTS tasks (
                task_id TEXT PRIMARY KEY, prompt TEXT NOT NULL, state TEXT NOT NULL,
                plan_json TEXT NOT NULL DEFAULT '[]', result_json TEXT,
                created_at REAL NOT NULL, updated_at REAL NOT NULL)""")
            db.execute("""CREATE TABLE IF NOT EXISTS task_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT, task_id TEXT NOT NULL,
                event TEXT NOT NULL, from_state TEXT, to_state TEXT,
                detail_json TEXT NOT NULL DEFAULT '{}', created_at REAL NOT NULL)""")
            db.execute("CREATE INDEX IF NOT EXISTS idx_task_events_task ON task_events(task_id, id)")

    def create(self, task_id: str, prompt: str) -> dict[str, Any]:
        now = time.time()
        with self._lock, self._connect() as db:
            db.execute("INSERT INTO tasks(task_id,prompt,state,created_at,updated_at) VALUES(?,?,?,?,?)",
                       (task_id, prompt, "PENDING", now, now))
            db.execute("INSERT INTO task_events(task_id,event,to_state,created_at) VALUES(?,?,?,?)",
                       (task_id, "task_created", "PENDING", now))
        return self.get(task_id)

    def get(self, task_id: str) -> dict[str, Any]:
        with self._lock, self._connect() as db:
            row = db.execute("SELECT * FROM tasks WHERE task_id=?", (task_id,)).fetchone()
        if row is None:
            raise KeyError(f"Unknown task: {task_id}")
        value = dict(row)
        value["plan"] = json.loads(value.pop("plan_json"))
        value["result"] = json.loads(value.pop("result_json")) if value.get("result_json") else None
        return value

    def transition(self, task_id: str, state: str, event: str, detail: dict[str, Any] | None = None) -> dict[str, Any]:
        if state not in TASK_STATES:
            raise ValueError(f"Unknown task state: {state}")
        now = time.time()
        with self._lock, self._connect() as db:
            row = db.execute("SELECT state FROM tasks WHERE task_id=?", (task_id,)).fetchone()
            if row is None:
                raise KeyError(f"Unknown task: {task_id}")
            old = row["state"]
            if state not in _ALLOWED[old]:
                raise ValueError(f"Invalid task transition: {old} -> {state}")
            db.execute("UPDATE tasks SET state=?,updated_at=? WHERE task_id=?", (state, now, task_id))
            db.execute("INSERT INTO task_events(task_id,event,from_state,to_state,detail_json,created_at) VALUES(?,?,?,?,?,?)",
                       (task_id, event, old, state, json.dumps(detail or {}), now))
        return self.get(task_id)

    def save_plan(self, task_id: str, plan: list[dict[str, Any]]) -> None:
        now = time.time()
        with self._lock, self._connect() as db:
            db.execute("UPDATE tasks SET plan_json=?,updated_at=? WHERE task_id=?",
                       (json.dumps(plan), now, task_id))
            db.execute("INSERT INTO task_events(task_id,event,detail_json,created_at) VALUES(?,?,?,?)",
                       (task_id, "plan_saved", json.dumps({"steps": len(plan)}), now))

    def save_result(self, task_id: str, result: dict[str, Any]) -> None:
        now = time.time()
        with self._lock, self._connect() as db:
            db.execute("UPDATE tasks SET result_json=?,updated_at=? WHERE task_id=?",
                       (json.dumps(result), now, task_id))
            db.execute("INSERT INTO task_events(task_id,event,detail_json,created_at) VALUES(?,?,?,?)",
                       (task_id, "result_saved", json.dumps({"verified": result.get("verified", False)}), now))

    def events(self, task_id: str) -> list[dict[str, Any]]:
        with self._lock, self._connect() as db:
            rows = db.execute("SELECT * FROM task_events WHERE task_id=? ORDER BY id", (task_id,)).fetchall()
        return [{**dict(row), "detail": json.loads(row["detail_json"])} for row in rows]
