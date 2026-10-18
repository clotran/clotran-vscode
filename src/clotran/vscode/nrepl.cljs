(ns clotran.vscode.nrepl
  "An nREPL client, the base operations only -- clone, eval, load-file --
  so nothing on the server (clotran-nrepl) has to match this extension's
  version. A connection is a map: the socket, the session, the pending
  requests by id, and the bytes not yet decoded. Every request answers a
  promise of its replies combined: the values in order, out and err
  concatenated, the statuses."
  (:require [clotran.vscode.bencode :as b]
            [clojure.string :as str]))

(def ^:private net (js/require "net"))

(defn- combine [replies]
  {:value (vec (keep #(get % "value") replies))
   :out (apply str (keep #(get % "out") replies))
   :err (apply str (keep #(get % "err") replies))
   :ex (some #(get % "ex") replies)
   :ns (some #(get % "ns") (reverse replies))
   :new-session (some #(get % "new-session") replies)
   :status (vec (mapcat #(get % "status") replies))})

(defn- on-data [conn chunk]
  (swap! (:buf conn) #(js/Buffer.concat #js [% chunk]))
  (loop []
    (when-let [[msg next] (and (pos? (.-length @(:buf conn))) (b/decode @(:buf conn) 0))]
      (swap! (:buf conn) #(.subarray % next))
      (when-let [{:keys [replies resolve]} (get @(:pending conn) (get msg "id"))]
        (swap! replies conj msg)
        (when (some #{"done"} (get msg "status"))
          (swap! (:pending conn) dissoc (get msg "id"))
          (resolve (combine @replies))))
      (recur))))

(defn request
  "msg, a map with string keys, sent with an id and the session; a
  promise of the combined replies."
  [conn msg]
  (js/Promise.
   (fn [resolve reject]
     (if-not (:open @(:state conn))
       (reject (js/Error. "clotran: not connected"))
       (let [id (str (swap! (:next-id conn) inc))]
         (swap! (:pending conn) assoc id {:replies (atom []) :resolve resolve})
         (.write (:socket conn) (b/encode (cond-> (assoc msg "id" id)
                                            @(:session conn) (assoc "session" @(:session conn))))))))))

(defn connect
  "A promise of a connection to the nREPL server on port of this machine,
  with a session opened."
  [port]
  (js/Promise.
   (fn [resolve reject]
     (let [socket (.createConnection net port "127.0.0.1")
           conn {:socket socket :session (atom nil) :pending (atom {}) :buf (atom (js/Buffer.alloc 0))
                 :next-id (atom 0) :state (atom {:open false})}]
       (.on socket "data" #(on-data conn %))
       (.on socket "error" (fn [e] (swap! (:state conn) assoc :open false) (reject e)))
       (.on socket "close" (fn [] (swap! (:state conn) assoc :open false)))
       (.on socket "connect"
            (fn []
              (swap! (:state conn) assoc :open true)
              (-> (request conn {"op" "clone"})
                  (.then (fn [r]
                           (if-let [s (:new-session r)]
                             (do (reset! (:session conn) s) (resolve conn))
                             (reject (js/Error. "clotran: the server gave no session")))))
                  (.catch reject))))))))

(defn connected? [conn] (boolean (and conn (:open @(:state conn)))))

(defn eval-code
  "code evaluated in ns (nil: the session's); a promise of the combined reply."
  [conn code ns]
  (request conn (cond-> {"op" "eval" "code" code} ns (assoc "ns" ns))))

(defn ns-loaded?
  "Whether the server has namespace ns: evaluating in one it does not
  have fails, as clj's nREPL does, on the first symbol."
  [conn ns]
  (-> (eval-code conn (str "(some? (find-ns '" ns "))") nil)
      (.then #(= ["true"] (:value %)))))

(defn load-file
  "The file's text loaded as the file at path; a promise of the reply."
  [conn text path]
  (request conn {"op" "load-file" "file" text "file-path" path
                 "file-name" (last (str/split path #"[/\\]"))}))

(defn close [conn]
  (when conn
    (swap! (:state conn) assoc :open false)
    (.destroy (:socket conn))))
