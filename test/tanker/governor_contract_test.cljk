(ns tanker.governor-contract-test
  "The governor contract as executable tests. The single invariant
  under test:

    TankerAdvisor never dispatches a tanker voyage or settles a
    discharge the Marine Cargo Governor would reject, `:voyage/dispatch`/
    `:discharge/settle` NEVER auto-commit at any phase, `:vessel/intake`
    (no direct capital risk) MAY auto-commit when clean, the HSE-CRITICAL
    inert-gas O2 check fires UNCONDITIONALLY at BOTH dispatch and
    discharge (overridable by NO human), and every decision (commit OR
    hold) leaves exactly one ledger fact."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [tanker.store :as store]
            [tanker.operation :as op]))

(defn- fresh []
  (let [db (store/seed-db)]
    [db (op/build db)]))

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

(deftest clean-intake-auto-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t1"
                  {:op :vessel/intake :subject "vs-1"
                   :patch {:id "vs-1" :bill-of-lading-no "BL-AK-0001"}} operator)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= "BL-AK-0001" (:bill-of-lading-no (store/vessel-shipment db "vs-1"))) "SSoT actually updated")
    (is (= 1 (count (store/ledger db))))))

(deftest bill-of-lading-verify-always-needs-approval
  (testing "bill-of-lading verify is never in any phase's :auto set -- always human approval, even when clean"
    (let [[db actor] (fresh)
          res (exec-op actor "t2" {:op :bill-of-lading/verify :subject "vs-1"} operator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t2")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (some? (store/bl-assessment-of db "vs-1")))))))

(deftest fabricated-jurisdiction-is-held
  (testing "a bill-of-lading/verify proposal with no official spec-basis -> HOLD, never reaches a human"
    (let [[db actor] (fresh)
          res (exec-op actor "t3"
                    {:op :bill-of-lading/verify :subject "vs-2"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:no-spec-basis} (-> (store/ledger db) first :basis)))
      (is (nil? (store/bl-assessment-of db "vs-2")) "no assessment written"))))

(deftest voyage-dispatch-without-assessment-is-held
  (testing "voyage/dispatch before any bill-of-lading assessment -> HOLD (evidence incomplete)"
    (let [[db actor] (fresh)
          res (exec-op actor "t4" {:op :voyage/dispatch :subject "vs-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:evidence-incomplete} (-> (store/ledger db) first :basis))))))

(deftest imo-number-invalid-is-held-and-unoverridable
  (testing "an invalid IMO number (failed 7-digit check-digit) -> HOLD, and never reaches request-approval (the freight tracking-validity discipline applied to the SOLAS A.600(15) scheme)"
    (let [[db actor] (fresh)
          _ (assess! actor "t5pre" "vs-3")
          res (exec-op actor "t5" {:op :voyage/dispatch :subject "vs-3"} operator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:imo-number-invalid} (-> (store/ledger db) last :basis)))
      (is (empty? (store/voyage-history db))))))

(deftest bl-unverified-is-held-and-unoverridable
  (testing "an unverified bill of lading -> HOLD, and never reaches request-approval"
    (let [[db actor] (fresh)
          _ (assess! actor "t6pre" "vs-4")
          res (exec-op actor "t6" {:op :voyage/dispatch :subject "vs-4"} operator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:bl-unverified} (-> (store/ledger db) last :basis)))
      (is (empty? (store/voyage-history db))))))

(deftest cargo-grade-mismatch-is-held-and-unoverridable
  (testing "a B/L declared grade that does not match the loaded cargo grade -> HOLD, and never reaches request-approval (contamination / quality fraud)"
    (let [[db actor] (fresh)
          _ (assess! actor "t7pre" "vs-5")
          res (exec-op actor "t7" {:op :voyage/dispatch :subject "vs-5"} operator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:cargo-grade-mismatch} (-> (store/ledger db) last :basis)))
      (is (empty? (store/voyage-history db))))))

(deftest vessel-overload-is-held-and-unoverridable
  (testing "a load displacement above the vessel's safe DWT limit -> HOLD, and never reaches request-approval (the fabrication measured-ratio-vs-rated-limit discipline, applied to deadweight tonnage)"
    (let [[db actor] (fresh)
          _ (assess! actor "t8pre" "vs-6")
          res (exec-op actor "t8" {:op :voyage/dispatch :subject "vs-6"} operator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:vessel-overload} (-> (store/ledger db) last :basis)))
      (is (empty? (store/voyage-history db))))))

(deftest inert-gas-o2-excessive-at-dispatch-is-held-and-unoverridable
  (testing "a cargo-tank O2 above the SOLAS 8 vol% ceiling -> HOLD at dispatch, and never reaches request-approval -- HSE-CRITICAL, a true explosion precursor evaluated UNCONDITIONALLY, overridable by NO human"
    (let [[db actor] (fresh)
          _ (assess! actor "t9pre" "vs-7")
          res (exec-op actor "t9" {:op :voyage/dispatch :subject "vs-7"} operator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:inert-gas-o2-excessive} (-> (store/ledger db) last :basis)))
      (is (empty? (store/voyage-history db))))))

(deftest inert-gas-o2-excessive-at-discharge-is-held-and-unoverridable
  (testing "the HSE-CRITICAL inert-gas O2 check fires at discharge too -> HOLD, never reaches request-approval, overridable by NO human (SOLAS II-2/4.5.5 applies during loading, discharging AND crude washing)"
    (let [[db actor] (fresh)
          _ (assess! actor "t10pre" "vs-7")
          res (exec-op actor "t10" {:op :discharge/settle :subject "vs-7"} operator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:inert-gas-o2-excessive} (-> (store/ledger db) last :basis)))
      (is (empty? (store/discharge-history db))))))

(deftest bonding-grounding-unconfirmed-is-held-and-unoverridable
  (testing "an unconfirmed ship-shore bonding/grounding -> HOLD, and never reaches request-approval (static-electricity ignition during cargo handling)"
    (let [[db actor] (fresh)
          _ (assess! actor "t11pre" "vs-8")
          res (exec-op actor "t11" {:op :voyage/dispatch :subject "vs-8"} operator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:bonding-grounding-unconfirmed} (-> (store/ledger db) last :basis)))
      (is (empty? (store/voyage-history db))))))

(deftest voyage-dispatch-always-escalates-then-human-decides
  (testing "a clean, fully-assessed, valid-IMO, B/L-verified, grade-matched, in-DWT, inerted, bonded shipment still ALWAYS interrupts for human approval -- :voyage/dispatch is never auto"
    (let [[db actor] (fresh)
          _ (assess! actor "t12pre" "vs-1")
          r1 (exec-op actor "t12" {:op :voyage/dispatch :subject "vs-1"} operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
      (testing "approve -> commit, voyage record drafted"
        (let [r2 (approve! actor "t12")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (true? (:dispatched? (store/vessel-shipment db "vs-1"))))
          (is (= 1 (count (store/voyage-history db))) "one draft voyage record"))))))

(deftest discharge-settle-always-escalates-then-human-decides
  (testing "a clean, fully-assessed, already-dispatched shipment still ALWAYS interrupts for human approval -- :discharge/settle is never auto"
    (let [[db actor] (fresh)
          _ (assess! actor "t13pre" "vs-1")
          _ (exec-op actor "t13dispatch" {:op :voyage/dispatch :subject "vs-1"} operator)
          _ (approve! actor "t13dispatch")
          r1 (exec-op actor "t13" {:op :discharge/settle :subject "vs-1"} operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
      (testing "approve -> commit, discharge record drafted"
        (let [r2 (approve! actor "t13")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (true? (:discharged? (store/vessel-shipment db "vs-1"))))
          (is (= 1 (count (store/discharge-history db))) "one draft discharge record"))))))

(deftest voyage-dispatch-double-dispatch-is-held
  (testing "dispatching the same vessel-shipment twice -> HOLD on the second attempt"
    (let [[db actor] (fresh)
          _ (assess! actor "t14pre" "vs-1")
          _ (exec-op actor "t14a" {:op :voyage/dispatch :subject "vs-1"} operator)
          _ (approve! actor "t14a")
          res (exec-op actor "t14" {:op :voyage/dispatch :subject "vs-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:already-dispatched} (-> (store/ledger db) last :basis)))
      (is (= 1 (count (store/voyage-history db))) "still only the one earlier dispatch"))))

(deftest discharge-settle-double-discharge-is-held
  (testing "settling the same vessel-shipment's discharge twice -> HOLD on the second attempt"
    (let [[db actor] (fresh)
          _ (assess! actor "t15pre" "vs-1")
          _ (exec-op actor "t15dispatch" {:op :voyage/dispatch :subject "vs-1"} operator)
          _ (approve! actor "t15dispatch")
          _ (exec-op actor "t15a" {:op :discharge/settle :subject "vs-1"} operator)
          _ (approve! actor "t15a")
          res (exec-op actor "t15" {:op :discharge/settle :subject "vs-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:already-discharged} (-> (store/ledger db) last :basis)))
      (is (= 1 (count (store/discharge-history db))) "still only the one earlier discharge"))))

(deftest every-decision-leaves-one-ledger-fact
  (testing "write-only-through-ledger: N operations -> N ledger facts"
    (let [[db actor] (fresh)]
      (exec-op actor "a" {:op :vessel/intake :subject "vs-1"
                          :patch {:id "vs-1" :bill-of-lading-no "BL-AK-0001"}} operator)
      (exec-op actor "b" {:op :bill-of-lading/verify :subject "vs-2"} operator)
      (is (= 2 (count (store/ledger db)))
          "one commit + one hold, both recorded"))))
