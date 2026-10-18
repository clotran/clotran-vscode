#!/bin/sh
# test/run.sh -- the extension's ClojureScript compiled (shadow-cljs) and
# its tests run under Node against a real `clotran nrepl`. Needs node and
# npm; CLOTRAN is the clotran command (default: the checkout beside this
# repository).
here=$(cd "$(dirname "$0")/.." && pwd)
cd "$here" || exit 1
[ -d node_modules ] || npm install --no-audit --no-fund || exit 1
npx shadow-cljs compile ext test || exit 1
CLOTRAN="${CLOTRAN:-$here/../clotran/clotran}" node out/test.js || exit 1
# VS Code itself (downloaded once into .vscode-test/) on test/fixtures/proj,
# with `clotran` from the checkout on its PATH; E2E=0 skips it
if [ "${E2E:-1}" != 0 ]; then
  # node's status, not grep's: a failing run printed through a pipe passed
  log=$(mktemp) || exit 1
  PATH="$(dirname "${CLOTRAN:-$here/../clotran/clotran}"):$PATH" node test/e2e/run.js >"$log" 2>&1
  st=$?
  grep -v '^\[main\|^Warning\|Electron' "$log"; rm -f "$log"
  [ $st -eq 0 ] || { echo "CLOTRAN-VSCODE TESTS FAILED"; exit 1; }
fi
echo "CLOTRAN-VSCODE TESTS PASSED"
