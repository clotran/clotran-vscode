// test/e2e/install-suite.js -- inside VS Code with a scratch HOME and no
// clotran on PATH (run.js, INSTALL=1): the extension finds none, its
// clotran: Install clotran command runs clotran's install script -- the
// release, downloaded -- and the language server then answers from the
// installed command.
const vscode = require('vscode');
const path = require('path');
const fs = require('fs');
const os = require('os');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
async function until(label, f, secs) {
  for (let i = 0; i < secs * 4; i++) { const v = await f(); if (v) return v; await sleep(250); }
  throw new Error('e2e: timed out waiting for ' + label);
}
const checks = [];
function ok(label, got, want) {
  const pass = JSON.stringify(got) === JSON.stringify(want);
  checks.push(pass);
  console.log((pass ? 'ok   - ' : 'FAIL - ') + label + (pass ? '' : '\n  got:  ' + JSON.stringify(got) + '\n  want: ' + JSON.stringify(want)));
}

exports.run = async () => {
  const root = vscode.workspace.workspaceFolders[0].uri.fsPath;
  const ext = vscode.extensions.getExtension('clotran.clotran');
  await ext.activate();
  ok('with no clotran, no language client is started', ext.exports.testState().languageClient, false);
  const installed = await vscode.commands.executeCommand('clotran.install');
  ok('clotran: Install clotran installs it', installed, true);
  const cmd = process.platform === 'win32'
    ? path.join(process.env.LOCALAPPDATA, 'Programs', 'clotran', 'bin', 'clotran.cmd')
    : path.join(os.homedir(), '.local', 'bin', 'clotran');
  ok('where the install script puts it', fs.existsSync(cmd), true);
  ok('and the language client is started then', ext.exports.testState().languageClient, true);
  const core = vscode.Uri.file(path.join(root, 'src', 'app', 'core.cljf'));
  const doc = await vscode.workspace.openTextDocument(core);
  await vscode.window.showTextDocument(doc);
  const at = doc.positionAt(doc.getText().indexOf('u/shout') + 3);
  const defs = await until('a definition from the installed clotran lsp', async () => {
    const r = await vscode.commands.executeCommand('vscode.executeDefinitionProvider', core, at);
    return r && r.length ? r : null;
  }, 120);
  ok('go to definition answers from the installed clotran', path.basename((defs[0].uri || defs[0].targetUri).fsPath), 'util.cljf');
  if (checks.some((c) => !c)) throw new Error('e2e install: ' + checks.filter((c) => !c).length + ' failed');
  console.log('E2E INSTALL PASSED');
};
