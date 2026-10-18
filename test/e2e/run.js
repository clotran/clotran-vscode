// test/e2e/run.js -- VS Code itself, downloaded once into .vscode-test/,
// started on test/fixtures/proj with this extension, running suite.js
// inside it. `clotran` must be on PATH (test/run.sh puts the checkout there).
const path = require('path');
const { runTests } = require('@vscode/test-electron');
(async () => {
  try {
    await runTests({
      version: process.env.VSCODE_VERSION || '1.95.0', // the engines version package.json declares
      extensionDevelopmentPath: path.resolve(__dirname, '../..'),
      extensionTestsPath: path.resolve(__dirname, 'suite.js'),
      launchArgs: [path.resolve(__dirname, '../fixtures/proj'), '--disable-extensions', '--disable-gpu'],
    });
  } catch (e) {
    console.error('e2e: failed', e);
    process.exit(1);
  }
})();
