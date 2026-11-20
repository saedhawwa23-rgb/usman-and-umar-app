#!/usr/bin/env python3
"""Remove agent-platform references from a Navigation.kt copied into the pinned baseline.

The visual-evidence job copies the current Navigation.kt into a v3.10.5 worktree so the
older tree gets the visual-evidence routes. That file also references the agent platform
center, which the baseline has no screen for, so the copy would not compile. Every pattern
must match exactly once; a partial match means the source moved and the baseline would be
wrong, so fail instead of producing a silently different baseline.
"""

import re
import sys

PATTERNS = [
    r"\n[ \t]*onNavigateToAgentPlatforms = \{\n[^}]*?\n[ \t]*\}",
    r"\n[ \t]*composable\(OpenDroidRoutes\.AGENT_PLATFORMS\) \{\n[^}]*?\n[ \t]*\}",
    r"\n[ \t]*onNavigateToAgentPlatforms: \(\) -> Unit = \{\},",
    r"\n[ \t]*onOpenAgentPlatforms = onNavigateToAgentPlatforms",
]


def main() -> int:
    if len(sys.argv) != 2:
        print(f"usage: {sys.argv[0]} <path-to-Navigation.kt>", file=sys.stderr)
        return 2

    path = sys.argv[1]
    with open(path) as handle:
        source = handle.read()

    for pattern in PATTERNS:
        source, count = re.subn(pattern, "", source, flags=re.S)
        if count != 1:
            print(
                f"expected exactly one match for {pattern!r}, found {count}",
                file=sys.stderr,
            )
            return 1

    with open(path, "w") as handle:
        handle.write(source)
    return 0


if __name__ == "__main__":
    sys.exit(main())
