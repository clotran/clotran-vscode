(ns clotran.vscode.nrepl-test
  "The extension's own nREPL client against a real `clotran nrepl`,
  started in a scratch project under Node (no VS Code): bencode, a
  session, eval, a namespace not yet loaded, load-file with its output.
  CLOTRAN names the clotran command (default: ../clotran/clotran)."
  (:require [cljs.test :refer [deftest is async use-fixtures]]
            [clotran.vscode.bencode :as b]
            [clotran.vscode.nrepl :as nrepl]
            [clojure.string :as str]))

(def fs (js/require "fs"))
(def os (js/require "os"))
(def path (js/require "path"))
(def child-process (js/require "child_process"))

(deftest bencode-round-trip
  (is (= "d4:code2:λ1:lli1e1:ae2:op4:evale" (.toString (b/encode {"op" "eval" "code" "λ" "l" [1 "a"]}) "utf8")))
  (let [[v n] (b/decode (b/encode {"a" 1 "b" ["x" "é"]}) 0)]
    (is (= {"a" 1 "b" ["x" "é"]} v))
    (is (= n (.-length (b/encode {"a" 1 "b" ["x" "é"]})))))
  (is (nil? (b/decode (js/Buffer.from "d1:a" "utf8") 0))))

(deftest against-clotran-nrepl
  (async done
    (let [clotran (or (.-CLOTRAN (.-env js/process)) (.resolve path (js/process.cwd) "../clotran/clotran"))
          root (.mkdtempSync fs (.join path (.tmpdir os) "clotran-vscode-"))
          src (.join path root "src" "app")]
      (.mkdirSync fs src #js {:recursive true})
      (.writeFileSync fs (.join path root "deps.edn") "{:paths [\"src\"]}")
      (.writeFileSync fs (.join path src "main.cljf") "(ns app.main)\n(defn twice [x] (* 2 x))\n(println \"loaded é\")\n")
      (let [proc (.spawn child-process clotran #js ["nrepl"] #js {:cwd root})
            finish (fn [] (.kill proc) (.rmSync fs root #js {:recursive true :force true}) (done))
            started (atom false)]
        (.on (.-stderr proc) "data" #(js/console.error (str %)))
        (.on (.-stdout proc) "data"
             (fn [chunk]
               (when-let [[_ port] (and (not @started) (re-find #"nREPL server started on port (\d+)" (str chunk)))]
                 (reset! started true)
                 (-> (nrepl/connect (js/parseInt port 10))
                     (.then (fn [conn]
                              (is (nrepl/connected? conn))
                              (-> (nrepl/eval-code conn "(+ 1 2)" nil)
                                  (.then (fn [r] (is (= ["3"] (:value r))) (is (some #{"done"} (:status r)))))
                                  (.then #(nrepl/ns-loaded? conn "app.main"))
                                  (.then (fn [loaded?] (is (false? loaded?))))
                                  (.then #(nrepl/load-file conn (.toString (.readFileSync fs (.join path src "main.cljf"))) (.join path src "main.cljf")))
                                  (.then (fn [r] (is (= "loaded é\n" (:out r))) (is (= "" (:err r)))))
                                  (.then #(nrepl/ns-loaded? conn "app.main"))
                                  (.then (fn [loaded?] (is (true? loaded?))))
                                  (.then #(nrepl/eval-code conn "(twice 21)" "app.main"))
                                  (.then (fn [r] (is (= ["42"] (:value r)))))
                                  (.then #(nrepl/eval-code conn "(/ 1 0)" nil))
                                  (.then (fn [r] (is (str/includes? (:err r) "Divide")) (is (empty? (:value r)))))
                                  (.then (fn [] (nrepl/close conn) (is (not (nrepl/connected? conn))) (finish)))
                                  (.catch (fn [e] (is false (str "error: " (.-message e))) (finish))))))
                     (.catch (fn [e] (is false (str "connect: " (.-message e))) (finish)))))))
        (js/setTimeout (fn [] (when-not @started (is false "clotran nrepl did not start in 90 s") (finish))) 90000)))))
