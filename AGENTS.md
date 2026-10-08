Load and read the memory file from '/home/ailife/.claude/projects/-home-ailife-Documents-QQPro/memory/MEMORY.md' at the start of each session if it exists, and use its contents to restore context about the project.

# qqmax_plus (QQ Max)

QQ smartwatch mod: at build time it patches the original QQ APK using **ApkMixin**, a custom Gradle plugin (`ApkMixin/`) that replaces methods in the target APK's smali with Kotlin hook classes. All feature code lives in `app/src/main/java/momoi/mod/qqpro/`.

## Build (there are no tests)

- Requires **JDK 21**. `./gradlew MixinApk-release` → `app/dist/QQMax_<version>.apk`; `MixinApk-debug` is faster. Add `-PuseProcessorCountAsThreadCount=true` to parallelize smali work.
- The release version comes from `apkMixin.versionName` in `app/build.gradle.kts` (not android `defaultConfig`, which stays `1.0`) — it also drives the output filename.
- CI quirk: `.github/workflows/android_build.yml` builds on push to `main` and auto-releases, but its artifact glob is `app/dist/QQPro*.apk` while actual output is `QQMax_*.apk` — release assets can come up empty.
- No test/lint/typecheck tasks exist; a successful Gradle build is the only verification.

## Hook rules (ApkMixin)

Authoritative reference: **`docs/ApkMixin.md`** (covers `@StaticHook`, `@PrivateCall`, `@ConstructorHook`, resource injection, manifest merge, and a ranked list of compile/runtime traps). Essentials:

- A hook is a Kotlin class extending the QQ target class, annotated `@Mixin`; `override fun` replaces the method body, `super.method()` calls the original.
- **One source `.kt` file maps to exactly one target class** — extra targets are silently ignored.
- Never add initialized fields or rewrite `<init>` in a `@Mixin` class (use `@ConstructorHook`).
- Helpers referenced from a `@Mixin` body must be `public` (top-level `private` compiles to package-private → runtime `IllegalAccessError`), and anonymous classes in hook bodies break the same way — move listeners into non-inline helper functions in `lib/`.
- After editing `app/mixin/inject/` or `inject-res/` inputs, incremental builds may reuse stale output: `rm -f app/dist/*.apk && ./gradlew MixinApk-debug --rerun-tasks`.

## Conventions

- New files/classes: **English names only**. Legacy Chinese-named files (`设置页.kt`, `禁言.kt`, …) exist — don't imitate them.
- All views are built with the Kotlin DSL in `lib/` (`ViewDSL.kt` etc.) — there are no XML layouts.
- Logging: `Utils.log(...)` writes `qqpro_debug.log` under the app's cache dir (`/sdcard/Android/data/com.tencent.qqlite/cache/` when external storage is mounted; falls back to internal cache) — the watch ROM strips `android.util.Log`, so `adb logcat` is unreliable and `adb logcat -c` must never be run. In release builds logging is off unless the user enables it. Toasts: `Utils.toast(...)`, never `android.widget.Toast`.
- Global chat state: `CurrentContact`, `CurrentMsgList`, `SelfContact` in `hook/action/` — query these for the active context instead of threading state through hooks.
- applicationId is `com.tencent.qqlite` (the mod masquerades as QQ itself).
- No third-party HTTP/DI libraries: networking is raw `HttpURLConnection` (`api/Http.kt`), servers are hand-rolled `ServerSocket` (see `mcp/McpServer.kt`), JSON is `org.json`. Keep it that way.

## Compile-only QQ stubs

- `app/libs/source.jar` holds stub classes of the target APK (compile-only; real classes come from the patched APK at runtime). The `patchStubJar` task in `app/build.gradle.kts` auto-generates a build-time copy with `EnclosingMethod` stripped so D8 accepts those stubs — hooks compile against that, not the raw jar.
- `ApkMixin-gen-dep` is a standalone JVM tool (`Main.kt`) that regenerates `app/libs/source.jar` from `raw.jar`; it is not part of the normal build.

## Understanding QQ internals

Decompiled target sources are gitignored but expected: `app/decompiled/jadx/` (readable Java; jadx renames fields) and `app/decompiled/apktool/` (smali is ground truth for runtime field/method names). Regenerate:

```bash
jadx -d app/decompiled/jadx --no-res --show-bad-code app/mixin/source.apk
apktool d -f -o app/decompiled/apktool app/mixin/source.apk
```

Verify a hook against the built artifact, never the source tree: `apktool d -r -f -o /tmp/check app/dist/QQMax_*.apk`, then grep the target `.smali` for the replaced body / injected fields.
