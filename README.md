# clotran-vscode

The [clotran](https://github.com/clotran/clotran) extension for VS Code
-- Clojure hosted on Fortran -- written in ClojureScript, standalone:
nothing else to install.

- `.cljf` files are Clojure to VS Code: highlighting and brackets from
  its own Clojure grammar.
- The language server, `clotran lsp`
  ([clotran-ide](https://github.com/clotran/clotran-ide)), started for
  the workspace: go to definition into Clojure and into the Fortran of a
  package, hover documentation, symbols, completion, errors as you type
  (the reader's, and the Fortran compiler's on save), and formatting by
  [cljfmt on clotran](https://github.com/clotran/cljfmt) -- the file,
  a selection, or as you type -- with no REPL running.
- A REPL: **clotran: Start a REPL** (`Ctrl+Alt+C J`) runs `clotran nrepl`
  in the workspace and connects to it with the small nREPL client in
  this extension (base operations only, so nothing on the server has to
  match the extension's version). Then `Cmd+Enter` (`Ctrl+Enter`)
  evaluates the form at the cursor -- the server says which -- with the
  result shown inline after it, or the selection when there is one;
  `Ctrl+Alt+C L` loads the file; `Ctrl+Alt+C K` loads it and compiles it
  to native code, waiting for the build and saying how it went. What is
  printed goes to the `clotran` output channel.

With no clotran on the machine, the extension offers to install it:
**Install clotran** (or **clotran: Install clotran** from the command
palette) runs clotran's own install script -- the release for this
machine, checked against its SHA256SUMS, into `~/.local` (on Windows
`%LOCALAPPDATA%\Programs\clotran`), where a terminal finds it too -- and
starts the language server. A Fortran compiler (gfortran or flang) is
needed for packages; lldb-dap for the debugger.

Settings: `clotran.program`, the command (`clotran` by default; a full
path when VS Code's PATH has none), and `clotran.languageServer`.

The extension activates in a workspace that holds a `.cljf` file or a
`package.edn`, so a plain Clojure project is left to its own tools.

## Building

ClojureScript, compiled by shadow-cljs to `out/extension.js`:

    npm install
    npm run build        # shadow-cljs release ext
    npm run package      # a .vsix, to install with: code --install-extension clotran-0.1.0.vsix

For development, `npm run watch` and VS Code's *Run Extension* (F5) on
this folder, or `code --extensionDevelopmentPath=$PWD`.

## Tests

    test/run.sh          # npm test: the extension's own nREPL client and
                         # bencode under Node, against a real `clotran nrepl`
                         # in a scratch project (CLOTRAN names the command)

## The pieces

- `clotran.vscode.bencode` -- bencode over Node Buffers, strings by
  their byte length.
- `clotran.vscode.nrepl` -- connect, a session, eval, load-file; each
  request a promise of its replies combined.
- `clotran.vscode.extension` -- `activate`: the language client, the
  commands, the inline results.

## Debugging

The extension contributes the `clotran` debug type: *Run and Debug*,
**clotran: run this file**, or a `launch.json` entry

```json
{"type": "clotran", "request": "launch", "name": "clotran: main",
 "program": "${workspaceFolder}/src/app/main.cljf", "cwd": "${workspaceFolder}"}
```

runs the file under `clotran dap`
([clotran-ide](https://github.com/clotran/clotran-ide)), which drives
lldb-dap underneath: a breakpoint set in a package's Fortran source
stops there with the call stack. Stage one: Fortran locals and Clojure
frames shown as Clojure are still to come.
