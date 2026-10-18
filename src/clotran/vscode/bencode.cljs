(ns clotran.vscode.bencode
  "bencode, as nREPL carries it, over Node Buffers: integers i42e, strings
  by their BYTE length 3:foo, lists l...e, dictionaries d...e with string
  keys. A string is decoded from UTF-8 only once its bytes are all there."
  (:require [clojure.string :as str]))

(defn- buf [s] (js/Buffer.from s "utf8"))

(defn encode
  "x -- a string, an integer, a sequential, or a map with string keys --
  as a Buffer."
  [x]
  (cond
    (string? x) (let [b (buf x)] (js/Buffer.concat #js [(buf (str (.-length b) ":")) b]))
    (integer? x) (buf (str "i" x "e"))
    (map? x) (js/Buffer.concat
              (into-array (concat [(buf "d")]
                                  (mapcat (fn [[k v]] [(encode (name k)) (encode v)]) (sort-by (comp name key) x))
                                  [(buf "e")])))
    (sequential? x) (js/Buffer.concat (into-array (concat [(buf "l")] (map encode x) [(buf "e")])))
    (nil? x) (buf "0:")
    :else (throw (ex-info (str "bencode: no encoding for " (pr-str x)) {:value x}))))

(defn decode
  "The value in Buffer b at pos as [value next-pos], or nil when it is
  not all there yet. Strings come back decoded from UTF-8, dictionaries
  as maps with string keys, lists as vectors."
  [b pos]
  (when (< pos (.-length b))
    (let [c (aget b pos)]
      (cond
        (= c 105)                       ; i
        (let [e (.indexOf b "e" pos "utf8")]
          (when (pos? e) [(js/parseInt (.toString b "utf8" (inc pos) e) 10) (inc e)]))
        (and (>= c 48) (<= c 57))       ; a digit: a string's length
        (let [colon (.indexOf b ":" pos "utf8")]
          (when (pos? colon)
            (let [n (js/parseInt (.toString b "utf8" pos colon) 10)
                  end (+ colon 1 n)]
              (when (<= end (.-length b))
                [(.toString b "utf8" (inc colon) end) end]))))
        (or (= c 108) (= c 100))        ; l d
        (loop [p (inc pos) items []]
          (cond (>= p (.-length b)) nil
                (= (aget b p) 101) [(if (= c 108) items (apply hash-map items)) (inc p)]
                :else (when-let [[v q] (decode b p)] (recur q (conj items v)))))
        :else (throw (ex-info (str "bencode: bad byte " c " at " pos) {:at pos}))))))
