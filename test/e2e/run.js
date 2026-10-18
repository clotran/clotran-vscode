// test/e2e/run.js -- VS Code itself, downloaded once into .vscode-test/,
// started on test/fixtures/proj with this extension, running suite.js
// inside it. `clotran` must be on PATH (test/run.sh puts the checkout there).
// INSTALL=1 runs install-suite.js instead, with a scratch HOME and no
// clotran on PATH, so the extension installs the release itself.
// VSIX=file runs the packaged extension -- the .vsix unpacked -- not this
// folder, so what the Marketplace would ship is what is tested.
const path = require('path');
const fs = require('fs');
const os = require('os');
const { execFileSync } = require('child_process');
const { runTests } = require('@vscode/test-electron');
(async () => {
  const install = process.env.INSTALL === '1';
  let env = {};
  if (install) {
    const home = fs.mkdtempSync(path.join(os.tmpdir(), 'clotran-vscode-home-'));
    const dirs = (process.env.PATH || '').split(path.delimiter)
      .filter((d) => !['clotran', 'clotran.cmd'].some((c) => fs.existsSync(path.join(d, c))));
    env = { HOME: home, USERPROFILE: home, LOCALAPPDATA: path.join(home, 'AppData', 'Local'), PATH: dirs.join(path.delimiter) };
    console.log('e2e install: HOME', home);
  }
  let ext = path.resolve(__dirname, '../..');
  if (process.env.VSIX) {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'clotran-vsix-'));
    execFileSync('unzip', ['-q', path.resolve(process.env.VSIX), 'extension/*', '-d', dir]);
    ext = path.join(dir, 'extension');
    console.log('e2e: the packaged extension,', process.env.VSIX);
  }
  try {
    await runTests({
      version: process.env.VSCODE_VERSION || '1.95.0', // the engines version package.json declares
      extensionDevelopmentPath: ext,
      extensionTestsPath: path.resolve(__dirname, install ? 'install-suite.js' : 'suite.js'),
      extensionTestsEnv: env,
      launchArgs: [path.resolve(__dirname, '../fixtures/proj'), '--disable-extensions', '--disable-gpu'],
    });
  } catch (e) {
    console.error('e2e: failed', e);
    process.exit(1);
  }
})();
