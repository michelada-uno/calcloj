(ns calcloj.sheet
  "Step 1 — cell registry over a Spindel execution context.

   Two layers:
   - reactive : registry atom {addr -> SignalRef|Spin}, in ctx metadata so
                compiled formulas resolve cells via calcloj.runtime/lookup.
   - document : meta atom {addr -> {:raw :kind :deps}} — source of truth for
                raw input / formatting / styles / serialization (later steps).

   Cell kinds:
   - :literal  -> SignalRef holding a number or string (user-editable)
   - :formula  -> Spin compiled from an `=`-prefixed expression
   - blank     -> absent from both maps (sparse)

   NOTE (Step 4): formula refs currently `track` their targets, and track only
   handles SignalRef. So formulas may reference literal cells today; formula->
   formula needs `await`-based refs — deferred."
  (:require [clojure.string :as str]
            [calcloj.formula :as formula]
            [org.replikativ.spindel.signal :as sig]
            [org.replikativ.spindel.spin.core :as spin-core]
            [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.engine.context :as ctx]
            [org.replikativ.spindel.engine.impl.simple :as simple]))

(defn create-sheet []
  (let [registry (atom {})
        meta     (atom {})
        rt       (ctx/create-execution-context {:metadata {:registry registry}})]
    {:rt rt :registry registry :meta meta}))

(defn- signal? [x] (instance? org.replikativ.spindel.signal.SignalRef x))
(defn- spin?   [x] (instance? org.replikativ.spindel.spin.core.Spin x))

(defn- classify [raw]
  (let [t (some-> raw str/trim)]
    (cond (or (nil? t) (= "" t))      :blank
          (str/starts-with? t "=")    :formula
          :else                       :literal)))

(defn- parse-literal
  "Number if it parses, else the trimmed string."
  [raw]
  (let [t (str/trim raw)]
    (cond
      (re-matches #"[-+]?\d+" t)               (Long/parseLong t)
      (re-matches #"[-+]?\d*\.\d+([eE]\d+)?" t) (Double/parseDouble t)
      :else                                    t)))

(defn set-cell!
  "Set cell `addr` from raw user input. Reclassifies (literal/formula/blank),
   cleaning up any prior spin. Returns the sheet."
  [{:keys [rt registry meta] :as sheet} addr raw]
  (binding [ec/*execution-context* rt]
    ;; tear down prior spin (formula) if replacing
    (when-let [old (get @registry addr)]
      (when (spin? old) (spin-core/cleanup-spin! old)))
    (case (classify raw)
      :blank
      (do (swap! registry dissoc addr)
          (swap! meta dissoc addr))

      :literal
      (let [v   (parse-literal raw)
            cur (get @registry addr)]
        (if (signal? cur)
          (reset! cur v)                       ; reuse stable signal -> propagates
          (let [s (sig/->SignalRef addr v)]
            (sig/ensure-signal-initialized! s)
            (swap! registry assoc addr s)))
        (swap! meta assoc addr {:raw raw :kind :literal}))

      :formula
      (let [{:keys [form deps]} (formula/parse (subs (str/trim raw) 1))
            sp (formula/compile form)]
        (swap! registry assoc addr sp)
        (swap! meta assoc addr {:raw raw :kind :formula :deps deps}))))
  sheet)

(defn settle!
  "Wait for the executor to finish propagating (drain barrier — waits, does
   not pump). Call before reading a viewport for a consistent snapshot."
  [{:keys [rt]}]
  (simple/await-drain-complete! rt :timeout-ms 5000))

(defn value
  "Current computed value of `addr`, or nil if blank. Errors are returned as
   {:error msg} so the renderer can show #ERR."
  [{:keys [rt registry]} addr]
  (when-let [ref (get @registry addr)]
    (binding [ec/*execution-context* rt]
      (try @ref (catch Exception e {:error (.getMessage e)})))))

(defn raw   [{:keys [meta]} addr] (get-in @meta [addr :raw]))
(defn kind  [{:keys [meta]} addr] (get-in @meta [addr :kind]))
(defn cells [{:keys [meta]}] (keys @meta))
