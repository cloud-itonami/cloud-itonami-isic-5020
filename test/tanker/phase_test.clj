(ns tanker.phase-test
  "The phase table as executable tests. The invariant this repo cannot
  regress on: `:voyage/dispatch`/`:discharge/settle` must NEVER be a
  member of any phase's `:auto` set."
  (:require [clojure.test :refer [deftest is testing]]
            [tanker.phase :as phase]))

(deftest voyage-dispatch-never-auto-at-any-phase
  (testing "structural invariant: no phase, now or in any future entry, auto-commits a real tanker voyage dispatch"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :voyage/dispatch))
          (str "phase " n " must not auto-commit :voyage/dispatch")))))

(deftest discharge-settle-never-auto-at-any-phase
  (testing "structural invariant: no phase, now or in any future entry, auto-commits a real cargo discharge settlement"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :discharge/settle))
          (str "phase " n " must not auto-commit :discharge/settle")))))

(deftest phase-0-is-fully-read-only
  (is (empty? (:writes (get phase/phases 0)))))

(deftest phase-3-auto-commits-only-no-capital-risk-ops
  (testing ":vessel/intake carries no direct capital risk -- auto-eligible; it is the ONLY auto-eligible op in this domain"
    (is (= #{:vessel/intake} (:auto (get phase/phases 3))))))

(deftest gate-hold-always-wins
  (is (= :hold (:disposition (phase/gate 3 {:op :vessel/intake} :hold)))))

(deftest gate-escalates-a-clean-non-auto-write
  (is (= :escalate (:disposition (phase/gate 3 {:op :voyage/dispatch} :commit))))
  (is (= :escalate (:disposition (phase/gate 3 {:op :discharge/settle} :commit)))))

(deftest gate-holds-a-write-disabled-in-this-phase
  (is (= :hold (:disposition (phase/gate 0 {:op :vessel/intake} :commit)))))
