(ns tanker.facts
  "Per-jurisdiction marine-cargo tanker-safety regulatory catalog -- the
  G2-style spec-basis table the Marine Cargo Governor checks every
  `:bill-of-lading/verify` proposal against ('did the advisor cite an
  OFFICIAL public source for this jurisdiction's tanker / inert-gas /
  ship-shore safety requirements, or did it invent one?').

  Each entry below is a REAL jurisdiction with a REAL marine tanker
  safety regime: Japan's MLIT Maritime Bureau 船舶安全法 (Ship Safety
  Act) jurisdiction over tankers plus MARPOL Annex I, the US Coast
  Guard's tank-vessel regime (33 C.F.R.) grounded in SOLAS Chapter
  II-2, the UK Maritime and Coastguard Agency's Merchant Shipping
  Regulations (SOLAS Chapter II-2), and the Norwegian Maritime
  Authority's Ship Safety and Security Act. The required-evidence set
  (bill of lading, IMO registry / vessel record, inert-gas-system
  operational record, ship-shore bonding confirmation) mirrors the
  cargo-handling and inert-gas evidence a port-state control inspector
  actually demands before a tanker is authorized to load or discharge;
  the SOLAS inert-gas O2 limit (8 vol%) lives on each vessel-shipment
  entity (`:o2-limit-percent`), operationally recorded per the SOLAS
  II-2/4.5.5 regime each of these authorities enforces.

  Coverage is reported HONESTLY (see `coverage`), the same discipline
  every sibling actor's `facts` namespace uses: a jurisdiction not in
  this table has NO spec-basis, full stop -- the advisor must not
  fabricate one, and the governor holds if it tries.")

(def catalog
  "iso3 -> requirement map. `:required-evidence` is the tanker
  cargo-handling / inert-gas evidence set (bill of lading, IMO registry
  / vessel record, IGS operational record, ship-shore bonding
  confirmation); `:legal-basis` / `:owner-authority` / `:provenance`
  are the G2 citation the governor requires before any
  `:bill-of-lading/verify` proposal can commit."
  {"JPN" {:name "Japan"
          :owner-authority "国土交通省 (MLIT) 海事局"
          :legal-basis "船舶安全法 (Ship Safety Act); MARPOL Annex I (prevention of pollution by oil)"
          :provenance "https://www.mlit.go.jp/common/001383958.pdf"
          :required-evidence ["bill-of-lading"
                              "IMO registry / vessel record"
                              "inert-gas-system (IGS) operational record"
                              "ship-shore bonding confirmation"]}
   "USA" {:name "United States"
          :owner-authority "U.S. Coast Guard (USCG)"
          :legal-basis "Tank Vessels (33 C.F.R.); SOLAS Chapter II-2 (fire protection / inert gas)"
          :provenance "https://www.ecfr.gov/current/title-33"
          :required-evidence ["bill-of-lading"
                              "IMO registry / vessel record"
                              "inert-gas-system (IGS) operational record"
                              "ship-shore bonding confirmation"]}
   "GBR" {:name "United Kingdom"
          :owner-authority "Maritime and Coastguard Agency (MCA)"
          :legal-basis "Merchant Shipping Regulations (SOLAS Chapter II-2)"
          :provenance "https://www.gov.uk/government/organisations/maritime-and-coastguard-agency"
          :required-evidence ["bill-of-lading"
                              "IMO registry / vessel record"
                              "inert-gas-system (IGS) operational record"
                              "ship-shore bonding confirmation"]}
   "NOR" {:name "Norway"
          :owner-authority "Norwegian Maritime Authority (NMA / Sjøfartsdirektoratet)"
          :legal-basis "Norwegian Ship Safety and Security Act; SOLAS"
          :provenance "https://www.sdir.no/en/"
          :required-evidence ["bill-of-lading"
                              "IMO registry / vessel record"
                              "inert-gas-system (IGS) operational record"
                              "ship-shore bonding confirmation"]}})

(defn spec-basis
  "The jurisdiction's requirement map, or nil -- nil means NO spec-basis,
  and the governor must hold any proposal that tries to dispatch a
  voyage or settle a discharge on it."
  [iso3]
  (get catalog iso3))

(defn coverage
  "Honest coverage report: how many of the requested jurisdictions
  actually have a spec-basis entry. Never report a missing jurisdiction
  as covered."
  ([] (coverage (keys catalog)))
  ([iso3s]
   (let [have (filter catalog iso3s)
         missing (remove catalog iso3s)]
     {:requested (count iso3s)
      :covered (count have)
      :covered-jurisdictions (vec (sort have))
      :missing-jurisdictions (vec (sort missing))
      :note (str "cloud-itonami-isic-5020 R0: " (count catalog)
                 " jurisdictions seeded with an official spec-basis. "
                 "This is a starting catalog, not a survey of all ~194 "
                 "jurisdictions -- extend `tanker.facts/catalog`, "
                 "never fabricate a jurisdiction's requirements.")})))

(defn required-evidence-satisfied?
  "Does `submitted` (a set/coll of evidence keywords or strings) satisfy
  every evidence item listed for `iso3`? Missing spec-basis -> never
  satisfied."
  [iso3 submitted]
  (when-let [{:keys [required-evidence]} (spec-basis iso3)]
    (let [need (count required-evidence)
          have (count (filter (set submitted) required-evidence))]
      (= need have))))

(defn evidence-checklist [iso3]
  (:required-evidence (spec-basis iso3) []))
