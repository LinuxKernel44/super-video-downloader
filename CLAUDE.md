# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

Android video/audio downloader with an integrated browser, player, and multiple custom downloader
backends (package `com.myAllVideoBrowser`). Single Gradle module (`:app`) plus two vendored native
components that are **not** Gradle subprojects but are built via custom `Exec` tasks wired into the
`:app` build:

- `rust_adblock/` — a Rust crate (`adblock_rust_jni`, wraps Brave's `adblock` engine) exposing JNI
  bindings consumed by `AdBlockEngine.kt`. Built by the `buildRustAdblock` Gradle task via `cargo`.
- `v2ray-src/` — a **git submodule** (`2dust/AndroidLibXrayLite`, a Go wrapper around Xray-core)
  exposing JNI bindings (`libgojni.so`) consumed by `V2Ray.kt`. Built by the `prepareGoBuild` /
  `copyAllGoSharedLibs` Gradle tasks via `go build`.

Both produce per-ABI `.so` files that are copied into `app/src/main/jniLibs/<abi>/` before the
Android build packages them. That directory is generated on every build and is git-ignored — do not
expect it to be present after a fresh clone, and do not hand-edit its contents.

## Prerequisites

1. **JDK 21**
2. **Android SDK + NDK** — NDK r29 preferred. `app/build.gradle.kts` first looks for
   `<sdk>/ndk/29.0.14206865`, then `ANDROID_NDK_HOME`/`ANDROID_NDK_ROOT`, then `ndk.dir` in
   `local.properties`. If only a different NDK 29.x patch version is installed, set
   `ndk.dir=<path>` in `local.properties` explicitly — the preferred-version check is an exact
   string match, not a semver range.
3. **Rust**, installed via `rustup` (not the distro `cargo`/`rustc` packages — the Gradle script
   invokes `$HOME/.cargo/bin/cargo` directly, or `%USERPROFILE%\.cargo\bin\cargo.exe` on Windows).
   `rust_adblock/rust-toolchain.toml` pins the exact channel (`1.96.0`) and the four Android targets
   (`aarch64-linux-android`, `armv7-linux-androideabi`, `x86_64-linux-android`,
   `i686-linux-android`); running any `cargo`/`rustc` command inside `rust_adblock/` auto-installs
   that toolchain + targets the first time, no manual `rustup target add` needed.
4. **Go**, version matching `v2ray-src/go.mod`'s `go` directive (currently `1.27`). With the default
   `GOTOOLCHAIN=auto`, an older locally-installed `go` (e.g. the distro package) will silently
   download and switch to the required toolchain on first build, given network access. The Gradle
   script auto-detects `go` from `GO_EXECUTABLE` env var, the `GO_EXECUTABLE` Gradle property, or a
   fixed list of common install paths (see `README.md` for overriding it).
5. **C/C++ build tools** (`build-essential` on Linux, Xcode CLT on macOS, VS Build Tools + Windows
   SDK on Windows) — needed by the NDK toolchain invoked from the Rust build.
6. Initialize the git submodule after cloning: `git submodule update --init --recursive`.

`local.properties` (git-ignored) needs at least `sdk.dir=`; add `ndk.dir=` if the preferred NDK
version isn't installed.

## Common commands

```bash
# Debug build
./gradlew :app:assembleDebug

# Release build (see "Release signing" below for how it gets signed)
./gradlew :app:assembleRelease

# Rust adblock engine only (all 4 ABIs, copies .so into app/src/main/jniLibs)
./gradlew :app:buildRustAdblock

# Go/v2ray native libs only
./gradlew :app:buildAllGoLibraries

# Clean everything (including generated native libs)
./gradlew clean
```

Single-ABI, non-split builds (faster local iteration): pass `-PABI_FILTERS=arm64-v8a` (any one of
`armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64`). Multiple comma/semicolon-separated values keep
per-ABI split APKs; a single value forces one non-split APK for just that ABI. Independently,
`SPLITS_INCLUDE=false` (env var) forces a single universal APK without restricting which ABIs are
included.

There are currently no `app/src/test` or `app/src/androidTest` source sets in this repo, despite
JUnit/Mockito/Espresso being on the dependency list — there is no existing test suite/command to
run.

### Release signing

`signingConfigs.release` in `app/build.gradle.kts` reads four environment variables:
`KEYSTORE_PATH` (defaults to `keystore.jks` in the repo root if unset), `KEYSTORE_PASSWORD`,
`KEY_ALIAS`, `KEY_PASSWORD`. The release build type only attaches this signing config if
`KEYSTORE_PASSWORD` is non-blank; otherwise it builds an **unsigned** release APK. There is no
keystore committed to the repo (this is a personal fork of `alexch33/super-video-downloader` and
does not have the upstream maintainer's signing key) — a self-signed keystore for local/sideload
builds lives at `keystore/release.jks` (git-ignored; back it up outside the repo, it's irreplaceable
for producing updates that Android will accept over a previous install signed with the same key).

### Memory during native builds

A full `assembleRelease` (Rust ×4 ABIs + Go ×4 ABIs + Kotlin/kapt/KSP + R8) is memory-hungry. On a
constrained/shared host this can trigger the OOM killer mid-build (observed killing the Gradle
daemon during `packageRelease`). If that happens: `./gradlew --stop`, free memory from any leftover
`KotlinCompileDaemon` process, and retry with `--max-workers=2` (or lower).

### F-Droid reproducibility

`verifyOfflineSources` (runs as part of `preBuild`) checks F-Droid reproducible-build compliance —
the project vendors Rust dependencies (`rust_adblock/vendor/`, `rust_adblock/.cargo/`, both
explicitly un-ignored in `.gitignore`) so `cargo build` can run fully offline.

## Architecture

**DI**: classic Dagger 2 (`dagger-android`), not Hilt. The graph root is
`di/component/AppComponent.kt`; modules live under `di/module/` (`AppModule`, `DatabaseModule`,
`NetworkModule`, `RepositoryModule`, `UtilModule`, `ViewModelModule`, plus per-activity modules
under `di/module/activity/`). `DLApplication` (the `Application` subclass) builds the component and
also seeds `ContextUtils` with a static application `Context` — several utility singletons (notably
`FileUtil`) read `ContextUtils.getApplicationContext()` directly instead of receiving `Context`
through DI, since they're also touched from `companion object`/static contexts.

**UI**: MVVM with `ViewModel` + `ObservableField`/`ObservableBoolean` LiveData-style bindings,
Android Views + DataBinding (`android:onClick="@{() -> viewModel.doThing()}"` style, no Compose).
Fragments pull their `ViewModel` from the hosting `MainActivity` (shared/activity-scoped ViewModels,
not fragment-scoped) rather than through a `ViewModelProvider.Factory` per-fragment.

**Downloads**: `SystemDownloadManager` (`util/downloaders/SystemDownloadManager.kt`) is the single
entry point (`IDownloader` interface) that the UI calls into. It doesn't download anything itself —
it inspects flags on the `VideoInfo` being downloaded and dispatches to one of three concrete
downloader singletons, each a `GenericDownloader` subclass built on WorkManager unique work:
- `CustomRegularDownloader` (`util/downloaders/custom_downloader/`) — plain/regular file downloads.
- `SuperXDownloader` (`util/downloaders/super_x_downloader/`) — HLS/DASH stream capture, including
  live streams; has pluggable `strategy/` implementations (`HlsDownloader`, `HlsLiveDownloader`,
  `MpdDownloader`, `MpdLiveDownloader`, `ManifestDownloader`) and a `control/FileBasedDownloadController`.
- `YoutubeDlDownloader` (`util/downloaders/youtubedl_downloader/`) — falls back to `youtubedl-android`
  for the long tail of supported sites.

All three write segments to `FileUtil.tmpDir` (an app-private, per-work-id scratch directory) during
the download, then call `FileUtil.moveMedia()` once at the end to relocate the finished file to its
real destination — this is the single seam where the actual download location is decided, so
downloader code itself never needs to know *where* files end up.

**Download destination** (`util/FileUtil.kt`): three mutually-exclusive storage modes toggled from
Settings and persisted via `SharedPrefHelper`, mirrored into static flags on `FileUtil` at app
startup (`DLApplication.initializeFileUtils()`):
- `IS_EXTERNAL_STORAGE_USE` / `IS_APP_DATA_DIR_USE` — the original three combinations (public
  Downloads folder, app-private external dir, app-private internal dir), all resolved as a plain
  `java.io.File` via `FileUtil.folderDir`.
- `IS_CUSTOM_DIR_USE` + `CUSTOM_DOWNLOAD_URI` — a user-picked SAF (Storage Access Framework) tree
  URI (`ACTION_OPEN_DOCUMENT_TREE`, persisted read/write permission), used when the user selects
  "Custom folder" in Settings. This path is checked first in `moveMedia()`/`listFiles`/free-space
  checks and goes through `DocumentFile`/`ContentResolver` instead of `java.io.File`, since scoped
  storage (API 29+) does not allow direct filesystem access to arbitrary user-picked directories.
  `isFileApiSupportedByUri()` is the switch used throughout `FileUtil` to decide whether a given
  `Uri` can be treated as a raw file (private app storage, pre-Q public storage) or must go through
  `ContentResolver`/`DocumentsContract` (MediaStore-backed Q-era downloads, or any SAF document URI).

**Native bridges**: `AdBlockEngine.kt` (`System.loadLibrary("adblock_rust_jni")`) and `V2Ray.kt`
(`System.loadLibrary("gojni")`) are thin `external fun` JNI wrappers around the Rust and Go
components described above. `ProxyManager` (`util/proxy_utils/proxy_manager/`) drives `V2Ray` for
proxy-chaining/DoH/DoT support.

**Data layer**: Room (`data/local/room/`, current `DB_VERSION` in `AppDatabase.kt`; exported schemas
under `app/schemas/` are committed and should be updated — new version's JSON added — whenever
`DB_VERSION` changes) plus Retrofit/OkHttp for remote calls (`data/remote/`), fronted by
`data/repository/` repositories injected into ViewModels.
