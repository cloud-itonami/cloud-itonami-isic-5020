(ns tanker.store
  "SSoT for the marine-tanker actor, behind a `Store` protocol so the
  backend is a swap, not a rewrite -- the same seam every prior
  `cloud-itonami-isic-*` actor in this fleet uses.

    - `MemStore`     -- atom of EDN. The deterministic default for
                        dev/tests/demo (no deps).
    - `DatomicStore` -- backed by `langchain.db`, a Datomic-API-compatible
                        EAV store (datalog q / pull / upsert). Pure `.cljc`,
                        so it runs offline AND can be pointed at a real
                        Datomic Local or a kotoba-server pod by swapping
                        `langchain.db`'s `:db-api` (see langchain.kotoba-db).

  Both implement the same protocol and pass the same contract
  (test/tanker/store_contract_test.clj), which is the whole point:
  the actor, the Marine Cargo Governor and the audit ledger never know
  which SSoT they run on.

  Like the crude sibling's own `well` entity (where `lift` and `settle`
  apply SEQUENTIALLY to the same well), this vertical's `dispatch` and
  `settle` actuation events apply SEQUENTIALLY to the SAME
  `vessel-shipment` -- a laden tanker voyage is dispatched first (act1),
  the cargo discharge is settled later (act2), on the same
  vessel-shipment record. Dedicated double-actuation-guard booleans
  (`:dispatched?`/`:discharged?`, never a `:status` value) refuse a
  second dispatch or a second discharge of the same shipment.

  The ledger stays append-only on every backend: 'which vessel-shipment
  was screened for an invalid IMO number, an unverified bill of lading,
  a cargo-grade mismatch, an overloaded displacement, an inert-gas O2
  above the SOLAS 8 vol% limit, or an unconfirmed ship-shore bonding,
  which shipment had a voyage dispatched, which discharge was settled,
  on what jurisdictional basis, approved by whom' is always a query
  over an immutable log -- the audit trail a regulator, a charterer, or
  an operator trusting a marine-tanker actor needs, and the evidence an
  operator needs if a dispatch or a discharge is later disputed."
  (:require [tanker.registry :as registry]
            [langchain.db :as d]
            [langchain-store.core :as ls]))

(defprotocol Store
  (vessel-shipment [s id])
  (all-vessel-shipments [s])
  (bl-assessment-of [s vs-id] "committed bill-of-lading / cargo-evidence assessment, or nil")
  (ledger [s])
  (voyage-history [s] "the append-only voyage-dispatch history (tanker.registry drafts)")
  (discharge-history [s] "the append-only discharge-settlement history (tanker.registry drafts)")
  (next-voyage-sequence [s jurisdiction] "next voyage-number sequence for a jurisdiction")
  (next-discharge-sequence [s jurisdiction] "next discharge-number sequence for a jurisdiction")
  (vessel-shipment-already-dispatched? [s vs-id] "has a voyage already been dispatched for this shipment?")
  (vessel-shipment-already-discharged? [s vs-id] "has this shipment's discharge already been settled?")
  (commit-record! [s record] "apply a committed op's record to the SSoT")
  (append-ledger! [s fact]   "append one immutable decision fact")
  (with-vessel-shipments [s vessel-shipments] "replace/seed the vessel-shipment directory (map id->vessel-shipment)"))

;; ----------------------------- demo data -----------------------------

(defn demo-data
  "A small, self-contained vessel-shipment set covering both actuation
  lifecycles (dispatch, discharge-settlement) plus the governor's own
  marine-cargo checks, so the actor + tests run offline. Each
  violation vessel-shipment isolates exactly ONE failure mode (the rest
  stay clean) following the 'exercise the failure mode directly, never
  only via a happy-path actuation' discipline every sibling governor's
  demo data establishes.

  `:vessel-imo` carries the IMO seven-digit ship number the governor's
  `imo-number-invalid` check-digit check validates; `:o2-limit-percent`
  is the SOLAS II-2/4.5.5 inert-gas O2 ceiling (8 vol%) recorded on
  the entity. 9074729 is a structurally valid IMO number (check digit
  verified); 9074728 deliberately fails the check digit."
  []
  {:vessel-shipments
   {"vs-1" {:id "vs-1" :vessel-imo "9074729" :bill-of-lading-no "BL-AK-0001"
            :cargo-grade "arakawa-light" :bl-declared-grade "arakawa-light"
            :volume-barrels 600000 :load-port "Akita" :discharge-port "Yokohama"
            :bl-verified? true
            :inert-gas-o2-percent 4.0 :o2-limit-percent 8.0
            :load-displacement-pct 80.0 :load-displacement-max 100.0
            :bonding-grounding-confirmed? true
            :dispatched? false :discharged? false
            :jurisdiction "JPN" :status :intake}
    "vs-2" {:id "vs-2" :vessel-imo "9074729" :bill-of-lading-no "BL-AT-0002"
            :cargo-grade "arakawa-light" :bl-declared-grade "arakawa-light"
            :volume-barrels 600000 :load-port "Atlantis" :discharge-port "Yokohama"
            :bl-verified? true
            :inert-gas-o2-percent 4.0 :o2-limit-percent 8.0
            :load-displacement-pct 80.0 :load-displacement-max 100.0
            :bonding-grounding-confirmed? true
            :dispatched? false :discharged? false
            :jurisdiction "ATL" :status :intake}
    "vs-3" {:id "vs-3" :vessel-imo "9074728" :bill-of-lading-no "BL-AK-0003"
            :cargo-grade "arakawa-light" :bl-declared-grade "arakawa-light"
            :volume-barrels 600000 :load-port "Akita" :discharge-port "Yokohama"
            :bl-verified? true
            :inert-gas-o2-percent 4.0 :o2-limit-percent 8.0
            :load-displacement-pct 80.0 :load-displacement-max 100.0
            :bonding-grounding-confirmed? true
            :dispatched? false :discharged? false
            :jurisdiction "JPN" :status :intake}
    "vs-4" {:id "vs-4" :vessel-imo "9074729" :bill-of-lading-no "BL-AK-0004"
            :cargo-grade "arakawa-light" :bl-declared-grade "arakawa-light"
            :volume-barrels 600000 :load-port "Akita" :discharge-port "Yokohama"
            :bl-verified? false
            :inert-gas-o2-percent 4.0 :o2-limit-percent 8.0
            :load-displacement-pct 80.0 :load-displacement-max 100.0
            :bonding-grounding-confirmed? true
            :dispatched? false :discharged? false
            :jurisdiction "JPN" :status :intake}
    "vs-5" {:id "vs-5" :vessel-imo "9074729" :bill-of-lading-no "BL-AK-0005"
            :cargo-grade "arakawa-heavy" :bl-declared-grade "arakawa-light"
            :volume-barrels 600000 :load-port "Akita" :discharge-port "Yokohama"
            :bl-verified? true
            :inert-gas-o2-percent 4.0 :o2-limit-percent 8.0
            :load-displacement-pct 80.0 :load-displacement-max 100.0
            :bonding-grounding-confirmed? true
            :dispatched? false :discharged? false
            :jurisdiction "JPN" :status :intake}
    "vs-6" {:id "vs-6" :vessel-imo "9074729" :bill-of-lading-no "BL-AK-0006"
            :cargo-grade "arakawa-light" :bl-declared-grade "arakawa-light"
            :volume-barrels 600000 :load-port "Akita" :discharge-port "Yokohama"
            :bl-verified? true
            :inert-gas-o2-percent 4.0 :o2-limit-percent 8.0
            :load-displacement-pct 105.0 :load-displacement-max 100.0
            :bonding-grounding-confirmed? true
            :dispatched? false :discharged? false
            :jurisdiction "JPN" :status :intake}
    "vs-7" {:id "vs-7" :vessel-imo "9074729" :bill-of-lading-no "BL-AK-0007"
            :cargo-grade "arakawa-light" :bl-declared-grade "arakawa-light"
            :volume-barrels 600000 :load-port "Akita" :discharge-port "Yokohama"
            :bl-verified? true
            :inert-gas-o2-percent 10.0 :o2-limit-percent 8.0
            :load-displacement-pct 80.0 :load-displacement-max 100.0
            :bonding-grounding-confirmed? true
            :dispatched? false :discharged? false
            :jurisdiction "JPN" :status :intake}
    "vs-8" {:id "vs-8" :vessel-imo "9074729" :bill-of-lading-no "BL-AK-0008"
            :cargo-grade "arakawa-light" :bl-declared-grade "arakawa-light"
            :volume-barrels 600000 :load-port "Akita" :discharge-port "Yokohama"
            :bl-verified? true
            :inert-gas-o2-percent 4.0 :o2-limit-percent 8.0
            :load-displacement-pct 80.0 :load-displacement-max 100.0
            :bonding-grounding-confirmed? false
            :dispatched? false :discharged? false
            :jurisdiction "JPN" :status :intake}}})

;; ----------------------------- shared commit logic -----------------------------

(defn- dispatch-vessel-shipment!
  "Backend-agnostic `:vessel/mark-dispatched` -- looks up the
  vessel-shipment via the protocol and drafts the voyage-dispatch
  record, and returns {:result .. :vs-patch ..} for the caller to
  persist."
  [s vs-id]
  (let [vs (vessel-shipment s vs-id)
        seq-n (next-voyage-sequence s (:jurisdiction vs))
        result (registry/register-voyage-record vs-id (:jurisdiction vs) seq-n)]
    {:result   result
     :vs-patch {:dispatched? true
                :voyage-number (get result "voyage_number")}}))

(defn- discharge-vessel-shipment!
  "Backend-agnostic `:vessel/mark-discharged` -- looks up the
  vessel-shipment via the protocol and drafts the discharge-settlement
  record, and returns {:result .. :vs-patch ..} for the caller to
  persist."
  [s vs-id]
  (let [vs (vessel-shipment s vs-id)
        seq-n (next-discharge-sequence s (:jurisdiction vs))
        result (registry/register-discharge-record vs-id (:jurisdiction vs) seq-n)]
    {:result   result
     :vs-patch {:discharged? true
                :discharge-number (get result "discharge_number")}}))

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (vessel-shipment [_ id] (get-in @a [:vessel-shipments id]))
  (all-vessel-shipments [_] (sort-by :id (vals (:vessel-shipments @a))))
  (bl-assessment-of [_ vs-id] (get-in @a [:bl-assessments vs-id]))
  (ledger [_] (:ledger @a))
  (voyage-history [_] (:voyages @a))
  (discharge-history [_] (:discharges @a))
  (next-voyage-sequence [_ jurisdiction] (get-in @a [:voyage-sequences jurisdiction] 0))
  (next-discharge-sequence [_ jurisdiction] (get-in @a [:discharge-sequences jurisdiction] 0))
  (vessel-shipment-already-dispatched? [_ vs-id] (boolean (get-in @a [:vessel-shipments vs-id :dispatched?])))
  (vessel-shipment-already-discharged? [_ vs-id] (boolean (get-in @a [:vessel-shipments vs-id :discharged?])))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :vessel/upsert
      (swap! a update-in [:vessel-shipments (:id value)] merge value)

      :bl-assessment/set
      (swap! a assoc-in [:bl-assessments (first path)] payload)

      :vessel/mark-dispatched
      (let [vs-id (first path)
            {:keys [result vs-patch]} (dispatch-vessel-shipment! s vs-id)
            jurisdiction (:jurisdiction (vessel-shipment s vs-id))]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:voyage-sequences jurisdiction] (fnil inc 0))
                       (update-in [:vessel-shipments vs-id] merge vs-patch)
                       (update :voyages registry/append result))))
        result)

      :vessel/mark-discharged
      (let [vs-id (first path)
            {:keys [result vs-patch]} (discharge-vessel-shipment! s vs-id)
            jurisdiction (:jurisdiction (vessel-shipment s vs-id))]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:discharge-sequences jurisdiction] (fnil inc 0))
                       (update-in [:vessel-shipments vs-id] merge vs-patch)
                       (update :discharges registry/append result))))
        result)
      nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-vessel-shipments [s vessel-shipments] (when (seq vessel-shipments) (swap! a assoc :vessel-shipments vessel-shipments)) s))

(defn seed-db
  "A MemStore seeded with the demo vessel-shipment set. The deterministic default."
  []
  (->MemStore (atom (assoc (demo-data)
                           :bl-assessments {}
                           :ledger [] :voyage-sequences {} :voyages []
                           :discharge-sequences {} :discharges []))))

;; ----------------------------- DatomicStore (langchain.db) -----------------------------

(def ^:private schema
  "DataScript/Datomic-style schema: only constraint attrs are declared.
  Map/compound values (assessment payloads, ledger facts, voyage/
  discharge records) are stored as EDN strings so `langchain.db`
  doesn't expand them into sub-entities -- the same convention every
  sibling actor's store uses."
  {:vessel-shipment/id                {:db/unique :db.unique/identity}
   :assessment/vs-id                  {:db/unique :db.unique/identity}
   :ledger/seq                        {:db/unique :db.unique/identity}
   :voyage/seq                        {:db/unique :db.unique/identity}
   :discharge/seq                     {:db/unique :db.unique/identity}
   :voyage-sequence/jurisdiction      {:db/unique :db.unique/identity}
   :discharge-sequence/jurisdiction   {:db/unique :db.unique/identity}})

;; Every vessel-shipment field is stored as its own Datomic attr so a
;; governor pull reads the exact ground truth (no blob decode). Boolean
;; fields are coerced on read so a missing attr reads back as false
;; (parity with MemStore). [field-key tx-attr boolean?]
(def ^:private vessel-shipment-fields
  [[:id :vessel-shipment/id false]
   [:vessel-imo :vessel-shipment/vessel-imo false]
   [:bill-of-lading-no :vessel-shipment/bill-of-lading-no false]
   [:cargo-grade :vessel-shipment/cargo-grade false]
   [:bl-declared-grade :vessel-shipment/bl-declared-grade false]
   [:volume-barrels :vessel-shipment/volume-barrels false]
   [:load-port :vessel-shipment/load-port false]
   [:discharge-port :vessel-shipment/discharge-port false]
   [:bl-verified? :vessel-shipment/bl-verified? true]
   [:inert-gas-o2-percent :vessel-shipment/inert-gas-o2-percent false]
   [:o2-limit-percent :vessel-shipment/o2-limit-percent false]
   [:load-displacement-pct :vessel-shipment/load-displacement-pct false]
   [:load-displacement-max :vessel-shipment/load-displacement-max false]
   [:bonding-grounding-confirmed? :vessel-shipment/bonding-grounding-confirmed? true]
   [:dispatched? :vessel-shipment/dispatched? true]
   [:discharged? :vessel-shipment/discharged? true]
   [:jurisdiction :vessel-shipment/jurisdiction false]
   [:status :vessel-shipment/status false]
   [:voyage-number :vessel-shipment/voyage-number false]
   [:discharge-number :vessel-shipment/discharge-number false]])

(defn- vessel-shipment->tx [vs]
  (reduce (fn [tx [k attr _bool?]]
            (let [v (get vs k)]
              (cond-> tx (some? v) (assoc attr v))))
          {:vessel-shipment/id (:id vs)}
          vessel-shipment-fields))

(def ^:private vessel-shipment-pull (mapv second vessel-shipment-fields))

(defn- pull->vessel-shipment [m]
  (when (:vessel-shipment/id m)
    (reduce (fn [vs [k attr bool?]]
              (let [v (get m attr)]
                (cond
                  bool?        (assoc vs k (boolean v))
                  (some? v)    (assoc vs k v)
                  :else        vs)))
            {:id (:vessel-shipment/id m)}
            vessel-shipment-fields)))

(defrecord DatomicStore [conn]
  Store
  (vessel-shipment [_ id]
    (pull->vessel-shipment (d/pull (d/db conn) vessel-shipment-pull [:vessel-shipment/id id])))
  (all-vessel-shipments [_]
    (->> (d/q '[:find [?id ...] :where [?e :vessel-shipment/id ?id]] (d/db conn))
         (map #(pull->vessel-shipment (d/pull (d/db conn) vessel-shipment-pull [:vessel-shipment/id %])))
         (sort-by :id)))
  (bl-assessment-of [_ vs-id]
    (ls/dec* (d/q '[:find ?p . :in $ ?vid
                :where [?a :assessment/vs-id ?vid] [?a :assessment/payload ?p]]
              (d/db conn) vs-id)))
  (ledger [_]
    (->> (d/q '[:find ?s ?f :where [?e :ledger/seq ?s] [?e :ledger/fact ?f]] (d/db conn))
         (sort-by first)
         (mapv (comp ls/dec* second))))
  (voyage-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :voyage/seq ?s] [?e :voyage/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp ls/dec* second))))
  (discharge-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :discharge/seq ?s] [?e :discharge/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp ls/dec* second))))
  (next-voyage-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :voyage-sequence/jurisdiction ?j] [?e :voyage-sequence/next ?n]]
            (d/db conn) jurisdiction)
        0))
  (next-discharge-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :discharge-sequence/jurisdiction ?j] [?e :discharge-sequence/next ?n]]
            (d/db conn) jurisdiction)
        0))
  (vessel-shipment-already-dispatched? [s vs-id]
    (boolean (:dispatched? (vessel-shipment s vs-id))))
  (vessel-shipment-already-discharged? [s vs-id]
    (boolean (:discharged? (vessel-shipment s vs-id))))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :vessel/upsert
      (d/transact! conn [(vessel-shipment->tx value)])

      :bl-assessment/set
      (d/transact! conn [{:assessment/vs-id (first path) :assessment/payload (ls/enc payload)}])

      :vessel/mark-dispatched
      (let [vs-id (first path)
            {:keys [result vs-patch]} (dispatch-vessel-shipment! s vs-id)
            jurisdiction (:jurisdiction (vessel-shipment s vs-id))
            next-n (inc (next-voyage-sequence s jurisdiction))]
        (d/transact! conn
                     [(vessel-shipment->tx (assoc vs-patch :id vs-id))
                      {:voyage-sequence/jurisdiction jurisdiction :voyage-sequence/next next-n}
                      {:voyage/seq (count (voyage-history s)) :voyage/record (ls/enc (get result "record"))}])
        result)

      :vessel/mark-discharged
      (let [vs-id (first path)
            {:keys [result vs-patch]} (discharge-vessel-shipment! s vs-id)
            jurisdiction (:jurisdiction (vessel-shipment s vs-id))
            next-n (inc (next-discharge-sequence s jurisdiction))]
        (d/transact! conn
                     [(vessel-shipment->tx (assoc vs-patch :id vs-id))
                      {:discharge-sequence/jurisdiction jurisdiction :discharge-sequence/next next-n}
                      {:discharge/seq (count (discharge-history s)) :discharge/record (ls/enc (get result "record"))}])
        result)
      nil)
    s)
  (append-ledger! [s fact]
    (d/transact! conn [{:ledger/seq (count (ledger s)) :ledger/fact (ls/enc fact)}])
    fact)
  (with-vessel-shipments [s vessel-shipments]
    (when (seq vessel-shipments) (d/transact! conn (mapv vessel-shipment->tx (vals vessel-shipments)))) s))

(defn datomic-store
  "A DatomicStore (langchain.db backend) seeded from `data`
  ({:vessel-shipments ..}); empty when omitted."
  ([] (datomic-store {}))
  ([{:keys [vessel-shipments]}]
   (let [s (->DatomicStore (d/create-conn schema))]
     (with-vessel-shipments s vessel-shipments))))

(defn datomic-seed-db
  "A DatomicStore seeded with the demo vessel-shipment set -- the
  Datomic-backed analog of `seed-db`, used to prove protocol parity."
  []
  (datomic-store (demo-data)))
