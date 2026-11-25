#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
FS_ROOT=${DEAL_FS_ROOT:-"$ROOT/../deal"}
"$ROOT/scripts/compile.sh"
bash "$ROOT/scripts/test-reconcile.sh"
TEST_CLASSES="$ROOT/build/test-classes"
rm -rf "$TEST_CLASSES"
mkdir -p "$TEST_CLASSES"
TEST_SOURCES=(
  "$ROOT"/src/test/java/deal/ui/UiDiagnosticsV3Test.java
  "$ROOT"/src/test/java/deal/ui/UiNullableElementsTest.java
  "$ROOT"/src/test/java/deal/ui/UiBorrowedValuesTest.java
  "$ROOT"/src/test/java/deal/ui/UiDeterminismTest.java
  "$ROOT"/src/test/java/deal/ui/UiLiteralPreservationTest.java
  "$ROOT"/src/test/java/deal/ui/UiIterationIdentityTest.java
  "$ROOT"/src/test/java/deal/ui/UiRootTypeTest.java
  "$ROOT"/src/test/java/deal/ui/UiNullishTypesTest.java
  "$ROOT"/src/test/java/deal/ui/UiAssignabilityTest.java
  "$ROOT"/src/test/java/deal/ui/UiComparisonsTest.java
  "$ROOT"/src/test/java/deal/ui/UiPresenceTest.java
  "$ROOT"/src/test/java/deal/ui/UiActionSamplesTest.java
  "$ROOT"/src/test/java/deal/ui/UiLexicalParityTest.java
  "$ROOT"/src/test/java/deal/ui/UiNominalIdentityTest.java
  "$ROOT"/src/test/java/deal/ui/UiPackPathsTest.java
  "$ROOT"/src/test/java/deal/ui/UiPropGenerationTest.java
  "$ROOT"/src/test/java/deal/ui/UiActionForwardingTest.java
  "$ROOT"/src/test/java/deal/ui/UiFrameworkTest.java
  "$ROOT"/src/test/java/deal/ui/UiRuntimeInvariantTest.java
  "$ROOT"/src/test/java/deal/ui/UiCompilerWorkspaceTest.java
  "$ROOT"/src/test/java/deal/ui/UiCheckpointTest.java
)
javac --release 25 -Xlint:all -Werror -cp "$ROOT/build/classes:$FS_ROOT/build" -d "$TEST_CLASSES" "${TEST_SOURCES[@]}"
(cd "$ROOT" && java -ea -cp "$TEST_CLASSES:$ROOT/build/classes:$FS_ROOT/build" deal.ui.UiDeterminismTest)
(cd "$ROOT" && java -ea -Ddeal.home="$FS_ROOT" -cp "$TEST_CLASSES:$ROOT/build/classes:$FS_ROOT/build" deal.ui.UiLiteralPreservationTest)
(cd "$ROOT" && java -ea -Ddeal.home="$FS_ROOT" -cp "$TEST_CLASSES:$ROOT/build/classes:$FS_ROOT/build" deal.ui.UiIterationIdentityTest)
java -ea -cp "$TEST_CLASSES:$ROOT/build/classes:$FS_ROOT/build" deal.ui.UiRootTypeTest
java -ea -cp "$TEST_CLASSES:$ROOT/build/classes:$FS_ROOT/build" deal.ui.UiNullishTypesTest
java -ea -cp "$TEST_CLASSES:$ROOT/build/classes:$FS_ROOT/build" deal.ui.UiAssignabilityTest
java -ea -cp "$TEST_CLASSES:$ROOT/build/classes:$FS_ROOT/build" deal.ui.UiComparisonsTest
java -ea -cp "$TEST_CLASSES:$ROOT/build/classes:$FS_ROOT/build" deal.ui.UiPresenceTest
java -ea -cp "$TEST_CLASSES:$ROOT/build/classes:$FS_ROOT/build" deal.ui.UiLexicalParityTest
java -ea -cp "$TEST_CLASSES:$ROOT/build/classes:$FS_ROOT/build" deal.ui.UiNominalIdentityTest
(cd "$ROOT" && java -ea -Ddeal.home="$FS_ROOT" -cp "$TEST_CLASSES:$ROOT/build/classes:$FS_ROOT/build" deal.ui.UiPackPathsTest)
(cd "$ROOT" && java -ea -Ddeal.home="$FS_ROOT" -cp "$TEST_CLASSES:$ROOT/build/classes:$FS_ROOT/build" deal.ui.UiPropGenerationTest)
(cd "$ROOT" && java -ea -Ddeal.home="$FS_ROOT" -cp "$TEST_CLASSES:$ROOT/build/classes:$FS_ROOT/build" deal.ui.UiActionForwardingTest)
(cd "$ROOT" && java -ea -Ddeal.home="$FS_ROOT" -cp "$TEST_CLASSES:$ROOT/build/classes:$FS_ROOT/build" deal.ui.UiActionSamplesTest)
java -ea -cp "$TEST_CLASSES:$ROOT/build/classes:$FS_ROOT/build" deal.ui.UiDiagnosticsV3Test
java -ea -Ddeal.home="$FS_ROOT" -cp "$TEST_CLASSES:$ROOT/build/classes:$FS_ROOT/build" deal.ui.UiNullableElementsTest
java -ea -Ddeal.home="$FS_ROOT" -cp "$TEST_CLASSES:$ROOT/build/classes:$FS_ROOT/build" deal.ui.UiBorrowedValuesTest
java -ea -Djava.awt.headless=true -cp "$TEST_CLASSES:$ROOT/build/classes:$FS_ROOT/build" deal.ui.UiFrameworkTest
java -ea -Djava.awt.headless=true -cp "$TEST_CLASSES:$ROOT/build/classes:$FS_ROOT/build" deal.ui.UiRuntimeInvariantTest
java -ea -Djava.awt.headless=true -cp "$TEST_CLASSES:$ROOT/build/classes:$FS_ROOT/build" deal.ui.UiCompilerWorkspaceTest
# CLI resolves both compiler and framework resources independently of caller cwd.
(cd "${TMPDIR:-/tmp}" && "$ROOT/bin/deal-ui" check "$ROOT/examples/museum/gallery.dealui")
# The fixture host supplies only loading/transport; codec and lifecycle are Deal UI.
(cd "$ROOT" && java -ea -Ddeal.home="$FS_ROOT" -cp "$TEST_CLASSES:$ROOT/build/classes:$FS_ROOT/build" deal.ui.UiCheckpointTest "${DEAL_UI_TEST_HOST:-$ROOT/../deal-embedding/android/assets/embedding/bindings}")
