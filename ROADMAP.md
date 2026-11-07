# Roadmap

Each phase ships as small `jules`-labelled issues, one PR each. A phase is done when all its issues are merged, CI is green, and the APK works on a real phone.

| Phase | Scope | Done when |
|---|---|---|
| 1. Setup | Flutter skeleton, folder structure, CI building a debug APK on every push | APK artifact installs and opens |
| 2. Chat + cloud models | Claude-style chat UI, Gemini / ChatGPT / Claude switchable, Google + email login | Streaming chat works with all 3 providers |
| 3. Voice | Real-time interruptible voice, natural male/female voices | Speaking over the reply stops it within ~150 ms |
| 4. Files + media | PDF/TXT/CSV/code/image analysis, image and video generation | Answers cite uploaded files; prompt → image/clip |
| 5. Offline models | On-device model (llama.cpp), cloud fallback | Chat works in airplane mode |
| 6. Coding agent | In-app agent, Low/Medium/High/Max effort, builds APKs via GitHub Actions | Described app → installable APK link |
