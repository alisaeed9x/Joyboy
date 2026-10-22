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
