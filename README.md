# AI App

Android AI assistant (Kotlin + Jetpack Compose). Chat with Claude, Gemini or ChatGPT using your own API keys, with a model picker and Low/Medium/High/Max effort levels.

## Install

Every push to `main` builds a debug APK in GitHub Actions and publishes it as the latest release:
**Releases → latest → `AIApp-N.apk`**. Each build installs over the previous one.

## Setup in the app

Settings → paste your API keys (stored encrypted on the phone, never in this repo).

## Status

Phase 1 (v0.1): streaming chat with Claude, Gemini and ChatGPT, model picker, Auto/Low/Medium/High/Max effort, Stop.
Next (Phase 2): conversation history, Markdown rendering, Google/email login. See `docs/BLUEPRINT.md`.

## Phase 1 test checklist

For each of Claude, Gemini and ChatGPT:

1. Paste the key in Settings. The model list fills in.
2. Send a message at Effort: Auto. "Thinking…" then "Writing…" appear and the reply streams in.
3. Send a long request and tap Stop. The reply stops, and the text written so far stays.
4. Switch to Effort: Max and send. It still answers (no 400 error).
5. Put in a wrong key. You get a "key was rejected" message, and your text goes back in the box.
6. Type with the keyboard open. The message box stays above the keyboard.

## Tests

`./gradlew testDebugUnitTest` covers request bodies, stream parsing and fallbacks for all three providers. CI runs it before every APK build.
