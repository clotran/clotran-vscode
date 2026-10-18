// test/e2e/suite.js -- inside VS Code: the extension activated for the
// fixture workspace; VS Code's own provider commands answer through the
// language client (definition, hover, formatting); then a REPL is started
// and a form evaluated at the cursor, its result read back through the
// extension's testState export.
const vscode = require('vscode');
const path = require('path');
const assert = require('assert');

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
  ok('the extension activates for a workspace with .cljf files', ext.isActive, true);
  ok('and starts the language client', ext.exports.testState().languageClient, true);

  const core = vscode.Uri.file(path.join(root, 'src', 'app', 'core.cljf'));
  const doc = await vscode.workspace.openTextDocument(core);
  const editor = await vscode.window.showTextDocument(doc);
  const text = doc.getText();
  const posOf = (needle, skip) => doc.positionAt(text.indexOf(needle) + (skip || 0));

  // definition, through VS Code's own command: the language client must be up
  const defs = await until('a definition from clotran lsp', async () => {
    const r = await vscode.commands.executeCommand('vscode.executeDefinitionProvider', core, posOf('u/shout', 3));
    return r && r.length ? r : null;
  }, 90);
  ok('go to definition of u/shout lands in util.cljf, line 3',
     [path.basename(defs[0].uri.fsPath), defs[0].range.start.line], ['util.cljf', 2]);
  const fdefs = await vscode.commands.executeCommand('vscode.executeDefinitionProvider', core, posOf('mylib.raw/scale', 12));
  ok('and of mylib.raw/scale in the Fortran source', [path.basename(fdefs[0].uri.fsPath), fdefs[0].range.start.line], ['mylib.f90', 3]);
  const hovers = await vscode.commands.executeCommand('vscode.executeHoverProvider', core, posOf('u/shout', 2));
  const hoverText = hovers.map((h) => h.contents.map((c) => c.value || c).join('')).join('');
  ok('hover carries the docstring', hoverText.includes('Louder.'), true);

  // formatting: a messy buffer, formatted by cljfmt on clotran through the server
  const messyPath = path.join(root, 'src', 'app', 'messy.cljf');
  require('fs').writeFileSync(messyPath, '(ns app.messy)\n\n(defn f [x]\n(+ x\n1))\n');
  const messy = await vscode.workspace.openTextDocument(vscode.Uri.file(messyPath));
  await vscode.window.showTextDocument(messy);
  const edits = await vscode.commands.executeCommand('vscode.executeFormatDocumentProvider', messy.uri, { tabSize: 2, insertSpaces: true });
  // VS Code hands the server's one edit back as minimal edits: apply them
  const applied = (edits || []).slice().sort((a, b) => b.range.start.compareTo(a.range.start))
    .reduce((t, e) => t.slice(0, messy.offsetAt(e.range.start)) + e.newText + t.slice(messy.offsetAt(e.range.end)), messy.getText());
  ok('format document comes back from cljfmt', applied, '(ns app.messy)\n\n(defn f [x]\n  (+ x\n     1))\n');
  require('fs').unlinkSync(messyPath);
  // the first editor is gone with its tab: show the file again
  const editor2 = await vscode.window.showTextDocument(doc);

  // the REPL
  await vscode.commands.executeCommand('clotran.jackIn');
  await until('the REPL to connect', async () => ext.exports.testState().connected, 120);
  ok('jack-in connects', ext.exports.testState().connected, true);
  await vscode.commands.executeCommand('clotran.loadFile');
  await until('the file to load', async () => { const s = ext.exports.testState(); return s.last ? true : null; }, 60).catch(() => null);
  // a form appended, evaluated at the cursor
  await editor2.edit((b) => b.insert(new vscode.Position(doc.lineCount, 0), '\n(greet "vs")\n'));
  const line = doc.lineCount - 2;
  editor2.selection = new vscode.Selection(line, 3, line, 3);
  await vscode.commands.executeCommand('clotran.evalForm');
  const last = await until('the evaluation', async () => {
    const s = ext.exports.testState();
    return s.last && s.last.value && s.last.value.length && s.last.value[0].includes('hello') ? s.last : null;
  }, 60);
  ok('Cmd+Enter evaluates the form at the cursor in the file\'s namespace', last.value, ['"hello VS 4"']);
  await vscode.commands.executeCommand('clotran.quit');
  ok('quit disconnects', ext.exports.testState().connected, false);
  await vscode.commands.executeCommand('workbench.action.revertAndCloseActiveEditor');

  // the debugger: a breakpoint in the package's Fortran, the fixture's debug.cljf
  // run under clotran dap, the stop and its stack read back through the session
  const f90 = vscode.Uri.file(path.join(root, 'src', 'mylib', 'mylib.f90'));
  vscode.debug.addBreakpoints([new vscode.SourceBreakpoint(new vscode.Location(f90, new vscode.Position(7, 0)))]);
  // a tracker sees every DAP message of the session: the stop, and its thread
  const seen = [];
  vscode.debug.registerDebugAdapterTrackerFactory('clotran', { createDebugAdapterTracker: () => ({
    onDidSendMessage: (m) => seen.push(m), onWillReceiveMessage: (m) => seen.push(m) }) });
  const started = await vscode.debug.startDebugging(vscode.workspace.workspaceFolders[0],
    { type: 'clotran', request: 'launch', name: 'e2e', program: 'debug.cljf', cwd: root });
  ok('a clotran debug session starts', started, true);
  const session = vscode.debug.activeDebugSession;
  const stopped = await until('the debuggee to stop', async () => seen.find((m) => m.type === 'event' && m.event === 'stopped') || null, 120)
    .catch((e) => { console.log('DAP messages seen: ' + seen.map((m) => m.type === 'event' ? 'event:' + m.event : m.type + ':' + m.command).join(' ')); throw e; });
  const tid = stopped.body.threadId;
  const st = await session.customRequest('stackTrace', { threadId: tid, levels: 3 });
  ok('and stops at the breakpoint in the Fortran subroutine, with its source line',
     [st.stackFrames[0].name, st.stackFrames[0].line, path.basename(st.stackFrames[0].source.path)], ['__mylib_MOD_scale', 8, 'mylib.f90']);
  await vscode.debug.stopDebugging(session);
  vscode.debug.removeBreakpoints(vscode.debug.breakpoints);
  ok('and the session ends', await until('the session to end', async () => !vscode.debug.activeDebugSession, 30), true);

  console.log(checks.every((c) => c) ? 'VSCODE E2E PASSED' : 'VSCODE E2E FAILED');
  if (!checks.every((c) => c)) throw new Error('e2e: ' + checks.filter((c) => !c).length + ' checks failed');
};
