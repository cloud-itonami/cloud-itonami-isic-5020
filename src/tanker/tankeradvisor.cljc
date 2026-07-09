(ns tanker.tankeradvisor
  "TankerAdvisor client -- the *contained intelligence node* for the
  marine-tanker actor.

  It normalizes vessel-shipment intake, drafts a per-jurisdiction
  bill-of-lading / IMO / inert-gas / bonding evidence checklist, drafts
  the voyage-dispatch action, and drafts the discharge-settlement
  action. CRITICAL: it is a smart-but-untrusted advisor. It returns a
  *proposal* (with a rationale + the fields it cited), never a
  committed record or a real dispatch/discharge. Every output is
  censored downstream by `tanker.governor` before anything touches the
  SSoT, and `:voyage/dispatch`/`:discharge/settle` proposals NEVER
  auto-commit at any phase -- see README `Actuation`.

  Like every sibling actor's advisor, this is a deterministic mock so
  the actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- SCANNED by the spec-basis gate
     :cites      [kw|str ..]    ; facts/sources the LLM used -- SCANNED too
     :effect     kw             ; how a commit would mutate the SSoT
     :stake      kw|nil         ; :voyage/dispatch | :discharge/settle | nil
     :confidence 0..1}"
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
           [clojure.string :as str]
           [tanker.facts :as facts]
           [tanker.registry :as registry]
           [tanker.store :as store]
           [langchain.model :as model]))

(defn- normalize-intake
  "Directory upsert -- the LLM only normalizes/validates the patch; it
  does not invent the vessel-imo, bill-of-lading-no, cargo-grade or
  jurisdiction. High confidence, low stakes."
  [_db {:keys [patch]}]
  {:summary    (str "船積記録更新: " (pr-str (keys patch)))
   :rationale  "入力 patch の正規化のみ。新規事実の生成なし。"
   :cites      (vec (keys patch))
   :effect     :vessel/upsert
   :value      patch
   :stake      nil
   :confidence 0.97})

(defn- assess-bill-of-lading
  "Per-jurisdiction bill-of-lading / IMO / inert-gas / bonding evidence
  checklist draft. `:no-spec?` injects the failure mode we must defend
  against: proposing a checklist for a jurisdiction with NO official
  spec-basis in `tanker.facts` -- the Marine Cargo Governor must reject
  this (never invent a jurisdiction's requirements)."
  [db {:keys [subject no-spec?]}]
  (let [vs (store/vessel-shipment db subject)
        iso3 (if no-spec? "ATL" (:jurisdiction vs))
        sb (facts/spec-basis iso3)]
    (if (nil? sb)
      {:summary    (str iso3 " の公式spec-basisが見つかりません")
       :rationale  "tanker.facts に未登録の法域。要件を推測で作らない。"
       :cites      []
       :effect     :bl-assessment/set
       :value      {:jurisdiction iso3 :checklist [] :spec-basis nil}
       :stake      nil
       :confidence 0.9}
      {:summary    (str iso3 " (" (:owner-authority sb) ") 向け必要書類 "
                        (count (:required-evidence sb)) " 件を提案")
       :rationale  (str "公式ソース: " (:provenance sb) " / 法的根拠: " (:legal-basis sb))
       :cites      [(:legal-basis sb) (:provenance sb)]
       :effect     :bl-assessment/set
       :value      {:jurisdiction iso3
                    :checklist (:required-evidence sb)
                    :spec-basis (:provenance sb)
                    :legal-basis (:legal-basis sb)}
       :stake      nil
       :confidence 0.9})))

(defn- propose-dispatch
  "Draft the actual VOYAGE-DISPATCH action -- dispatching a real laden
  tanker voyage (the vessel sailing from load port to discharge port).
  ALWAYS `:stake :voyage/dispatch` -- this is a REAL-WORLD act (an
  autonomous tanker-loading/valve robot physically handles the cargo
  and the master sails the laden vessel, or an operator does), never a
  draft the actor may auto-run. See README `Actuation`: no phase ever
  adds this op to a phase's `:auto` set (`tanker.phase`); the governor
  also always escalates on `:voyage/dispatch`. Two independent layers
  agree, deliberately."
  [db {:keys [subject]}]
  (let [vs (store/vessel-shipment db subject)
        imo-ok? (and vs (registry/imo-number-valid? (:vessel-imo vs)))
        bl-ok? (and vs (true? (:bl-verified? vs)))
        grade-ok? (and vs (:bl-declared-grade vs) (:cargo-grade vs)
                       (= (:bl-declared-grade vs) (:cargo-grade vs)))
        overload-ok? (and vs (:load-displacement-pct vs) (:load-displacement-max vs)
                          (<= (:load-displacement-pct vs) (:load-displacement-max vs)))
        o2-ok? (and vs (not (registry/inert-gas-o2-excessive?
                             (:inert-gas-o2-percent vs) (:o2-limit-percent vs))))
        bonding-ok? (and vs (true? (:bonding-grounding-confirmed? vs)))]
    {:summary    (str subject " 向け航海発航提案"
                      (when vs (str " (vessel-imo=" (:vessel-imo vs)
                                    ", load=" (:load-port vs) ")")))
     :rationale  (if vs
                   (str "imo-valid?=" imo-ok?
                        " bl-verified?=" bl-ok?
                        " grade-match?=" grade-ok?
                        " within-dwt?=" overload-ok?
                        " inert-gas-ok?=" o2-ok?
                        " bonding-confirmed?=" bonding-ok?)
                   "vessel-shipmentが見つかりません")
     :cites      (if vs [subject] [])
     :effect     :vessel/mark-dispatched
     :value      {:vessel-shipment-id subject}
     :stake      :voyage/dispatch
     :confidence (if (and imo-ok? bl-ok? grade-ok? overload-ok? o2-ok? bonding-ok?)
                   0.9 0.3)}))

(defn- propose-settlement
  "Draft the actual DISCHARGE-SETTLEMENT action -- settling a real
  cargo discharge (the laden volume received at the discharge port, the
  cargo transfer finalized). ALWAYS `:stake :discharge/settle` -- this
  is a REAL-WORLD act (real volume / real custody moves between
  vessel and terminal), never a draft the actor may auto-run. See
  README `Actuation`: no phase ever adds this op to a phase's `:auto`
  set (`tanker.phase`); the governor also always escalates on
  `:discharge/settle` (and independently re-checks the HSE-CRITICAL
  inert-gas O2 at discharge). Two independent layers agree,
  deliberately."
  [db {:keys [subject]}]
  (let [vs (store/vessel-shipment db subject)
        dispatched? (and vs (:dispatched? vs))]
    {:summary    (str subject " 向け揚荷精算提案"
                      (when vs (str " (discharge-port=" (:discharge-port vs) ")")))
     :rationale  (if vs
                   (str "dispatched?=" dispatched?)
                   "vessel-shipmentが見つかりません")
     :cites      (if vs [subject] [])
     :effect     :vessel/mark-discharged
     :value      {:vessel-shipment-id subject}
     :stake      :discharge/settle
     :confidence (if dispatched? 0.9 0.3)}))

(defn infer
  "Route a request to the right proposal generator.
  request: {:op kw :subject id ...op-specific...}"
  [db {:keys [op] :as request}]
  (case op
    :vessel/intake        (normalize-intake db request)
    :bill-of-lading/verify (assess-bill-of-lading db request)
    :voyage/dispatch      (propose-dispatch db request)
    :discharge/settle     (propose-settlement db request)
    {:summary "未対応の操作" :rationale (str op) :cites []
     :effect :noop :stake nil :confidence 0.0}))

;; ----------------------------- Advisor protocol -----------------------------

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

(defn mock-advisor
  "The deterministic advisor (the `infer` logic above). Default everywhere."
  [] (reify Advisor (-advise [_ st req] (infer st req))))

(def ^:private system-prompt
  (str "あなたは地域海運タンカー事業者の航海発航・揚荷精算エージェントの助言者です。"
       "与えられた事実のみに基づき、提案を1つだけEDNマップで返します。"
       "説明や前置きは一切書かず、EDNだけを出力します。\n"
       "キー: :summary(人向けドラフト) :rationale(根拠/必ず事実から) "
       ":cites(使った事実キーのベクタ) "
       ":effect(:vessel/upsert|:bl-assessment/set|:vessel/mark-dispatched|"
       ":vessel/mark-discharged) "
       ":stake(:voyage/dispatch か :discharge/settle か nil) :confidence(0..1)。\n"
       "重要: 登録されていない法域のタンカー安全要件を絶対に創作してはいけません。"
       "spec-basisが無い場合は :cites を空にし confidence を上げないこと。"
       "IMO番号・B/L検証状態・貨物グレード・積載排水量・インガスO2濃度・ボンディング状態を偽って報告してはいけません。"))

(defn- facts-for [st {:keys [op subject]}]
  (case op
    :bill-of-lading/verify {:vessel-shipment (store/vessel-shipment st subject)}
    :voyage/dispatch      {:vessel-shipment (store/vessel-shipment st subject)}
    :discharge/settle     {:vessel-shipment (store/vessel-shipment st subject)}
    {:vessel-shipment (store/vessel-shipment st subject)}))

(defn- parse-proposal
  "Parse the model's EDN proposal defensively. Any parse/shape failure
  yields a safe low-confidence noop so the Marine Cargo Governor
  escalates/holds -- an LLM hiccup can never auto-dispatch a voyage or
  auto-settle a discharge."
  [content]
  (let [p (try (edn/read-string (str/trim (str content)))
               (catch #?(:clj Exception :cljs :default) _ nil))]
    (if (map? p)
      (-> p
          (update :cites #(vec (or % [])))
          (update :confidence #(if (number? %) (double %) 0.0))
          (update :effect #(or % :noop)))
      {:summary "LLM応答を解釈できませんでした" :rationale (str content)
       :cites [] :effect :noop :stake nil :confidence 0.0})))

(defn llm-advisor
  "An advisor backed by a `langchain.model/ChatModel` (real inference)."
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-advise [_ st req]
       (let [msgs [{:role :system :content system-prompt}
                   {:role :user :content (str "操作: " (:op req)
                                              "\n対象: " (:subject req)
                                              "\n事実: " (pr-str (facts-for st req)))}]
             resp (model/-generate chat-model msgs gen-opts)]
         (parse-proposal (:content resp)))))))

(defn trace
  "Decision-grounded audit record -- persisted to the :audit channel."
  [request proposal]
  {:t          :tankeradvisor-proposal
   :op         (:op request)
   :subject    (:subject request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :cites      (:cites proposal)
   :confidence (:confidence proposal)})
