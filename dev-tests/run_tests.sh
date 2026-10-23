#!/bin/sh
# اختبارات JVM نقية (محتاج kotlinc + kotlin في PATH). كل اختبار بيتكومبايل لوحده. بيفشل لو أي اختبار فشل.
set -e
K=app/src/main/java/com/vanilla/tvlauncher
run() {  # run <TestName> <source files...>
  t=$1; shift
  out=$(mktemp -d); kotlinc "$@" dev-tests/$t.kt -d "$out" 2>&1 | grep -E "^e:|error:" && exit 1 || true
  echo "== $t"; kotlin -Dfile.encoding=UTF-8 -cp "$out" ${t}Kt
}
run BackRouterTest  $K/BackRouter.kt
run HomePrefsTest   $K/HomePrefs.kt
run LibraryLogicTest $K/LibraryLogic.kt
run HistoryLogicTest $K/LibraryLogic.kt $K/HistoryLogic.kt
run SearchLogicTest $K/LibraryLogic.kt $K/HistoryLogic.kt $K/SearchLogic.kt
run PlayerLogicTest $K/LibraryLogic.kt $K/PlayerLogic.kt
run YtLogicTest $K/LibraryLogic.kt $K/YtLogic.kt
run SaverLogicTest $K/SaverLogic.kt
run AppsLogicTest $K/LibraryLogic.kt $K/AppsLogic.kt
run AppearanceTest $K/Theme.kt $K/Appearance.kt
run BackupLogicTest $K/BackupLogic.kt
run SubLogicTest $K/SubLogic.kt
