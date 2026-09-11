(ns tanker.sim
  "Demo driver -- `clojure -M:dev:run`. Walks a clean vessel-shipment
  through intake -> bill-of-lading safety assessment -> voyage dispatch
  (escalate/approve/commit) -> discharge settlement (escalate/approve/
  commit), then shows HARD-hold scenarios: a jurisdiction with no
  spec-basis, an invalid IMO number, an unverified bill of lading, a
  cargo-grade mismatch, an overloaded displacement, an inert-gas O2
  above the SOLAS 8 vol% ceiling (HSE-CRITICAL, at BOTH dispatch and
  discharge), an unconfirmed ship-shore bonding, a double dispatch, and
  a double discharge.

  Like every sibling actor's new checks, this actor's marine-cargo
  checks (`imo-number-valid?`, `inert-gas-o2-excessive?`, plus the
  direct bl-verified / cargo-grade / displacement / bonding entity
  checks) are evaluated directly at `:voyage/dispatch` (and the
  HSE-CRITICAL inert-gas O2 at `:discharge/settle` too) rather than via
  a separate screening op -- a real dispatch decision validates the IMO
  number, the B/L, the cargo grade, the displacement, the tank
  atmosphere and the bonding at the point of the act itself, not as a
  discrete pre-screening ceremony. Each check is still exercised
  directly and independently below, one vessel-shipment per HARD-hold
  scenario, following the SAME 'exercise the failure mode directly,
  never only via a happy-path actuation' discipline `parksafety`'s
  ADR-2607071922 Decision 5 and every sibling since establish."
  (:require [langgraph.graph :as g]
            [tanker.store :as store]
            [tanker.operation :as op]))

(def operator {:actor-id "op-1" :actor-role :tanker-operator :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn- assess!
  "Walks `subject` through bill-of-lading verify -> approve, leaving a
  bl-assessment on file. Uses distinct thread-ids per call site by
  suffixing `tid-prefix`."
  [actor tid-prefix subject]
  (exec-op actor (str tid-prefix "-assess") {:op :bill-of-lading/verify :subject subject} operator)
  (approve! actor (str tid-prefix "-assess")))

(defn -main [& _]
  (let [db (store/seed-db)
        actor (op/build db)]
    (println "== vessel/intake vs-1 (JPN, clean) ==")
    (println (exec-op actor "t1" {:op :vessel/intake :subject "vs-1"
                                  :patch {:id "vs-1" :bill-of-lading-no "BL-AK-0001"}} operator))

    (println "== bill-of-lading/verify vs-1 (escalates -- human approves) ==")
    (println (exec-op actor "t2" {:op :bill-of-lading/verify :subject "vs-1"} operator))
    (println (approve! actor "t2"))

    (println "== voyage/dispatch vs-1 (always escalates -- :voyage/dispatch) ==")
    (let [r (exec-op actor "t3" {:op :voyage/dispatch :subject "vs-1"} operator)]
      (println r)
      (println "-- human tanker operator / master approves --")
      (println (approve! actor "t3")))

    (println "== discharge/settle vs-1 (always escalates -- :discharge/settle) ==")
    (let [r (exec-op actor "t4" {:op :discharge/settle :subject "vs-1"} operator)]
      (println r)
      (println "-- human tanker operator / master approves --")
      (println (approve! actor "t4")))

    (println "== bill-of-lading/verify vs-2 (no spec-basis -> HARD hold) ==")
    (println (exec-op actor "t5" {:op :bill-of-lading/verify :subject "vs-2"} operator))

    (println "== voyage/dispatch vs-3 (invalid IMO number -> HARD hold) ==")
    (assess! actor "t6pre" "vs-3")
    (println (exec-op actor "t6" {:op :voyage/dispatch :subject "vs-3"} operator))

    (println "== voyage/dispatch vs-4 (bill-of-lading unverified -> HARD hold) ==")
    (assess! actor "t7pre" "vs-4")
    (println (exec-op actor "t7" {:op :voyage/dispatch :subject "vs-4"} operator))

    (println "== voyage/dispatch vs-5 (cargo-grade mismatch -> HARD hold) ==")
    (assess! actor "t8pre" "vs-5")
    (println (exec-op actor "t8" {:op :voyage/dispatch :subject "vs-5"} operator))

    (println "== voyage/dispatch vs-6 (vessel overload -> HARD hold) ==")
    (assess! actor "t9pre" "vs-6")
    (println (exec-op actor "t9" {:op :voyage/dispatch :subject "vs-6"} operator))

    (println "== voyage/dispatch vs-7 (inert-gas O2 excessive -> HARD hold, HSE-CRITICAL) ==")
    (assess! actor "t10pre" "vs-7")
    (println (exec-op actor "t10" {:op :voyage/dispatch :subject "vs-7"} operator))

    (println "== discharge/settle vs-7 (inert-gas O2 excessive at discharge too -> HARD hold, HSE-CRITICAL) ==")
    (assess! actor "t11pre" "vs-7")
    (println (exec-op actor "t11" {:op :discharge/settle :subject "vs-7"} operator))

    (println "== voyage/dispatch vs-8 (bonding-grounding unconfirmed -> HARD hold) ==")
    (assess! actor "t12pre" "vs-8")
    (println (exec-op actor "t12" {:op :voyage/dispatch :subject "vs-8"} operator))

    (println "== voyage/dispatch vs-1 AGAIN (double-dispatch -> HARD hold) ==")
    (println (exec-op actor "t13" {:op :voyage/dispatch :subject "vs-1"} operator))

    (println "== discharge/settle vs-1 AGAIN (double-discharge -> HARD hold) ==")
    (println (exec-op actor "t14" {:op :discharge/settle :subject "vs-1"} operator))

    (println "== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "== draft voyage-dispatch records ==")
    (doseq [r (store/voyage-history db)] (println r))

    (println "== draft discharge-settlement records ==")
    (doseq [r (store/discharge-history db)] (println r))))
