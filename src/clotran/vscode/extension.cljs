(ns clotran.vscode.extension
  "The clotran extension for VS Code, in ClojureScript (clotran's PLAN
  step 36): standalone, with no other extension to install.

  - The language server: `clotran lsp` (clotran-ide) started for the
    workspace through vscode-languageclient, so definitions into Clojure
    and Fortran, hover, symbols, completion, diagnostics and formatting
    (cljfmt) come as VS Code expects them.
  - A REPL: `clotran: Start a REPL` runs `clotran nrepl` in the
    workspace, connects with the small nREPL client in
    clotran.vscode.nrepl, and then Cmd+Enter evaluates the form at the
    cursor -- the server's clotran/formAtPoint says which -- with the
    result inline after it; the selection, when there is one; load the
    file; load and compile it to native code. What is printed goes to
    the `clotran` output channel."
  (:require [clotran.vscode.nrepl :as nrepl]
            [clojure.string :as str]))

(def ^:private vscode (js/require "vscode"))
(def ^:private child-process (js/require "child_process"))
(def ^:private lc (js/require "vscode-languageclient/node"))

(defonce ^:private state (atom {:client nil :conn nil :server nil :output nil :decoration nil}))

(defn- config [k] (.get (.getConfiguration (.-workspace vscode) "clotran") k))
(defn- program [] (or (config "program") "clotran"))

(defn- output []
  (or (:output @state)
      (let [o (.createOutputChannel (.-window vscode) "clotran")]
        (swap! state assoc :output o)
        o)))

(defn- say [& xs] (.appendLine (output) (str/join " " xs)))
(defn- info [& xs] (.showInformationMessage (.-window vscode) (str/join " " xs)))
(defn- warn [& xs] (.showWarningMessage (.-window vscode) (str/join " " xs)))

(defn- workspace-root []
  (some-> (.-workspaceFolders (.-workspace vscode)) first .-uri .-fsPath))

;;; ---- the language server -------------------------------------------------------------

(defn- start-language-client! [context]
  (when (config "languageServer")
    (let [LanguageClient (.-LanguageClient lc)
          server-options #js {:command (program) :args #js ["lsp"]
                              :options #js {:cwd (or (workspace-root) ".")}}
          client-options #js {:documentSelector #js [#js {:scheme "file" :language "clojure"}
                                                     #js {:scheme "file" :pattern "**/*.{f90,f95,f03,f08,f,for,F90}"}]
                              :outputChannelName "clotran language server"}
          client (LanguageClient. "clotran" "clotran language server" server-options client-options)]
      (swap! state assoc :client client)
      (-> (.start client)
          (.catch (fn [e] (warn "clotran lsp did not start:" (.-message e) "-- is clotran on the PATH? (the clotran.program setting)"))))
      (.push (.-subscriptions context) #js {:dispose #(.stop client)}))))

(defn- lsp-request
  "A request to the language server: a promise of its result, or of nil
  when there is no server."
  [method params]
  (if-let [client (:client @state)]
    (.sendRequest client method (clj->js params))
    (js/Promise.resolve nil)))

;;; ---- the REPL -------------------------------------------------------------------

(defn- connected? [] (nrepl/connected? (:conn @state)))

(defn- jack-in []
  (if (connected?)
    (info "clotran: the REPL is connected (clotran: Quit the REPL stops it)")
    (let [root (or (workspace-root) ".")
          proc (.spawn child-process (program) #js ["nrepl"] #js {:cwd root})
          started (atom false)]
      (swap! state assoc :server proc)
      (say ";; starting" (program) "nrepl in" root)
      (.show (output) true)
      (.on (.-stdout proc) "data"
           (fn [chunk]
             (let [s (str chunk)]
               (.append (output) s)
               (when-let [[_ port] (and (not @started) (re-find #"nREPL server started on port (\d+)" s))]
                 (reset! started true)
                 (-> (nrepl/connect (js/parseInt port 10))
                     (.then (fn [conn] (swap! state assoc :conn conn) (say ";; connected to nREPL on port" port) (info "clotran: REPL connected")))
                     (.catch (fn [e] (warn "clotran: could not connect:" (.-message e)))))))))
      (.on (.-stderr proc) "data" #(.append (output) (str %)))
      (.on proc "error" (fn [e] (warn "clotran nrepl did not start:" (.-message e) "-- is clotran on the PATH? (the clotran.program setting)")))
      (.on proc "exit" (fn [code] (say ";; clotran nrepl exited" code) (nrepl/close (:conn @state)) (swap! state assoc :conn nil :server nil))))))

(defn- quit []
  (nrepl/close (:conn @state))
  (when-let [p (:server @state)] (.kill p))
  (swap! state assoc :conn nil :server nil)
  (say ";; disconnected"))

(defn- need-conn []
  (if (connected?)
    (:conn @state)
    (do (warn "clotran: no REPL -- run clotran: Start a REPL (Ctrl+Alt+C J)") nil)))

;;; ---- results, inline -------------------------------------------------------------------

(defn- decoration-type []
  (or (:decoration @state)
      (let [t (.createTextEditorDecorationType
               (.-window vscode)
               #js {:after #js {:margin "0 0 0 1.5em" :color (new (.-ThemeColor vscode) "editorCodeLens.foreground")}})]
        (swap! state assoc :decoration t)
        t)))

(defn- show-inline! [editor line text]
  (let [range (new (.-Range vscode) line 0 line 0)
        full-line (.-range (.lineAt (.-document editor) line))]
    (.setDecorations editor (decoration-type)
                     #js [#js {:range (new (.-Range vscode) (.-end full-line) (.-end full-line))
                               :renderOptions #js {:after #js {:contentText text}}}])
    range))

(defn- clear-inline! []
  (when-let [e (.-activeTextEditor (.-window vscode))]
    (.setDecorations e (decoration-type) #js [])))

(defn- show-result! [editor line {:keys [value out err] :as r}]
  (swap! state assoc :last r)
  (when (seq out) (.append (output) out))
  (when (seq err) (.append (output) err))
  (let [text (cond (seq err) (str "!! " (str/trim (first (str/split-lines err))))
                   (seq value) (str "=> " (str/join " " value))
                   :else "=> (nothing)")]
    (show-inline! editor line (subs text 0 (min 200 (count text))))
    (when (seq err) (.show (output) true))))

;;; ---- evaluating -------------------------------------------------------------------

(defn- eval-in-ns!
  "code evaluated in the document's namespace -- in user, and said so,
  while the server has not loaded that namespace -- shown inline at line."
  [editor code line]
  (when-let [conn (need-conn)]
    (let [doc (.-document editor)]
      (-> (lsp-request "clotran/namespace" {:textDocument {:uri (.toString (.-uri doc))}})
          (.then (fn [r] (when r (.-ns r))))
          (.then (fn [ns] (if ns (-> (nrepl/ns-loaded? conn ns) (.then #(if % ns nil))) nil)))
          (.then (fn [ns]
                   (when-not ns (say ";; the file's namespace is not loaded (clotran: Load this file); evaluated in user"))
                   (nrepl/eval-code conn code ns)))
          (.then #(show-result! editor line %))
          (.catch (fn [e] (warn "clotran:" (.-message e))))))))

(defn- eval-form []
  (when-let [editor (.-activeTextEditor (.-window vscode))]
    (let [doc (.-document editor) pos (.-active (.-selection editor))]
      (-> (lsp-request "clotran/formAtPoint" {:textDocument {:uri (.toString (.-uri doc))}
                                              :position {:line (.-line pos) :character (.-character pos)}})
          (.then (fn [r]
                   (if (and r (.-text r))
                     (eval-in-ns! editor (.-text r) (.. r -range -end -line))
                     (warn "clotran: no form at the cursor (is the language server running?)"))))))))

(defn- eval-selection []
  (when-let [editor (.-activeTextEditor (.-window vscode))]
    (let [sel (.-selection editor)
          code (.getText (.-document editor) sel)]
      (if (str/blank? code)
        (eval-form)
        (eval-in-ns! editor code (.-line (.-end sel)))))))

(defn- load-file! []
  (when-let [editor (.-activeTextEditor (.-window vscode))]
    (when-let [conn (need-conn)]
      (let [doc (.-document editor)]
        (-> (.save doc)
            (.then #(nrepl/load-file conn (.getText doc) (.-fsPath (.-uri doc))))
            (.then (fn [{:keys [out err] :as r}]
                     (when (seq out) (.append (output) out))
                     (when (seq err) (.append (output) err) (.show (output) true))
                     (if (seq err)
                       (warn "clotran:" (str/trim (first (str/split-lines err))))
                       (info "clotran:" (last (str/split (.-fsPath (.-uri doc)) #"[/\\]")) "loaded"))
                     r))
            (.catch (fn [e] (warn "clotran:" (.-message e)))))))))

(defn- compile-file! []
  (when-let [editor (.-activeTextEditor (.-window vscode))]
    (when-let [conn (need-conn)]
      (let [doc (.-document editor)
            file (.-fsPath (.-uri doc))
            name (last (str/split file #"[/\\]"))
            form (str "(do (clojure.core/load-file " (pr-str file) ")"
                      " (let [st (clotran.loader/compile-now " (pr-str file) ")]"
                      "   [st (:log (get @@(resolve (quote clotran.loader/builds)) " (pr-str file) "))]))")]
        (-> (.save doc)
            (.then (fn [] (say ";; compiling" name "...") (nrepl/eval-code conn form nil)))
            (.then (fn [{:keys [value err out]}]
                     (when (seq out) (.append (output) out))
                     (when (seq err) (.append (output) err))
                     (let [v (last value)
                           [_ st log] (when v (re-find #"^\[:([a-z]+) (.*)\]$" v))]
                       (cond (seq err) (warn "clotran:" name "did not load:" (str/trim (first (str/split-lines err))))
                             (= st "installed") (info "clotran:" name "is compiled")
                             (= st "none") (info "clotran: nothing in" name "compiles; it runs interpreted")
                             st (warn "clotran:" name "did not compile (" st "); see" log)
                             :else (warn "clotran: no answer from compiling" name)))))
            (.catch (fn [e] (warn "clotran:" (.-message e)))))))))

;;; ---- activation -------------------------------------------------------------------

(defn- register-debugger! [context]
  ;; the `clotran` debug type (package.json): clotran dap, in the workspace
  (.push (.-subscriptions context)
         (.registerDebugAdapterDescriptorFactory
          (.-debug vscode) "clotran"
          #js {:createDebugAdapterDescriptor
               (fn [_session _executable]
                 (new (.-DebugAdapterExecutable vscode) (program) #js ["dap"] #js {:cwd (or (workspace-root) ".")}))})))

(defn activate [context]
  (start-language-client! context)
  (register-debugger! context)
  (doseq [[name f] {"clotran.jackIn" jack-in "clotran.evalForm" eval-form "clotran.evalSelection" eval-selection
                    "clotran.loadFile" load-file! "clotran.compileFile" compile-file! "clotran.quit" quit}]
    (.push (.-subscriptions context) (.registerCommand (.-commands vscode) name f)))
  (.push (.-subscriptions context) (.onDidChangeTextDocument (.-workspace vscode) (fn [_] (clear-inline!))))
  (say ";; clotran extension active")
  ;; what activate answers is the extension's exports (test/e2e reads them)
  #js {:testState test-state})

(defn deactivate []
  (quit)
  (when-let [c (:client @state)] (.stop c)))

(defn test-state
  "For test/e2e: whether the REPL is connected, and the last result shown."
  []
  #js {:connected (connected?)
       :languageClient (boolean (:client @state))
       :last (clj->js (:last @state))})
