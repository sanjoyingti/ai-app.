# App structure

How the code in `app/src/main/java/com/aiapp/` is organised, and why.

The layout follows a pattern seen in large chat apps: one thin activity, an app
shell that owns navigation, and one package per feature. It was chosen after
studying the structure of the Claude Android app (`com.anthropic.claude`
1.261109.19). Only the structure informed this design. No code, artwork, names
or branding from that app are used here.

## What the study showed

The study read the app's manifest and the class and package names that survive
in the APK. Most of the app's code is obfuscated (about 56,700 of 61,700 classes
have no readable package), so these are observations about structure, not about
how any feature is implemented.

| Observation | Evidence |
|---|---|
| One main activity; other activities are small entry points (deep links, share, assistant overlay, widget setup) | Manifest: 1 launcher activity, the rest are trampolines |
| One package per feature (chat, chat list, projects, settings, login, voice, artifacts, widgets, notifications) | Readable package names under the app's own namespace |
| An app shell separate from features, split into start-up, logged-in and logged-out parts | `app/appstart`, `app/main/loggedin`, `app/main/loggedout`, `app/shell` |
| Each large feature has its own `navigation` package | `chat/navigation`, `code/remote/navigation` |
| Shared layers sit below features: `core` (DI, logging, telemetry), `networking` (HTTP, SSE), `api` (errors, result types), `db` | Package names |
| Long-running work lives in services, not screens (message streaming, speech playback, media playback) | Manifest services |
| Compose UI, Decompose navigation, Room, DataStore, WorkManager, OkHttp, kotlinx.serialization | Library packages present in the APK |

Not established: how dependency injection is wired, how state is held inside a
feature, or anything about server APIs.

## Layout in this app

```
com.aiapp
├── MainActivity.kt      Thin: theme + AppShell, memory trimming
├── app/                 App shell: Screen (destinations), AppShell (navigation), IncomingShare
├── chat/                Chat screen and its view model
├── chatlist/            Saved chats (History)
├── voice/               Voice mode: phone voice, Gemini Live, voice screen
├── create/              Image and video creation screens
├── settings/            Settings screen
├── builder/             Build Mode agent, GitHub client, build service
│   └── ui/              Build Mode screens, Codespace screen
├── design/              Shared UI: theme, Markdown, progress bar, image and video views
├── llm/                 Model providers, HTTP, retry, web tools
├── media/               Image and video generation back ends
└── data/                Storage: chats, attachments, API keys, downloads
```

What changed from the previous layout: the single `ui/` package (15 files, every
screen in one place) was split into the feature packages above, and navigation
moved out of `MainActivity` into `app/AppShell.kt`. Screens are now a typed
`Screen` enum, not strings. Behaviour is unchanged.

## Rules

1. **Features do not navigate.** A screen takes callbacks (`onBack`,
   `onOpenSettings`); only `AppShell` changes the current screen.
2. **Features do not import each other's screens.** Shared composables go in
   `design/`. Two exceptions exist today and should shrink over time: Build,
   History and Settings take `ChatViewModel`, and Chat hosts the voice screen.
3. **Dependencies point down.** Feature → `design`, `llm`, `media`, `data`.
   Nothing in `llm`, `media` or `data` imports a feature package.
4. **A new feature gets a new package**, with its screen and view model
   together, and one new entry in `Screen`.
5. **Work that must survive leaving the screen goes in a service**, as
   `builder/BuildService` already does.

## Entry points

- **Launcher:** `MainActivity` shows `AppShell`.
- **Share from other apps:** `MainActivity` also accepts shared text, photos and
  files. `app/IncomingShare` passes files to Chat as attachments and puts text in
  the message box, then the shell switches to Chat. The activity is `singleTask`,
  so a share lands in the chat that is already open.

## Not done yet

These follow the same pattern but were left out because each changes behaviour
and needs testing on a phone:

- **Shared state out of `ChatViewModel`.** Provider, model and key settings are
  read by Build, History and Settings through the chat view model. Moving them
  to a small settings store would remove the cross-feature dependency in rule 2.
- **`networking/` layer.** `llm/Http.kt` and `llm/Retry.kt` are general HTTP and
  SSE helpers but depend on `ProviderException` in `llm`. Splitting them needs a
  neutral error type first.
- **Streaming in a service.** Chat replies stream inside the view model, so a
  long reply can stop if Android kills the hidden app. Build Mode already avoids
  this with a foreground service.
- **Database for chats.** Chats are one JSON file each, read in full to list
  them. Fine for now; Room becomes worth it when lists get slow.
