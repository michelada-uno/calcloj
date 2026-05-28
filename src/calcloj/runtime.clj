(ns calcloj.runtime
  "Runtime support referenced by compiled formula bodies.

   `lookup` resolves a cell address to its SignalRef/Spin via the CURRENT
   execution context's metadata (not a dynamic binding) — so it works on the
   executor threads where spins recompute, and is naturally per-sheet/tenant."
  (:require [org.replikativ.spindel.engine.core :as ec]
            [org.replikativ.spindel.engine.context :as ctx]))

(defn registry
  "The {addr -> ref} atom for the current execution context."
  []
  (-> (ec/current-execution-context) ctx/get-metadata :registry))

(defn lookup
  "Address -> SignalRef|Spin for the current sheet. Throws if absent."
  [addr]
  (let [reg (registry)]
    (or (get @reg addr)
        (throw (ex-info "unknown cell" {:addr addr})))))
