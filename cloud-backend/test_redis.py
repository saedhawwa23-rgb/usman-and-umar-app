#!/usr/bin/env python3
"""Live Upstash Redis REST connectivity check.

Run with UPSTASH_REDIS_REST_URL and UPSTASH_REDIS_REST_TOKEN configured:
    python test_redis.py
The script writes a uniquely named temporary ``task_*`` document, reads it
back, verifies exact JSON equality, then deletes it. It never prints secrets.
"""
from __future__ import annotations

import os
import sys
import uuid

from redis_task_storage import RedisTaskStorage, RedisStorageError


def main() -> int:
    if not os.getenv("UPSTASH_REDIS_REST_URL", "").strip() or not os.getenv("UPSTASH_REDIS_REST_TOKEN", "").strip():
        print("NOT READY: set UPSTASH_REDIS_REST_URL and UPSTASH_REDIS_REST_TOKEN first.", file=sys.stderr)
        return 2
    storage = RedisTaskStorage()
    task_id = f"connection-test-{uuid.uuid4().hex}"
    expected = {"purpose": "upstash_connection_test", "nonce": uuid.uuid4().hex}
    try:
        storage.set(task_id, expected)
        observed = storage.get(task_id)
        if observed != expected:
            raise RedisStorageError("Upstash task JSON SET/GET returned different content")
        print("PASS: Upstash REST SET/GET verified JSON under the task_{id} key.")
        return 0
    except RedisStorageError as exc:
        print(f"FAIL: {exc}", file=sys.stderr)
        return 1
    finally:
        try:
            storage.delete(task_id)
        except RedisStorageError:
            print("WARNING: temporary Redis test key cleanup failed.", file=sys.stderr)


if __name__ == "__main__":
    raise SystemExit(main())
