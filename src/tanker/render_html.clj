(ns tanker.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300,
  Wave1 Lane A-no-demo): this repo previously had a hand-typed
  `docs/samples/operator-console.html` with no generator. This namespace
  drives the REAL actor stack (`tanker.operation` -> `tanker.governor`
  -> `tanker.store`) through a scenario adapted from this repo's own
  `tanker.sim` demo driver (`clojure -M:dev:run`, confirmed BEFORE
  writing this file to produce a sensible ledger against the real
  seeded vessel-shipment ids `vs-1`..`vs-8` -- ids DO match
  `tanker.store/demo-data`, so it was safe to reuse rather than author
  from scratch), trimmed to a representative subset (one full
  intake->verify->dispatch->settle lifecycle, and several distinct
  HARD-hold reasons including the HSE-CRITICAL inert-gas O2 gate) and
  rendered deterministically -- no invented numbers, no timestamps in
  the page content, byte-identical across reruns against the same seed
  (verify by diffing two consecutive runs).

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [jp-go-dds.skin]
            [clojure.string :as str]
            [tanker.store :as store]
            [tanker.operation :as op]
            [tanker.phase :as phase]
            [tanker.governor :as governor]
            [langgraph.graph :as g]))

(def ^:private operator
  {:actor-id "op-1" :actor-role :tanker-operator :phase 3})

(defn- exec! [actor tid request]
  (g/run* actor {:request request :context operator} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}}
          {:thread-id tid :resume? true}))

(defn- assess!
  "Walks `subject` through bill-of-lading verify -> approve, leaving a
  bl-assessment on file. Uses distinct thread-ids per call site by
  suffixing `tid-prefix`."
  [actor tid-prefix subject]
  (exec! actor (str tid-prefix "-assess")
         {:op :bill-of-lading/verify :subject subject})
  (approve! actor (str tid-prefix "-assess")))

(defn run-demo!
  "Runs a fresh seeded store through a scenario mixing every
  disposition this actor can reach: vs-1 clears a full lifecycle --
  vessel intake (auto-commit clean at phase 3, no capital risk), a
  bill-of-lading / cargo-evidence assessment (phase-gated -- not auto-
  eligible -- approved), a voyage dispatch (ALWAYS escalates --
  `:voyage/dispatch` is permanently high-stakes, never auto at any
  phase -- approved) and a discharge settlement (ALWAYS escalates --
  `:discharge/settle`, same posture -- approved); vs-2 HARD-holds a
  bill-of-lading assessment with no official spec-basis for its
  (deliberately unregistered) jurisdiction ATL; vs-3 HARD-holds a
  voyage dispatch on an invalid IMO check digit; vs-4 HARD-holds a
  voyage dispatch on an unverified bill of lading; vs-7 HARD-holds a
  voyage dispatch on inert-gas O2 above the SOLAS 8 vol% ceiling
  (HSE-CRITICAL). Every HARD hold never reaches a human. Returns the
  resulting store -- every field read by `render` below is real
  governor/store output, not a hand-typed copy."
  []
  (let [db (store/seed-db)
        actor (op/build db)]
    (exec! actor "t1-intake" {:op :vessel/intake :subject "vs-1"
                              :patch {:id "vs-1" :bill-of-lading-no "BL-AK-0001"}})

    (exec! actor "t1-assess" {:op :bill-of-lading/verify :subject "vs-1"})
    (approve! actor "t1-assess")

    (exec! actor "t1-dispatch" {:op :voyage/dispatch :subject "vs-1"})
    (approve! actor "t1-dispatch")

    (exec! actor "t1-settle" {:op :discharge/settle :subject "vs-1"})
    (approve! actor "t1-settle")

    (exec! actor "t2-assess" {:op :bill-of-lading/verify :subject "vs-2"})

    (assess! actor "t3pre" "vs-3")
    (exec! actor "t3-dispatch" {:op :voyage/dispatch :subject "vs-3"})

    (assess! actor "t4pre" "vs-4")
    (exec! actor "t4-dispatch" {:op :voyage/dispatch :subject "vs-4"})

    (assess! actor "t7pre" "vs-7")
    (exec! actor "t7-dispatch" {:op :voyage/dispatch :subject "vs-7"})
    db))

;; ----------------------------- rendering -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- last-fact-for [ledger vs-id]
  (last (filter #(= (:subject %) vs-id) ledger)))

(defn- status-cell [ledger vs-id]
  (let [f (last-fact-for ledger vs-id)]
    (cond
      (nil? f) "<span class=\"muted\">no activity</span>"
      (= :committed (:t f)) "<span class=\"ok\">committed</span>"
      (= :approval-granted (:t f)) "<span class=\"ok\">approved &amp; committed</span>"
      (= :governor-hold (:t f))
      (let [rule (-> f :violations first :rule)]
        (str "<span class=\"critical\">HARD hold &middot; " (esc (name (or rule :unknown))) "</span>"))
      (= :approval-requested (:t f)) "<span class=\"warn\">awaiting approval</span>"
      :else "<span class=\"muted\">in progress</span>")))

(defn- lifecycle-cell [{:keys [dispatched? discharged?]}]
  (cond
    discharged? "<span class=\"ok\">dispatched &amp; discharged</span>"
    dispatched? "<span class=\"warn\">dispatched, discharge open</span>"
    :else "<span class=\"muted\">intake</span>"))

(defn- vessel-row [ledger {:keys [id vessel-imo load-port discharge-port
                                  jurisdiction bill-of-lading-no] :as vs}]
  (format "        <tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc id) (esc vessel-imo)
          (esc (str load-port " → " discharge-port))
          (esc bill-of-lading-no) (esc jurisdiction)
          (lifecycle-cell vs)
          (status-cell ledger id)))

(defn- ledger-row [{:keys [t op subject disposition basis]}]
  (format "        <tr><td>%s</td><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
          (esc (name t)) (esc (name (or op :n-a))) (esc subject)
          (esc (or (some->> basis (map name) (str/join ", ")) (some-> disposition name) ""))))

(def ^:private action-gate-rows
  ;; Static description of this actor's own closed op contract
  ;; (README Ops, `tanker.governor`/`tanker.phase`) -- documentation of
  ;; fixed behavior, not runtime telemetry, so it is legitimately
  ;; hand-described rather than derived from a live run.
  ["        <tr><td><code>:vessel/intake</code></td><td><span class=\"ok\">phase-3 auto-commit when clean, no capital risk</span></td></tr>"
   "        <tr><td><code>:bill-of-lading/verify</code></td><td><span class=\"warn\">phase-3: human approval (not auto-eligible) · HARD on no-spec-basis</span></td></tr>"
   "        <tr><td><code>:voyage/dispatch</code></td><td><span class=\"warn\">ALWAYS human approval &middot; never auto at any phase &middot; HARD on IMO / B/L / grade / DWT / inert-gas O2 / bonding / double-dispatch</span></td></tr>"
   "        <tr><td><code>:discharge/settle</code></td><td><span class=\"warn\">ALWAYS human approval &middot; never auto at any phase &middot; HSE-CRITICAL inert-gas O2 re-checked · double-discharge guard</span></td></tr>"])

(defn render
  "Renders the full operator-console.html document from a store `db`
  that has already run `run-demo!` (or any other real scenario)."
  [db]
  (let [ledger (vec (store/ledger db))
        vessels (store/all-vessel-shipments db)
        vessel-rows (str/join "\n" (map (partial vessel-row ledger) vessels))
        ledger-rows (str/join "\n" (map ledger-row ledger))
        hard-holds (count (filter #(= :governor-hold (:t %)) ledger))]
    (str
     "<html><head><meta charset=\"utf-8\"><title>cloud-itonami-isic-5020 &middot; water-freight-tanker</title><style>"
     (jp-go-dds.skin/dds+skin)
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Water freight transport — tanker (ISIC 5020) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · voyage dispatch/discharge settle always human-approved · phase "
     phase/default-phase " (" (esc (:label (get phase/phases phase/default-phase))) ")</span>\n"
     "</header>\n"
     "<main>\n"
     "  <section class=\"card\">\n"
     "    <h2>Vessel-shipments</h2>\n"
     "    <p class=\"muted\">Demo snapshot — build-time-generated from <code>tanker.store</code> via <code>tanker.render-html</code> (<code>clojure -M:dev:render-html</code>). HARD holds this run: "
     hard-holds ".</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Shipment</th><th>IMO</th><th>Load → Discharge</th><th>B/L</th><th>Jurisdiction</th><th>Lifecycle</th><th>Last op status</th></tr></thead>\n"
     "      <tbody>\n"
     vessel-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Action gate (Marine Cargo Governor)</h2>\n"
     "    <p class=\"muted\">HARD holds cannot be overridden. IMO check digit, B/L verification, cargo-grade match, DWT load limit, SOLAS inert-gas O2 ceiling and ship-shore bonding are re-checked at the point of the act, never trusted from the proposal alone. The inert-gas O2 gate is HSE-CRITICAL and re-verified at BOTH dispatch and discharge. High-stakes ops: "
     (esc (str/join ", " (map name governor/high-stakes))) ".</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Gate</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" action-gate-rows) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Audit ledger (this run)</h2>\n"
     "    <p class=\"muted\">Append-only decision-fact log — every proposal, hold and commit this scenario produced.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Fact</th><th>Op</th><th>Shipment</th><th>Basis</th></tr></thead>\n"
     "      <tbody>\n"
     ledger-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "</main>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        db (run-demo!)
        html (render db)
        parent (.getParentFile (java.io.File. out))]
    (when parent (.mkdirs parent))
    (spit out html)
    (println "wrote" out "(" (count (store/ledger db)) "ledger facts,"
             (count (store/voyage-history db)) "voyage dispatches,"
             (count (store/discharge-history db)) "discharge settlements,"
             (count (filter #(= :governor-hold (:t %)) (store/ledger db))) "HARD holds )")))
