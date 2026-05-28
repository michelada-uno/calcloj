(ns calcloj.formula
  "Formula = restricted Clojure expression. Cell refs via reader tag:
     #cell A:1   ->  current value of cell A:1

   Pipeline:
     parse    : string -> {:form <clj-form> :deps #{addr...}}
     validate : reject any symbol not on the whitelist (pre-eval sandbox)
     compile  : form   -> a Spin, via Spindel's real `spin` macro (track works).

   `#cell A:1` expands to `(deref (track (calcloj.runtime/lookup \"A:1\")))`.
   `track` must be the bare referred symbol (CPS breakpoint matches by symbol).

   Sandbox: EDN reader blocks `#=` RCE; then `validate` whitelists every
   operator symbol before we `eval`. Cell lookups, literals, and whitelisted
   math only — no Java interop, no arbitrary fns."
  (:refer-clojure :exclude [compile])
  (:require [clojure.edn :as edn]
            [clojure.walk :as walk]
            [org.replikativ.spindel.spin.cps :refer [spin]]
            [org.replikativ.spindel.effects.track :refer [track]]
            [calcloj.runtime :as rt]))

;; --- parse --------------------------------------------------------------

(def ^:private readers
  {'cell (fn [sym]
           (list 'deref (list 'track (list 'calcloj.runtime/lookup (str sym)))))})

(defn deps
  [form]
  (let [acc (volatile! #{})]
    (walk/postwalk
     (fn [x]
       (when (and (seq? x) (= 'calcloj.runtime/lookup (first x)))
         (vswap! acc conj (second x)))
       x)
     form)
    @acc))

(defn parse
  "Formula string (without leading =) -> {:form :deps}."
  [s]
  (let [form (edn/read-string {:readers readers} s)]
    {:form form :deps (deps form)}))

;; --- validate (whitelist sandbox) --------------------------------------

(def allowed-ops
  "Symbols a formula body may call. Extend deliberately."
  '#{+ - * / deref track calcloj.runtime/lookup
     min max abs mod quot rem
     = not= < > <= >= and or not if when
     sum avg count})

(defn validate!
  "Walk the form; every symbol must be whitelisted. Throws on violation."
  [form]
  (walk/postwalk
   (fn [x]
     (when (and (symbol? x) (not (contains? allowed-ops x)))
       (throw (ex-info "disallowed symbol in formula" {:symbol x})))
     x)
   form)
  form)

;; --- compile ------------------------------------------------------------

(defn compile
  "Parsed form -> Spin. Validates, then evals the real `spin` macro in THIS
   namespace (spin/track/lookup referred). Must run with *execution-context*
   bound to the sheet runtime."
  [form]
  (validate! form)
  ;; eval in THIS namespace so bare `spin`/`track`/`deref` resolve and the
  ;; CPS transformer recognizes `track` as a breakpoint.
  (binding [*ns* (find-ns 'calcloj.formula)]
    (eval (list 'spin form))))
