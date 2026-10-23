# Task Manager — usage and safety

The **Tasks** tab provides persistent task tracking and an explicitly approved path to run an action already registered in the app. It does not require an AI provider/API key for local task entry or local registered actions.

## Create and order work

1. Open **Tasks** and enter a task title. Optionally add a description/instructions and an expected outcome.
2. Set priority from **-10** to **10**; higher values appear first.
3. To make this an executable task, search for a registered action. The screen shows the action category, parameter names/descriptions, and risk label. Selecting one pre-fills a JSON object with its parameter keys; edit the string values as needed.
4. Optionally select prerequisite tasks. A dependent task cannot enter the queue or RUNNING state until every prerequisite is `SUCCEEDED`. Missing, failed, cancelled, or still-running prerequisites block execution.
5. Tap the add button. Creating a task never runs its action.

## Run, cancel, and record outcomes

- A registered-action task presents **Approve & Run**. Review the action, risk, and inputs before tapping; that tap is the explicit approval and execution happens immediately afterward.
- **Cancel** requests coroutine cancellation and records the task as cancelled. Android actions or external side effects that already began may not be undone. The app does not claim cancellation is rollback.
- A task without an action is a manual task. It is never executed by the app. The user may start it and tap **MARK DONE (USER-REPORTED)**; the activity log records that provenance, and the UI states that the result was not verified by an app action.
- Successful registered-action tasks use the action's actual result. Task execution history remains in the existing Room database. Output/history handling retains the app's existing redaction behavior.

## Input privacy and limits

Action inputs are persisted with the task on the device. Do not put passwords, tokens, or other secrets in them. The UI previews inputs after the app's current redactor, but users should still review what they are saving and approving.

This is an integrated local task manager, not a promise of arbitrary shell access or full autonomous AI routing. Only actions present in the app's registered action catalog can run. A successful registered action is not proof of downstream delivery unless that action's own result verifies it. Galaxy M10 on-device interaction still requires device acceptance testing; a successful debug build is not a substitute for that test.
