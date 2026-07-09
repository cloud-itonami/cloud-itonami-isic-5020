(ns tanker.registry
  "Pure-function voyage-dispatch + discharge-settlement record
  construction -- an append-only tanker book-of-record draft -- AND
  the pure marine-cargo range-check functions the Marine Cargo
  Governor calls to re-verify a vessel-shipment's own ground truth
  before any voyage dispatch or discharge.

  Unlike `freightops`/4920's own registry (which delegates tracking-
  number validation to a real, pre-existing bespoke capability library
  `kotoba-lang/logistics`), this marine-tanker vertical has NO
  pre-existing maritime capability library to wrap -- there is no
  'kotoba-lang/maritime' to call. So this namespace is self-contained:
  the IMO-number structural check and the SOLAS inert-gas O2 check are
  pure functions defined HERE, not delegated. The actor layer adds the
  governed proposal/approval loop on top; the governor calls these same
  pure functions to INDEPENDENTLY re-verify the vessel-shipment's own
  recorded values before any real-world voyage dispatch or discharge,
  rather than trusting the advisor's self-reported confidence.

  Like every sibling actor's registry, there is no single international
  reference-number standard for a voyage-dispatch or discharge-
  settlement record -- every operator/jurisdiction assigns its own
  reference format. This namespace does NOT invent one beyond a
  jurisdiction-scoped sequence number; it validates the record's
  required fields, the same honest, non-fabricating discipline
  `tanker.facts` uses.

  This namespace is pure data + pure functions -- no I/O, no network
  call to any real AIS / VTS / ship-shore system. It builds the RECORD
  an operator would keep, not the act of dispatching a real tanker
  voyage or settling a real discharge itself (that is `tanker.
  operation`'s `:voyage/dispatch`/`:discharge/settle`, always human-
  gated -- see README `Actuation`)."
  (:require [clojure.string :as str]))

(defn- unsigned-certificate
  "Every certificate this actor produces is UNSIGNED -- signature is
  the operator's act, not this actor's. See README `Actuation`."
  [kind subject record-id]
  {"@context" ["https://www.w3.org/ns/credentials/v2"]
   "type" ["VerifiableCredential" kind]
   "credentialSubject" {"id" subject "record" record-id}
   "proof" nil
   "issued_by_registry" false
   "status" "draft-unsigned"})

(defn- zero-pad [n w]
  (let [s (str n)]
    (str (apply str (repeat (max 0 (- w (count s))) "0")) s)))

;; ----------------------------- marine-cargo range checks (pure) -----------------------------
;;
;; The Marine Cargo Governor calls these to INDEPENDENTLY re-verify the
;; vessel-shipment's own recorded values before authorizing a voyage
;; dispatch or a discharge. Each returns true when the value is provably
;; OUTSIDE the safe envelope -- the conservative marine-cargo choice,
;; matching the tracking-validity discipline of the freight siblings
;; (`freightops.registry/tracking-valid?`) and the measured-value-vs-
;; rated-limit discipline of the fabrication siblings: a value that
;; cannot be certified inside the safe envelope is treated as a
;; violation, not as 'unknown therefore ok'. Missing data -> violation
;; (cannot verify safe to dispatch / discharge).

(defn- imo-digits
  "The 7 integer digits of an IMO number string, or nil if it is not
  exactly 7 base-10 digits. Portable across JVM / cljs (single-char
  ASCII digit arithmetic, no platform parseInt)."
  [s]
  (when (and (string? s) (= 7 (count s)) (re-find #"^[0-9]{7}$" s))
    (mapv #(- (int %) (int \0)) s)))

(defn imo-number-valid?
  "IMO ship identification number structural validation -- the SOLAS /
  IMO resolution A.600(15) seven-digit check-digit scheme. The first
  six digits are multiplied by weights 7, 6, 5, 4, 3, 2 (left to
  right); the units digit of the sum MUST equal the 7th (check) digit.
  This is the SAME 'reuse a validated structural check' discipline
  `retailops.governor`'s ean13 and the freight siblings' tracking-
  number checks establish -- an honest reapplication of the IMO scheme
  rather than a placeholder format check. Pure (no I/O), portable
  (.cljc). Returns false for any non-7-digit / non-numeric / wrong-
  check-digit input; the governor treats false as a HARD
  `:imo-number-invalid` hold (the freight tracking-valid? discipline,
  inverted: the registry returns 'is it valid?', the governor negates)."
  [imo]
  (boolean
   (when-let [ds (imo-digits (str imo))]
     (let [weights [7 6 5 4 3 2]
           chk (mod (reduce + (map * (take 6 ds) weights)) 10)]
       (= chk (nth ds 6))))))

(defn inert-gas-o2-excessive?
  "SOLAS Chapter II-2 inert-gas-system O2 limit: the measured cargo-
  tank / vapor-space oxygen concentration must not exceed the limit
  (8 vol% per SOLAS II-2/4.5.5 for tankers carrying crude oil /
  petroleum products with a flashpoint <= 60C). An inert-gas system
  keeps the tank atmosphere below the oxygen limit so a flammable
  mixture cannot form during loading, discharging or crude washing --
  exceeding it is a true explosion precursor, evaluated
  UNCONDITIONALLY at both `:voyage/dispatch` and `:discharge/settle`,
  and NO human approver may override it. The fabrication measured-
  value-vs-rated-limit discipline. Missing either value -> unsafe
  (cannot verify the tank atmosphere is inerted before cargo
  handling)."
  [o2-percent o2-limit-percent]
  (cond
    (or (nil? o2-percent) (nil? o2-limit-percent)) true
    (> o2-percent o2-limit-percent)                true
    :else                                          false))

;; ----------------------------- record construction -----------------------------

(defn register-voyage-record
  "Validate + construct the VOYAGE-DISPATCH registration DRAFT -- the
  operator's own legal act of dispatching a real tanker voyage (a laden
  vessel sailing from load port to discharge port). Pure function --
  does not touch any real AIS / VTS / chartering system; it builds the
  RECORD an operator would keep. `tanker.governor` independently
  re-verifies the vessel-shipment's own IMO-number, bill-of-lading,
  cargo-grade, displacement, inert-gas-O2 and bonding ground truth, and
  blocks a double-dispatch of the same shipment, before this is ever
  allowed to commit."
  [vessel-shipment-id jurisdiction sequence]
  (when-not (and vessel-shipment-id (not= vessel-shipment-id ""))
    (throw (ex-info "voyage-dispatch: vessel_shipment_id required" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "voyage-dispatch: jurisdiction required" {})))
  (when (< sequence 0)
    (throw (ex-info "voyage-dispatch: sequence must be >= 0" {})))
  (let [voyage-number (str (str/upper-case jurisdiction) "-VOYAGE-" (zero-pad sequence 6))
        record {"record_id" voyage-number
                "kind" "voyage-dispatch-draft"
                "vessel_shipment_id" vessel-shipment-id
                "jurisdiction" jurisdiction
                "immutable" true}]
    {"record" record "voyage_number" voyage-number
     "certificate" (unsigned-certificate "VoyageDispatch" voyage-number voyage-number)}))

(defn register-discharge-record
  "Validate + construct the DISCHARGE-SETTLEMENT registration DRAFT --
  the operator's own legal act of settling a real cargo discharge (the
  laden volume received at the discharge port, the cargo transfer
  finalized). Pure function -- does not touch any real terminal /
  cargo-accounting system; it builds the RECORD an operator would
  keep. `tanker.governor` independently re-verifies the vessel-
  shipment's own inert-gas-O2 ground truth (HSE-CRITICAL, evaluated
  UNCONDITIONALLY at discharge too) and evidence completeness, and
  blocks a double-discharge of the same shipment, before this is ever
  allowed to commit."
  [vessel-shipment-id jurisdiction sequence]
  (when-not (and vessel-shipment-id (not= vessel-shipment-id ""))
    (throw (ex-info "discharge-settlement: vessel_shipment_id required" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "discharge-settlement: jurisdiction required" {})))
  (when (< sequence 0)
    (throw (ex-info "discharge-settlement: sequence must be >= 0" {})))
  (let [discharge-number (str (str/upper-case jurisdiction) "-DISCHARGE-" (zero-pad sequence 6))
        record {"record_id" discharge-number
                "kind" "discharge-settlement-draft"
                "vessel_shipment_id" vessel-shipment-id
                "jurisdiction" jurisdiction
                "immutable" true}]
    {"record" record "discharge_number" discharge-number
     "certificate" (unsigned-certificate "DischargeSettlement" discharge-number discharge-number)}))

(defn append [history result]
  (conj (vec history) (get result "record")))
