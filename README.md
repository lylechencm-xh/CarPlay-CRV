# CarPlay-CRV

Android head-unit CarPlay receiver project based on DiPlay.

Upstream: shihabal3amri/DiPlay

## CR-V compatibility baseline — DO NOT RAISE

- Head unit OS: **Android 4.2.2**
- Android API level: **17**
- CR-V application `minSdk`: **17**
- CR-V legacy compatibility module `minSdk`: **17**
- Wired USB CarPlay is the primary path.
- Preserve upstream attribution and licensing.
- Keep modern-only Wi-Fi P2P / MapEmbed / hotspot / HUD paths isolated from the API17 runtime.
- New CR-V runtime code must not require Android API 18 or newer.
- If upstream code requires API18+, adapt it behind an API17-compatible implementation or keep it outside `crvlegacy`.
- Do **not** raise the CR-V baseline to make a build pass.

CI enforces this baseline with:
1. fixed `minSdk=17` verification;
2. API17 Java-library compatibility scanning;
3. Android Lint `NewApi` checks;
4. APK manifest verification before publishing.
