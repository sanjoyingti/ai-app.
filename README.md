# AI App

A cross-platform autonomous AI assistant built with Flutter (Android first): chat with Gemini / ChatGPT / Claude, real-time interruptible voice, file analysis, media generation, offline on-device models, and an in-app coding agent that builds APKs.

## Architecture

```
lib/
├── app/            App widget, routing, theme
├── core/           Shared: config, networking, storage, model gateway, safety
└── features/
    ├── chat/       Chat UI, conversations, streaming
    ├── auth/       Google / email login
    ├── voice/      Duplex voice, barge-in, TTS voices
    ├── files/      Upload, parsing, RAG
    ├── media/      Image / video generation
    ├── offline/    On-device models, cloud fallback
    └── agent/      Coding agent, effort levels, APK builds
```

- **State:** Riverpod. **Routing:** go_router. **Structure:** feature-first; each feature has `data/`, `domain/`, `presentation/`.
- **Model gateway (`core/models`):** one interface, many providers (Gemini, OpenAI, Anthropic, local). Features never call a provider directly.
- **Effort levels:** Low / Medium / High / Max map to model choice + reasoning settings per provider.

## Secrets

Never commit API keys.
- Local: `config/dev.json` (gitignored), passed with `--dart-define-from-file=config/dev.json`. Copy `config/example.json` to start.
- CI: GitHub Secrets → written to `config/dev.json` during the build.

## Build

Every push and PR runs `.github/workflows/build-apk.yml`: analyze → test → `flutter build apk --debug`. Download the APK from the run's **Artifacts** section.

See [ROADMAP.md](ROADMAP.md) for phases.
