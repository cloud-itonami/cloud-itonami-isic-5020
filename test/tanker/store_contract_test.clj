(ns tanker.store-contract-test
  "The Store contract, run against BOTH backends. Proving MemStore and
  the Datomic-backed (langchain.db) store satisfy the same contract is
  what makes 'swap the SSoT for Datomic / kotoba-server' a
  configuration change, not a rewrite -- see `cloud-itonami-isic-6511`'s
  `underwriting.store-contract-test` for the same pattern on the
  sibling actor."
  (:require [clojure.test :refer [deftest is testing]]
            [tanker.store :as store]))

(defn- backends []
  [["MemStore" (store/seed-db)] ["DatomicStore" (store/datomic-seed-db)]])

(deftest read-parity
  (doseq [[label s] (backends)]
    (testing label
      (is (= "JPN" (:jurisdiction (store/vessel-shipment s "vs-1"))))
      (is (= "9074729" (:vessel-imo (store/vessel-shipment s "vs-1"))))
      (is (= "arakawa-light" (:cargo-grade (store/vessel-shipment s "vs-1"))))
      (is (= "ATL" (:jurisdiction (store/vessel-shipment s "vs-2"))))
      (is (= "9074728" (:vessel-imo (store/vessel-shipment s "vs-3"))) "vs-3 invalid IMO")
      (is (false? (:bl-verified? (store/vessel-shipment s "vs-4"))) "vs-4 B/L unverified")
      (is (= "arakawa-heavy" (:cargo-grade (store/vessel-shipment s "vs-5"))) "vs-5 grade mismatch")
      (is (= 105.0 (:load-displacement-pct (store/vessel-shipment s "vs-6"))) "vs-6 overloaded")
      (is (= 10.0 (:inert-gas-o2-percent (store/vessel-shipment s "vs-7"))) "vs-7 O2 excessive")
      (is (false? (:bonding-grounding-confirmed? (store/vessel-shipment s "vs-8"))) "vs-8 bonding unconfirmed")
      (is (false? (:dispatched? (store/vessel-shipment s "vs-1"))))
      (is (false? (:discharged? (store/vessel-shipment s "vs-1"))))
      (is (= ["vs-1" "vs-2" "vs-3" "vs-4" "vs-5" "vs-6" "vs-7" "vs-8"]
             (mapv :id (store/all-vessel-shipments s))))
      (is (nil? (store/bl-assessment-of s "vs-1")))
      (is (= [] (store/ledger s)))
      (is (= [] (store/voyage-history s)))
      (is (= [] (store/discharge-history s)))
      (is (zero? (store/next-voyage-sequence s "JPN")))
      (is (zero? (store/next-discharge-sequence s "JPN")))
      (is (false? (store/vessel-shipment-already-dispatched? s "vs-1")))
      (is (false? (store/vessel-shipment-already-discharged? s "vs-1"))))))

(deftest write-and-ledger-parity
  (doseq [[label s] (backends)]
    (testing label
      (testing "partial upsert merges, preserving untouched fields"
        (store/commit-record! s {:effect :vessel/upsert
                                 :value {:id "vs-1" :bill-of-lading-no "BL-AK-9999"}})
        (is (= "BL-AK-9999" (:bill-of-lading-no (store/vessel-shipment s "vs-1"))))
        (is (= "JPN" (:jurisdiction (store/vessel-shipment s "vs-1"))) "unrelated field preserved"))
      (testing "bl-assessment payloads commit and read back"
        (store/commit-record! s {:effect :bl-assessment/set :path ["vs-1"]
                                 :payload {:jurisdiction "JPN" :checklist ["a" "b"]}})
        (is (= {:jurisdiction "JPN" :checklist ["a" "b"]} (store/bl-assessment-of s "vs-1"))))
      (testing "voyage dispatch drafts a record and advances the voyage sequence"
        (store/commit-record! s {:effect :vessel/mark-dispatched :path ["vs-1"]})
        (is (= "JPN-VOYAGE-000000" (get (first (store/voyage-history s)) "record_id")))
        (is (= "voyage-dispatch-draft" (get (first (store/voyage-history s)) "kind")))
        (is (true? (:dispatched? (store/vessel-shipment s "vs-1"))))
        (is (= 1 (count (store/voyage-history s))))
        (is (= 1 (store/next-voyage-sequence s "JPN")))
        (is (true? (store/vessel-shipment-already-dispatched? s "vs-1"))))
      (testing "discharge settlement drafts a record and advances the discharge sequence"
        (store/commit-record! s {:effect :vessel/mark-discharged :path ["vs-1"]})
        (is (= "JPN-DISCHARGE-000000" (get (first (store/discharge-history s)) "record_id")))
        (is (= "discharge-settlement-draft" (get (first (store/discharge-history s)) "kind")))
        (is (true? (:discharged? (store/vessel-shipment s "vs-1"))))
        (is (= 1 (count (store/discharge-history s))))
        (is (= 1 (store/next-discharge-sequence s "JPN")))
        (is (true? (store/vessel-shipment-already-discharged? s "vs-1"))))
      (testing "ledger is append-only and order-preserving"
        (store/append-ledger! s {:op :a :disposition :commit})
        (store/append-ledger! s {:op :b :disposition :hold})
        (is (= [:commit :hold] (mapv :disposition (store/ledger s))))))))

(deftest datomic-empty-store-is-usable
  (let [s (store/datomic-store)]
    (is (nil? (store/vessel-shipment s "nope")))
    (is (= [] (store/all-vessel-shipments s)))
    (is (= [] (store/ledger s)))
    (is (= [] (store/voyage-history s)))
    (is (= [] (store/discharge-history s)))
    (is (zero? (store/next-voyage-sequence s "JPN")))
    (is (zero? (store/next-discharge-sequence s "JPN")))
    (store/with-vessel-shipments s {"x" {:id "x" :vessel-imo "9074729" :bill-of-lading-no "BL-X"
                                         :cargo-grade "arakawa-light" :bl-declared-grade "arakawa-light"
                                         :volume-barrels 500000 :load-port "Akita" :discharge-port "Yokohama"
                                         :bl-verified? true
                                         :inert-gas-o2-percent 4.0 :o2-limit-percent 8.0
                                         :load-displacement-pct 80.0 :load-displacement-max 100.0
                                         :bonding-grounding-confirmed? true
                                         :dispatched? false :discharged? false
                                         :jurisdiction "JPN" :status :intake}})
    (is (= "9074729" (:vessel-imo (store/vessel-shipment s "x"))))))
