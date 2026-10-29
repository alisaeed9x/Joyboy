# Joyboy TV Skin/Theme Test Matrix

Status meanings: `PASS` = actually executed and passed; `REVIEW` = source inspected but not runtime-tested; `NOT RUN` = not executed.

| Check | Original S | Hub | Glass | Samsung | LG | TCL | Xiaomi |
|---|---|---|---|---|---|---|---|
| Registered once in Skin gallery | REVIEW | REVIEW | REVIEW | REVIEW | REVIEW | REVIEW | REVIEW |
| Separate from Theme IDs | REVIEW | REVIEW | REVIEW | REVIEW | REVIEW | REVIEW | REVIEW |
| Home layout exists | Existing | Existing | Existing | REVIEW | REVIEW | REVIEW | REVIEW |
| Folder card open callback preserved | REVIEW | REVIEW | REVIEW | REVIEW | REVIEW | REVIEW | REVIEW |
| D-pad traversal | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN |
| OK and Back | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN |
| Select Skin then Theme | REVIEW | REVIEW | REVIEW | REVIEW | REVIEW | REVIEW | REVIEW |
| Skin/Theme persists after restart | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN |
| Light and dark Theme compatibility | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN |
| Recent playback card | Existing | Existing | Existing | REVIEW | REVIEW | REVIEW | REVIEW |
| App build | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN | NOT RUN |

## Global checks

| Check | Status | Evidence |
|---|---|---|
| Kotlin manifest includes new source | PASS | `sh dev-tests/check_manifest.sh` |
| Build configuration version 626 / 6.26 | REVIEW | `app/build.gradle.kts` source |
| Android Gradle build | NOT RUN | No Gradle executable/wrapper or configured Android SDK in environment |
| Physical TV remote/device | NOT RUN | No Android TV runtime attached |
