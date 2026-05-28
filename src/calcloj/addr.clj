(ns calcloj.addr
  "Spreadsheet addressing. Address = COL \":\" ROW, e.g. \"A:1\", \"AAB:1234\".
   COL = bijective base-26 letters (A=1, Z=26, AA=27, ...). ROW = 1-based int.

   Two id spaces:
   - address string \"AAB:1234\"  (canonical, used as signal/spin id, user-facing)
   - 0-based [col-idx row-idx]    (grid geometry / viewport math)")

(defn col->idx
  "Column letters -> 0-based index. \"A\"->0, \"Z\"->25, \"AA\"->26."
  [^String col]
  (dec
   (reduce (fn [acc ch]
             (+ (* acc 26) (- (int (Character/toUpperCase ch)) (int \A) -1)))
           0 col)))

(defn idx->col
  "0-based index -> column letters. 0->\"A\", 25->\"Z\", 26->\"AA\"."
  [idx]
  (loop [n (inc idx) out ""]
    (if (pos? n)
      (let [r (mod (dec n) 26)]
        (recur (quot (dec n) 26) (str (char (+ (int \A) r)) out)))
      out)))

(defn parse
  "\"AAB:1234\" -> {:col \"AAB\" :row 1234 :ci <0-based> :ri <0-based>}."
  [addr]
  (let [[col row] (clojure.string/split addr #":")
        row-n (Long/parseLong row)]
    {:col col :row row-n :ci (col->idx col) :ri (dec row-n)}))

(defn make
  "0-based col/row indices -> canonical address string."
  [ci ri]
  (str (idx->col ci) ":" (inc ri)))

(defn valid?
  [addr]
  (boolean (re-matches #"[A-Za-z]+:[0-9]+" (str addr))))
