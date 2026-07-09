(ns tanker.registry-test
  (:require [clojure.test :refer [deftest is]]
            [tanker.registry :as r]))

;; ----------------------------- IMO-number check-digit validation -----------------------------

(deftest imo-valid-numbers-pass
  (is (r/imo-number-valid? "9074729") "valid IMO (check digit 9) -> ok")
  (is (r/imo-number-valid? "9302578") "valid IMO (check digit 8) -> ok")
  (is (r/imo-number-valid? 9074729) "numeric input coerced to 7-digit string -> ok"))

(deftest imo-wrong-check-digit-fails
  (is (not (r/imo-number-valid? "9074728")) "wrong check digit (8 vs expected 9) -> invalid"))

(deftest imo-structural-failures
  (is (not (r/imo-number-valid? "907472")) "6 digits -> invalid")
  (is (not (r/imo-number-valid? "90747290")) "8 digits -> invalid")
  (is (not (r/imo-number-valid? "9074abc")) "non-numeric -> invalid")
  (is (not (r/imo-number-valid? nil)) "nil -> invalid")
  (is (not (r/imo-number-valid? "")) "empty -> invalid"))

;; ----------------------------- inert-gas O2 vs SOLAS limit -----------------------------

(deftest inert-gas-o2-vs-limit
  (is (not (r/inert-gas-o2-excessive? 4.0 8.0)) "below limit -> ok")
  (is (not (r/inert-gas-o2-excessive? 8.0 8.0)) "at limit -> ok (not excessive)")
  (is (r/inert-gas-o2-excessive? 10.0 8.0) "above limit -> excessive")
  (is (r/inert-gas-o2-excessive? nil 8.0) "missing measured -> unsafe")
  (is (r/inert-gas-o2-excessive? 10.0 nil) "missing limit -> unsafe"))

;; ----------------------------- register-voyage-record -----------------------------

(deftest voyage-is-a-draft-not-a-real-dispatch
  (let [result (r/register-voyage-record "vs-1" "JPN" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest voyage-assigns-voyage-number
  (let [result (r/register-voyage-record "vs-1" "JPN" 7)]
    (is (= (get result "voyage_number") "JPN-VOYAGE-000007"))
    (is (= (get-in result ["record" "vessel_shipment_id"]) "vs-1"))
    (is (= (get-in result ["record" "kind"]) "voyage-dispatch-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest voyage-validation-rules
  (is (thrown? Exception (r/register-voyage-record "" "JPN" 0)))
  (is (thrown? Exception (r/register-voyage-record "vs-1" "" 0)))
  (is (thrown? Exception (r/register-voyage-record "vs-1" "JPN" -1))))

;; ----------------------------- register-discharge-record -----------------------------

(deftest discharge-is-a-draft-not-a-real-discharge
  (let [result (r/register-discharge-record "vs-1" "JPN" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest discharge-assigns-discharge-number
  (let [result (r/register-discharge-record "vs-1" "JPN" 7)]
    (is (= (get result "discharge_number") "JPN-DISCHARGE-000007"))
    (is (= (get-in result ["record" "vessel_shipment_id"]) "vs-1"))
    (is (= (get-in result ["record" "kind"]) "discharge-settlement-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest discharge-validation-rules
  (is (thrown? Exception (r/register-discharge-record "" "JPN" 0)))
  (is (thrown? Exception (r/register-discharge-record "vs-1" "" 0)))
  (is (thrown? Exception (r/register-discharge-record "vs-1" "JPN" -1))))

(deftest history-is-append-only
  (let [c1 (r/register-voyage-record "vs-1" "JPN" 0)
        hist (r/append [] c1)
        c2 (r/register-voyage-record "vs-2" "JPN" 1)
        hist2 (r/append hist c2)]
    (is (= 2 (count hist2)))
    (is (= "JPN-VOYAGE-000000" (get-in hist2 [0 "record_id"])))
    (is (= "JPN-VOYAGE-000001" (get-in hist2 [1 "record_id"])))))
