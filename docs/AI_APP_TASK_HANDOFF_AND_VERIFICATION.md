# AI-app task handoff and verification

## Inbox triage

The Inbox tab sends pasted text to the provider selected in the app's Settings. There is no embedded provider key and no AI call is made by the local Git or native-phone tools. The selected provider may be a local Ollama endpoint or a cloud provider; cloud-provider billing and data handling remain governed by that provider. Triage requests JSON, restricts categories to Work/Coding/Personal, bounds the task count and field lengths, and rejects blank titles or malformed output. A failed parse leaves the pasted inbox text in the UI and creates no partial task when parsing itself fails. (If the database fails after some inserts, the UI reports the failure; the created rows remain visible rather than being silently discarded.) Model-inferred due dates are optional and should be reviewed before execution.

## Handing a task to an installed AI app

The app discovers installed, launchable packages whose package name or app label identifies ChatGPT, Gemini, DeepSeek or Copilot. When the user assigns one, starting the task moves the existing persisted task through its normal DRAFT/READY/QUEUED/RUNNING state transitions and separately advances its orchestration stage through PLANNING and RUNNING. The app creates a package-specific Android `ACTION_SEND` text intent. This hands the prompt to the selected app; Android and the selected app control what happens next. The user may have to review and tap Send. The app does not claim to bypass the selected AI app's confirmation, login, subscription, policy or UI requirements.

If the app does not accept a text-share intent, the task UI copies the prompt to the clipboard and opens the installed app so the user can paste it manually. If the target package cannot be launched, the task is marked failed rather than complete.

## External-app response capture and OS limitations

Accessibility capture is only armed after the user starts an AI-app task, only for that assigned package, and expires after ten minutes. It stores the latest accessible visible text in process memory until the user selects Capture; it does not capture other app packages. Screens whose accessibility tree contains a password field are skipped. No screen text is written to logs. A successful capture is stored in the task's local notes and moves the separate orchestration stage to VERIFYING.

Accessibility is not a supported ChatGPT/Gemini/DeepSeek/Copilot response API. An app may expose no accessible text, render protected or inaccessible content, use a screen on which the service cannot observe text, or require the user to leave the app before capture. Android may also block or limit accessibility behavior. In those cases the UI explicitly asks the user to paste the response manually. This feature does not bypass Android protections or automate an app's Send button.

## Completion and output verification

An AI-generated text response alone never changes the task to SUCCEEDED. The user must select an output file, the app must read it successfully and confirm that it is non-empty (verification reads up to 100 MiB), and the user must confirm its contents. Only then does the app store the persisted file URI, set the orchestration stage to COMPLETED and transition the existing task state to SUCCEEDED. This is a file-presence/readability check plus user review; the app does not infer that the artifact semantically satisfies the task.

A captured response and a verified output can each be uploaded to Google Drive from the task card when a Drive session is authorized. The existing Drive repository uploads at most 5 MiB per multipart request and verifies the returned Drive file metadata/ID. Larger resumable uploads are not implemented. An expired or denied token requires renewed Drive authorization. Drive OAuth/API calls still require a real account and project configuration; the repository tests do not prove live device access.

## Device and runtime scope

The target Samsung Galaxy M10 baseline remains Android 9 / ARM32 (`armeabi-v7a`). The bundled patched PRoot archive and `setup.sh` are explicitly ARM32: the script accepts Termux `dpkg --print-architecture=arm`, creates a Debian ARM rootfs and refuses other Termux architectures. The current APK does not package an x86_64 PRoot/loader or an AMD64 Debian bootstrap. No 64-bit executable is substituted for the M10. AMD64 support requires a separately built, integrity-pinned matching PRoot/loader archive and an x86_64 Termux/Debian test device; it is not claimed by this release.

## Galaxy M10 manual verification

1. Install the debug APK and open Settings to select/configure an LLM provider. For a local, no-cloud-provider path, configure a reachable Ollama server; otherwise a configured provider may have its own usage cost.
2. Open Task Manager → Inbox, paste a short test email with one or two explicit actions and at most one explicit deadline, then tap AI Triage. Confirm the tasks, titles, categories and due dates in Tasks. A failed provider request or invalid JSON should leave the inbox text intact.
3. Open the AI app you want to use once, then return to the app. Create a task and assign the detected package. Enable the app's Accessibility service only if you want automatic visible-text capture; otherwise plan to paste the response manually.
4. Tap Share prompt with AI app. Confirm that Android opens the selected app with the task prompt. Tap Send in that app if required. Do not assume that sharing submits it.
5. Return to USMAN AND UMAR. Try Capture accessible response. If the target app did not expose readable text, paste a sample response in the manual response field. Confirm the task is still RUNNING / VERIFYING and not SUCCEEDED.
6. Save a small test output file in the AI app or another local location. Tap Select output file · verify & complete, select the file, inspect it, and confirm that task state becomes SUCCEEDED only after it is read and non-empty.
7. Connect Google Drive using the app's Google Drive screen and grant its requested Drive permissions. Use the task card's response or verified-file Drive upload button. Confirm the returned file appears in Drive. Record the exact UI error if authorization/API access fails; it cannot be certified without a live device run.
8. Test dependencies by creating a second task that depends on an unfinished task. Verify that its AI action is disabled until the prerequisite reaches SUCCEEDED. Check Dashboard for Today/Tomorrow/Overdue and Analytics for overall/category completion rates.


### Plan links and artifact confirmation

Inbox Triage can include prerequisite indices in its ordered plan. The parser accepts only unique zero-based references to earlier items; forward links, self-links, malformed indices and blank titles are rejected before task creation. Each accepted link is resolved to the persisted task ID, so dependency gating uses the same Task Manager prerequisite engine as manually created plans.

Output selection opens a review dialog before any success transition. The dialog identifies the selected file, shows byte count and a short SHA-256 prefix, and previews only detected text-like files. Confirming causes the app to re-read the file and compare both byte count and full SHA-256 with the reviewed content. Only after that explicit confirmation does the repository store the artifact URI and mark the task SUCCEEDED. This guards against a file changing between inspection and confirmation; the user remains responsible for judging whether the contents satisfy the task.
