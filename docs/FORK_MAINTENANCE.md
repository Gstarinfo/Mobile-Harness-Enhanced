# Mobile Harness Enhanced — Fork Maintenance & Upstream Sync Guide

This document describes how **Mobile Harness Enhanced** ([Gstarinfo/Mobile-Harness-Enhanced](https://github.com/Gstarinfo/Mobile-Harness-Enhanced)) is structured, how its extra features are maintained, and how to keep it synchronized with the original repository ([techjarves/Mobile-Harness](https://github.com/techjarves/Mobile-Harness)).

---

## 1. Modular Architecture

To prevent merge conflicts when the original repository releases new versions, all custom enhancements are strictly isolated into dedicated modular packages rather than modified inline in upstream files:

### Custom Code Location (`com.jarves.mh.enhanced`)
- **`app/src/main/java/com/jarves/mh/enhanced/EnhancedFileOperations.kt`**
  - Path boundary validation (`resolveWorkspaceFile`) preventing directory traversal attacks.
  - Full binary and APK chunked streaming with file descriptor sync (`exportSingleFile`).
  - Project zip export with progress tracking (`exportProjectZip`).
  - External app viewer and editor launcher (`openWithFile`).
  - Direct workspace APK installer (`installWorkspaceApk`).
  - MIME type resolution and human-readable byte formatting.
- **`app/src/main/java/com/jarves/mh/enhanced/EnhancedUiComponents.kt`**
  - `EnhancedExportProgressDialog`: Live progress dialog displaying bytes, percentages, and file names during file/project exports.
  - `EnhancedFileMenuItems`: Dropdown menu items for "Open with…", "Export file…", and "Install APK".

### Upstream Hook Points (Kept Minimal)
- **`app/src/main/java/com/jarves/mh/ui/PocketDevApp.kt`**: Connects "Open with…", "Install APK", and the export progress dialog using ~20 lines of extension hooks.
- **`app/src/main/java/com/jarves/mh/ui/MainViewModel.kt`**: Lightweight delegates calling `EnhancedFileOperations`.
- **`app/build.gradle.kts`**: Sets `applicationId = "com.jarves.mh.enhanced"` for side-by-side installation on Android devices alongside the official app.
- **`app/src/main/cpp/CMakeLists.txt`**: Generates `proot_preinclude.h` to fix CMake compilation on workspace paths containing spaces, and isolates the shared memory cache directory to `com.jarves.mh.enhanced`.
- **`app/src/main/java/com/jarves/mh/runtime/AntigravityAuthController.kt`**: Auto-installs the Antigravity CLI bundle on login and restricts OAuth URLs to Google domains.
- **`app/src/main/java/com/jarves/mh/runtime/RuntimeInstaller.kt`**: Adds asset stream fallback for online runtime bundle staging.

---

## 2. How to Update from Upstream

Whenever the upstream repository pushes new commits or tags (e.g. `v1.0.8`, `v1.0.9`), you can update your fork using either method below:

### Method A: Automated CLI Script (Recommended)

Run the included sync script from the repository root:

```bash
./scripts/sync-upstream.sh
```

This script will:
1. Fetch latest commits from `techjarves/Mobile-Harness`.
2. Check how many commits upstream is ahead.
3. Prompt you to rebase your modular commits on top.
4. Run `./gradlew testOnlineDebugUnitTest` to guarantee stability.
5. Provide the exact push command to publish to your fork.

### Method B: Manual Git Commands

```bash
# 1. Fetch upstream
git fetch upstream main

# 2. Rebase your custom commits on top of upstream
git rebase upstream/main

# 3. Run unit tests
./gradlew testOnlineDebugUnitTest

# 4. Push to your GitHub fork
git push origin main --force-with-lease
```

---

## 3. GitHub Actions Automated Sync

The repository includes [`.github/workflows/sync-upstream.yml`](../.github/workflows/sync-upstream.yml) which:
- Runs automatically daily at 02:00 UTC (and can be triggered manually in the GitHub Actions tab).
- Checks if `techjarves/Mobile-Harness:main` has new commits.
- If upstream has updated, pushes an `upstream-sync-latest` branch to your repository so you can review changes and merge.

---

## 4. Running Verification Tests

To verify that all custom file operations, OAuth security validations, and core features pass:

```bash
./gradlew testOnlineDebugUnitTest
```

Key test suites:
- `WorkspaceFileOperationsTest.kt`: Validates path confinement, binary-safe file export, MIME types, and file operations.
- `SecurityAndPrivacyAuditTest.kt`: Validates tool description redaction, telemetry prevention, and OAuth security.
