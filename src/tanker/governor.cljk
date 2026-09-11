(ns tanker.governor
  "Marine Cargo Governor -- the independent compliance layer that earns
  the TankerAdvisor the right to commit. The LLM has no notion of
  jurisdictional tanker / inert-gas / ship-shore law, whether a
  vessel's IMO number actually passes its 7-digit check-digit
  validation, whether the bill of lading is actually verified, whether
  the B/L declared cargo grade actually matches the loaded grade,
  whether the load displacement actually stays within the vessel's safe
  DWT limit, whether the cargo-tank atmosphere is actually inerted
  below the SOLAS 8 vol% O2 ceiling, whether the ship-shore bonding is
  actually confirmed, or when an act stops being a draft and becomes a
  real-world tanker voyage dispatch or cargo discharge settlement, so
  this MUST be a separate system able to *reject* a proposal and fall
  back to HOLD.

  Unlike `freightops`/4920's own governor (built on TOP of a real,
  pre-existing bespoke capability library `kotoba-lang/logistics`),
  this marine-tanker vertical has NO pre-existing maritime capability
  library to delegate to -- so the IMO-number structural check and the
  SOLAS inert-gas O2 check are pure functions defined in
  `tanker.registry` and called directly here, the SAME 'reuse a
  capability library's own validated function' discipline
  `retailops.governor`'s ean13 check establishes, here applied to this
  vertical's OWN pure registry functions rather than a separate
  library.

  `:itonami.blueprint/governor` is `:marine-cargo-governor`, grep-
  verified UNIQUE fleet-wide -- no naming-collision precedent
  question, a fresh independent build following the SAME governed-
  actor architecture (langgraph StateGraph + independent Governor +
  Phase 0->3 rollout) established by `cloud-itonami-isic-6511`.

  Eight checks, in priority order, ALL HARD violations: a human
  approver CANNOT override them -- and the inert-gas O2 check is
  HSE-CRITICAL, evaluated UNCONDITIONALLY at BOTH the voyage dispatch
  and the discharge settlement, so no human, no phase and no
  confidence score ever lets a non-inerted tanker atmosphere handle
  cargo. The confidence/actuation gate is SOFT: it asks a human to
  look (low confidence / actuation), and the human may approve -- but
  see `tanker.phase`: for `:stake :voyage/dispatch`/`:discharge/settle`
  (a real dispatch or settlement) NO phase ever allows auto-commit
  either. Two independent layers agree that actuation is always a
  human call.

    1. Spec-basis                  -- did the jurisdiction proposal cite
                                       an OFFICIAL source (`tanker.facts`),
                                       or invent one?
    2. Evidence incomplete         -- for `:voyage/dispatch`/`:discharge/
                                       settle`, has the shipment actually
                                       been verified with a full bill-of-
                                       lading / IMO / IGS / bonding
                                       evidence checklist on file?
    3. IMO number invalid          -- for `:voyage/dispatch`, INDEPENDENTLY
                                       validate the vessel's IMO number
                                       via `tanker.registry/imo-number-
                                       valid?` (the freight tracking-
                                       validity discipline, applied to the
                                       SOLAS A.600(15) 7-digit check-digit
                                       scheme).
    4. Bill-of-lading unverified   -- for `:voyage/dispatch`, INDEPENDENTLY
                                       verify the B/L is verified.
    5. Cargo-grade mismatch        -- for `:voyage/dispatch`, INDEPENDENTLY
                                       verify the B/L declared grade
                                       matches the loaded cargo grade
                                       (contamination / quality fraud).
    6. Vessel overload             -- for `:voyage/dispatch`, INDEPENDENTLY
                                       verify the load displacement stays
                                       within the vessel's safe DWT limit
                                       (the fabrication measured-ratio-vs-
                                       rated-limit discipline).
    7. Inert-gas O2 excessive      -- HSE-CRITICAL: for `:voyage/dispatch`
                                       AND `:discharge/settle`, INDEPENDENTLY
                                       verify the cargo-tank O2 stays below
                                       the SOLAS 8 vol% ceiling via
                                       `tanker.registry/inert-gas-o2-
                                       excessive?` -- a true explosion
                                       precursor, evaluated
                                       UNCONDITIONALLY, overridable by NO
                                       human.
    8. Bonding-grounding unconfirmed -- for `:voyage/dispatch`, INDEPENDENTLY
                                       verify the ship-shore bonding/
                                       grounding is confirmed (static-
                                       electricity ignition during cargo
                                       handling).
    9. Confidence floor / actuation
       gate                          -- LLM confidence below threshold,
                                       OR the op is `:voyage/dispatch`/
                                       `:discharge/settle` (REAL acts)
                                       -> escalate.

  Two more guards, double-dispatch/double-discharge prevention, are
  enforced but NOT listed as numbered HARD checks above because they
  need no upstream comparison at all -- `already-dispatched-violations`/
  `already-discharged-violations` refuse to dispatch/settle the SAME
  vessel-shipment twice, off dedicated `:dispatched?`/`:discharged?`
  facts (never a `:status` value) -- the SAME 'check a dedicated
  boolean, not status' discipline every prior governor's guards
  establish, informed by `cloud-itonami-isic-6492`'s status-lifecycle
  bug (ADR-2607071320)."
  (:require [tanker.facts :as facts]
            [tanker.registry :as registry]
            [tanker.store :as store]))

(def confidence-floor 0.6)

(def high-stakes
  "Stakes grave enough to always require a human, even when clean.
  Dispatching a real laden tanker voyage (a cargo sailing from load
  port to discharge port) and settling the real cargo discharge (the
  laden volume received, the transfer finalized) are the two
  real-world actuation events this actor performs -- a two-member set,
  matching every sibling's own dual-actuation shape."
  #{:voyage/dispatch :discharge/settle})

;; ----------------------------- checks -----------------------------

(defn- spec-basis-violations
  "A `:bill-of-lading/verify` (or `:voyage/dispatch`/`:discharge/settle`)
  proposal with no spec-basis citation is a HARD violation -- never
  invent a jurisdiction's tanker / inert-gas / ship-shore requirements."
  [{:keys [op]} proposal]
  (when (contains? #{:bill-of-lading/verify :voyage/dispatch :discharge/settle} op)
    (let [value (:value proposal)]
      (when (or (empty? (:cites proposal))
                (and (contains? value :spec-basis) (nil? (:spec-basis value))))
        [{:rule :no-spec-basis
          :detail "公式spec-basisの引用が無い提案は法域要件として扱えない"}]))))

(defn- evidence-incomplete-violations
  "For `:voyage/dispatch`/`:discharge/settle`, the jurisdiction's
  required bill-of-lading / IMO-registry / IGS-operational /
  ship-shore-bonding evidence must actually be satisfied -- do not
  trust the advisor's self-reported confidence alone."
  [{:keys [op subject]} st]
  (when (contains? #{:voyage/dispatch :discharge/settle} op)
    (let [vs (store/vessel-shipment st subject)
          assessment (store/bl-assessment-of st subject)]
      (when-not (and assessment
                     (facts/required-evidence-satisfied?
                      (:jurisdiction vs) (:checklist assessment)))
        [{:rule :evidence-incomplete
          :detail "法域の必要書類(B/L, IMO船籍記録, インガス稼働記録, 船陸接続ボンディング等)が充足していない状態での提案"}]))))

(defn- imo-number-invalid-violations
  "For `:voyage/dispatch`, INDEPENDENTLY validate the vessel's IMO
  number via `tanker.registry/imo-number-valid?` (the freight
  tracking-validity discipline applied to the SOLAS A.600(15) 7-digit
  check-digit scheme). Evaluated UNCONDITIONALLY (every dispatch needs
  a structurally valid IMO ship identity)."
  [{:keys [op subject]} st]
  (when (= op :voyage/dispatch)
    (let [vs (store/vessel-shipment st subject)]
      (when (not (registry/imo-number-valid? (:vessel-imo vs)))
        [{:rule :imo-number-invalid
          :detail (str subject " のIMO番号(" (:vessel-imo vs)
                      ")は7桁検査数字検証に失敗 -- 構造的に無効な船籍識別のため航海提案は進められない")}]))))

(defn- bl-unverified-violations
  "For `:voyage/dispatch`, INDEPENDENTLY verify the bill of lading is
  verified -- an unverified B/L is no authority to sail a laden cargo."
  [{:keys [op subject]} st]
  (when (= op :voyage/dispatch)
    (let [vs (store/vessel-shipment st subject)]
      (when (not (true? (:bl-verified? vs)))
        [{:rule :bl-unverified
          :detail (str subject " は船荷証券(B/L)が未検証 -- 航海提案は進められない")}]))))

(defn- cargo-grade-mismatch-violations
  "For `:voyage/dispatch`, INDEPENDENTLY verify the B/L declared cargo
  grade matches the loaded cargo grade -- a mismatch signals
  contamination or quality fraud (the freight tracking-validity
  discipline, applied to cargo identity). Missing either grade ->
  violation (cannot verify the cargo matches its B/L)."
  [{:keys [op subject]} st]
  (when (= op :voyage/dispatch)
    (let [vs (store/vessel-shipment st subject)
          declared (:bl-declared-grade vs)
          actual (:cargo-grade vs)]
      (when (or (nil? declared) (nil? actual) (not= declared actual))
        [{:rule :cargo-grade-mismatch
          :detail (str subject " のB/L宣言グレード(" declared ")と実際の貨物グレード(" actual
                      ")が不一致 -- 混入/品質詐欺リスクのため航海提案は進められない")}]))))

(defn- vessel-overload-violations
  "For `:voyage/dispatch`, INDEPENDENTLY verify the load displacement
  stays within the vessel's safe DWT limit (the fabrication measured-
  ratio-vs-rated-limit discipline, applied to deadweight tonnage). A
  laden displacement above the vessel's rated DWT is a structural/
  loadline overload -- evaluated UNCONDITIONALLY. Missing either
  value -> violation (cannot verify the vessel is within its safe
  displacement)."
  [{:keys [op subject]} st]
  (when (= op :voyage/dispatch)
    (let [vs (store/vessel-shipment st subject)
          pct (:load-displacement-pct vs)
          dwt-max (:load-displacement-max vs)]
      (when (or (nil? pct) (nil? dwt-max) (> pct dwt-max))
        [{:rule :vessel-overload
          :detail (str subject " の積載排水量(" pct "%)がDWT安全上限(" dwt-max
                      "%)を超過 -- 過積載のため航海提案は進められない")}]))))

(defn- inert-gas-o2-excessive-violations
  "HSE-CRITICAL: for `:voyage/dispatch` AND `:discharge/settle`,
  INDEPENDENTLY verify the cargo-tank O2 concentration stays below the
  SOLAS 8 vol% ceiling via `tanker.registry/inert-gas-o2-excessive?`
  (the fabrication measured-value-vs-rated-limit discipline) -- an
  inert-gas system keeps the tank atmosphere below the O2 limit so a
  flammable mixture cannot form during loading, discharging or crude
  washing. Evaluated UNCONDITIONALLY at BOTH the dispatch and the
  discharge; NO human approver may override it. Missing either
  value -> unsafe (cannot verify the tank atmosphere is inerted)."
  [{:keys [op subject]} st]
  (when (contains? #{:voyage/dispatch :discharge/settle} op)
    (let [vs (store/vessel-shipment st subject)]
      (when (registry/inert-gas-o2-excessive?
             (:inert-gas-o2-percent vs) (:o2-limit-percent vs))
        [{:rule :inert-gas-o2-excessive
          :detail (str subject " のインガスO2濃度(" (:inert-gas-o2-percent vs)
                      " vol%)がSOLAS上限(" (:o2-limit-percent vs)
                      " vol%)を超過 -- タンク雰囲気が不活性化されておらず爆発前兆のため、HSE-CRITICALとして提案は進められない")}]))))

(defn- bonding-grounding-unconfirmed-violations
  "For `:voyage/dispatch`, INDEPENDENTLY verify the ship-shore
  bonding/grounding is confirmed -- an unconfirmed bonding connection
  risks static-electricity ignition during cargo handling."
  [{:keys [op subject]} st]
  (when (= op :voyage/dispatch)
    (let [vs (store/vessel-shipment st subject)]
      (when (not (true? (:bonding-grounding-confirmed? vs)))
        [{:rule :bonding-grounding-unconfirmed
          :detail (str subject " は船陸間のボンディング/グラウンドが未確認 -- 静電気着火リスクのため航海提案は進められない")}]))))

(defn- already-dispatched-violations
  "For `:voyage/dispatch`, refuses to dispatch the SAME vessel-shipment
  twice, off a dedicated `:dispatched?` fact (never a `:status`
  value)."
  [{:keys [op subject]} st]
  (when (= op :voyage/dispatch)
    (when (store/vessel-shipment-already-dispatched? st subject)
      [{:rule :already-dispatched
        :detail (str subject " は既に航海発航済み")}])))

(defn- already-discharged-violations
  "For `:discharge/settle`, refuses to settle the SAME vessel-shipment's
  discharge twice, off a dedicated `:discharged?` fact (never a
  `:status` value)."
  [{:keys [op subject]} st]
  (when (= op :discharge/settle)
    (when (store/vessel-shipment-already-discharged? st subject)
      [{:rule :already-discharged
        :detail (str subject " は既に揚荷精算済み")}])))

(defn check
  "Censors a TankerAdvisor proposal against the governor rules.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :high-stakes? bool :hard? bool}."
  [request _context proposal st]
  (let [hard (into []
                   (concat (spec-basis-violations request proposal)
                           (evidence-incomplete-violations request st)
                           (imo-number-invalid-violations request st)
                           (bl-unverified-violations request st)
                           (cargo-grade-mismatch-violations request st)
                           (vessel-overload-violations request st)
                           (inert-gas-o2-excessive-violations request st)
                           (bonding-grounding-unconfirmed-violations request st)
                           (already-dispatched-violations request st)
                           (already-discharged-violations request st)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (high-stakes (:stake proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
