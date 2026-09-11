(ns tanker.facts-test
  (:require [clojure.test :refer [deftest is]]
            [tanker.facts :as facts]))

(deftest jpn-has-a-spec-basis
  (is (some? (facts/spec-basis "JPN")))
  (is (string? (:provenance (facts/spec-basis "JPN")))))

(deftest all-five-seeded-jurisdictions-have-required-evidence
  ;; every seeded marine-tanker jurisdiction actually has a real
  ;; required-evidence set reported honestly here
  (doseq [iso3 ["JPN" "USA" "GBR" "NOR" "SGP"]]
    (is (seq (:required-evidence (facts/spec-basis iso3))) (str iso3 " required-evidence"))
    (is (string? (:legal-basis (facts/spec-basis iso3))) (str iso3 " legal-basis"))))

(deftest sgp-has-a-spec-basis-with-the-mpa-flag-state-sections
  ;; Singapore (5th jurisdiction, added 2026-07): Maritime and Port
  ;; Authority (MPA) -- Merchant Shipping Act 1995 s.206/s.113 and
  ;; Prevention of Pollution of the Sea Act 1990 s.22/s.23, verified
  ;; against Singapore Statutes Online (sso.agc.gov.sg).
  (let [sgp (facts/spec-basis "SGP")]
    (is (some? sgp))
    (is (= "Singapore" (:name sgp)))
    (is (= "Maritime and Port Authority of Singapore (MPA)" (:owner-authority sgp)))
    (is (string? (:provenance sgp)))
    (is (re-find #"sso\.agc\.gov\.sg" (:provenance sgp)))
    (is (re-find #"s\.206" (:legal-basis sgp)) "cites MSA 1995 s.206 (powers of inspector)")
    (is (re-find #"s\.113" (:legal-basis sgp)) "cites MSA 1995 s.113 (detention of unsafe ship)")
    (is (re-find #"s\.22" (:legal-basis sgp)) "cites POTSA 1990 s.22 (powers of inspectors)")
    (is (re-find #"s\.23" (:legal-basis sgp)) "cites POTSA 1990 s.23 (power to deny entry / detain ship)")
    (is (= ["bill-of-lading"
            "IMO registry / vessel record"
            "inert-gas-system (IGS) operational record"
            "ship-shore bonding confirmation"]
           (:required-evidence sgp)))))

(deftest unknown-jurisdiction-has-no-fabricated-spec-basis
  (is (nil? (facts/spec-basis "ATL"))))

(deftest coverage-never-reports-a-missing-jurisdiction-as-covered
  (let [report (facts/coverage ["JPN" "ATL" "GBR"])]
    (is (= 2 (:covered report)))
    (is (= ["ATL"] (:missing-jurisdictions report)))
    (is (= ["GBR" "JPN"] (:covered-jurisdictions report)))))

(deftest required-evidence-satisfied-needs-every-item
  (let [all (facts/evidence-checklist "JPN")]
    (is (facts/required-evidence-satisfied? "JPN" all))
    (is (not (facts/required-evidence-satisfied? "JPN" (rest all))))
    (is (not (facts/required-evidence-satisfied? "ATL" all)) "no spec-basis -> never satisfied")))
