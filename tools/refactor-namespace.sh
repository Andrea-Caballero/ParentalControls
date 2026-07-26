#!/usr/bin/env bash
# =============================================================================
# refactor-namespace.sh
# Atomic rewrite of Kotlin package declarations and import lines for the
# namespace change `com.example.parentalcontrol` -> `com.tudominio.parentalcontrol`.
#
# Why this script (instead of inline sed):
#   - ktlint baseline is line-numbered; the rewrite must be reproducible so
#     the baseline diff stays auditable.
#   - We commit the script + the resulting diff in a single commit, so the
#     line-numbered baseline can be regenerated if needed in PR 4 or later.
#
# Scope:
#   - app/src/main/java/**/*.kt    (package + import lines)
#   - app/src/test/java/**/*.kt    (package + import lines)
#   - app/src/androidTest/java/**/*.kt (package + import lines)
#
# Out of scope (handled by other tasks):
#   - app/build.gradle.kts (gradle file, edited manually)
#   - app/src/main/AndroidManifest.xml (xml, edited manually)
#   - app/google-services.json (user regenerates in Firebase Console)
#
# The directory structure is left as-is intentionally: Kotlin/Gradle do not
# require source path to match package. If KSP/Detekt complain, move the dirs
# with `git mv` in a follow-up commit.
# =============================================================================

set -euo pipefail

OLD_NS="com\.example\.parentalcontrol"
NEW_NS="com.tudominio.parentalcontrol"

# Three regexes:
#   1. package com.example.parentalcontrol         (root package, no trailing dot)
#   2. package com.example.parentalcontrol.something   (sub-package)
#   3. import  com.example.parentalcontrol.something   (imports always have trailing dot)
#
# We run them in this order: sub-package first, then root. This avoids the
# `com.example.parentalcontrol.` prefix being shadowed by the `com.example.parentalcontrol`
# root match (which has an anchored end).

ROOTS=(app/src/main app/src/test app/src/androidTest)

# Sub-package package declaration:  package com.example.parentalcontrol.X  ->  package com.tudominio.parentalcontrol.X
# Root     package declaration:     package com.example.parentalcontrol     ->  package com.tudominio.parentalcontrol
# Import line:                       import  com.example.parentalcontrol.X   ->  import  com.tudominio.parentalcontrol.X
# String literal (rare):             "com.example.parentalcontrol"          ->  "com.tudominio.parentalcontrol"

for root in "${ROOTS[@]}"; do
    if [[ ! -d "$root" ]]; then
        echo "skip: $root (does not exist)"
        continue
    fi
    echo "scanning $root"
    find "$root" -name "*.kt" -print0 | xargs -0 sed -i \
        -e "s|^package ${OLD_NS}\.|package ${NEW_NS}.|g" \
        -e "s|^package ${OLD_NS}\$|package ${NEW_NS}|g" \
        -e "s|^import  ${OLD_NS}\.|import  ${NEW_NS}.|g" \
        -e "s|${OLD_NS}|${NEW_NS}|g"
done

echo
echo "remaining references (should be empty or only in .bak files):"
grep -rn "com\.example\.parentalcontrol" app/src/main app/src/test app/src/androidTest 2>/dev/null || echo "  (none)"
