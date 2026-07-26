(ns powertoolmfg.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300,
  Wave5 rollout ledger seq 7): this repo previously had NO demo page and
  no generator at all. This namespace drives the REAL actor stack
  (`powertoolmfg.operation` -> `powertoolmfg.governor` -> `powertoolmfg.store`)
  through a scenario adapted from this repo's own `powertoolmfg.sim` demo
  driver (`clojure -M:dev:run`, confirmed to run correctly against the real
  seeded batch/equipment directory before this file was written -- unlike
  `cloud-itonami-isic-851`'s `schoolops.sim`, this repo's own sim driver
  uses ids that DO match `powertoolmfg.store/sample-batches` /
  `powertoolmfg.store/sample-equipment`, and every disposition it produces
  matches `powertoolmfg.governor`'s own documented rules exactly, so it
  was safe to reuse rather than author from scratch), trimmed to a
  representative subset (one clean phase-3 auto-commit, one always-escalate
  maintenance schedule, one always-escalate safety-concern flag, one
  always-escalate shipment coordination, and three distinct HARD-hold
  reasons) and rendered deterministically -- no invented numbers, no
  timestamps in the page content, byte-identical across reruns against the
  same seed (verified by diffing two consecutive runs).

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [jp-go-dds.skin]
            [clojure.string :as str]
            [powertoolmfg.store :as store]
            [powertoolmfg.operation :as op]
            [langgraph.graph :as g]))

(def ^:private operator
  {:actor-id "op-1" :actor-role :plant-operations-coordinator :phase 3})

(defn- exec! [actor tid request]
  (g/run* actor {:request request :context operator} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}}
          {:thread-id tid :resume? true}))

(defn run-demo!
  "Runs a fresh seeded store through a scenario mixing every disposition
  this actor can reach: batch-001 clears a clean production-batch intake
  patch (phase-3 auto-commit, no physical/financial risk); a maintenance
  window (mnt-1) scheduled against assy-001 (verified+registered
  motor-assembly line) ALWAYS escalates -- `:schedule-maintenance` is
  permanently absent from every phase's `:auto` set -- approved by a
  human plant supervisor; a safety concern (concern-1) flagged against
  assy-001 ALWAYS escalates (governor `high-stakes`
  `:coordination/safety-concern`) -- approved; a shipment (ship-1)
  coordinating 50 units of batch-001 (within its own logged production
  quantity) ALWAYS escalates (shipment coordination is also never
  auto-eligible) -- approved. Then three distinct HARD-hold reasons that
  never reach a human: a maintenance window (mnt-2) against molder-002
  (UNVERIFIED/unregistered housing-molding press) HARD-holds on
  `:equipment-not-verified`; a shipment (ship-2) against batch-003
  (UNVERIFIED/unregistered batch) HARD-holds on `:batch-not-verified`;
  a shipment (ship-3) claiming 100 units of batch-002 (300 logged units,
  280 already shipped -- 100 more would exceed the batch's own recorded
  quantity) HARD-holds on `:shipment-quantity-exceeded`. Returns the
  resulting store -- every field read by `render` below is real
  governor/store output, not a hand-typed copy."
  []
  (let [db (store/mem-store)
        _ (store/sample-data! db)
        actor (op/build db)]

    (exec! actor "b1-intake" {:op :log-production-batch :effect :propose :subject "batch-001"
                               :patch {:product-type :drill :last-assessed "2026-07-15"}})

    (exec! actor "mnt1-schedule" {:op :schedule-maintenance :effect :propose :subject "mnt-1"
                                   :value {:equipment-id "assy-001" :maintenance-type :spindle-inspection
                                           :scheduled-date "2026-08-01" :actuate-equipment? false}})
    (approve! actor "mnt1-schedule")

    (exec! actor "concern1-flag" {:op :flag-safety-concern :effect :propose :subject "concern-1"
                                   :value {:equipment-id "assy-001" :severity :moderate
                                           :description "モータ過熱の異常兆候、絶縁耐圧試験時の異音"}})
    (approve! actor "concern1-flag")

    (exec! actor "ship1-coordinate" {:op :coordinate-shipment :effect :propose :subject "ship-1"
                                      :value {:batch-id "batch-001" :units 50.0
                                              :destination "buyer-yard-north"}})
    (approve! actor "ship1-coordinate")

    (exec! actor "mnt2-schedule" {:op :schedule-maintenance :effect :propose :subject "mnt-2"
                                   :value {:equipment-id "molder-002" :maintenance-type :calibration
                                           :scheduled-date "2026-08-01" :actuate-equipment? false}})

    (exec! actor "ship2-coordinate" {:op :coordinate-shipment :effect :propose :subject "ship-2"
                                      :value {:batch-id "batch-003" :units 100.0
                                              :destination "buyer-yard-south"}})

    (exec! actor "ship3-coordinate" {:op :coordinate-shipment :effect :propose :subject "ship-3"
                                      :value {:batch-id "batch-002" :units 100.0
                                              :destination "buyer-yard-east"}})
    db))

;; ----------------------------- rendering -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- last-fact-for [ledger subject-id]
  (last (filter #(= (:subject %) subject-id) ledger)))

(defn- status-cell [ledger subject-id]
  (let [f (last-fact-for ledger subject-id)]
    (cond
      (nil? f) "<span class=\"muted\">no activity</span>"
      (= :committed (:t f)) "<span class=\"ok\">committed</span>"
      (= :approval-granted (:t f)) "<span class=\"ok\">approved &amp; committed</span>"
      (= :governor-hold (:t f))
      (let [rule (-> f :violations first :rule)]
        (str "<span class=\"critical\">HARD hold &middot; " (esc (name (or rule :unknown))) "</span>"))
      (= :approval-requested (:t f)) "<span class=\"warn\">awaiting approval</span>"
      :else "<span class=\"muted\">in progress</span>")))

(defn- batch-row [ledger {:keys [id product-type model hipot-test-kv quantity-units
                                  defect-rate-percent verified? registered? shipped-units]}]
  (format "        <tr><td>%s</td><td>%s</td><td>%s</td><td>%s kV</td><td>%s / %s units</td><td>%s%%</td><td>%s</td><td>%s</td></tr>"
          (esc id) (esc (name (or product-type :n-a))) (esc model) (esc hipot-test-kv)
          (esc shipped-units) (esc quantity-units) (esc defect-rate-percent)
          (if (and verified? registered?) "<span class=\"ok\">verified &amp; registered</span>"
              "<span class=\"warn\">unverified / unregistered</span>")
          (status-cell ledger id)))

(defn- equipment-row [{:keys [id kind verified? registered? last-maintenance-date
                               last-scheduled-maintenance-date]}]
  (format "        <tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc id) (esc (name (or kind :n-a)))
          (if (and verified? registered?) "<span class=\"ok\">verified &amp; registered</span>"
              "<span class=\"warn\">unverified / unregistered</span>")
          (esc (or last-maintenance-date "never"))
          (esc (or last-scheduled-maintenance-date "none scheduled"))))

(defn- ledger-row [{:keys [t op subject disposition basis]}]
  (format "        <tr><td>%s</td><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
          (esc (name t)) (esc (name (or op :n-a))) (esc subject)
          (esc (or (some->> basis (map name) (str/join ", ")) (some-> disposition name) ""))))

(def ^:private action-gate-rows
  ;; Static description of this actor's own op contract (README `Ops`
  ;; table, `powertoolmfg.governor`/`powertoolmfg.phase`) --
  ;; documentation of fixed behavior, not runtime telemetry, so it is
  ;; legitimately hand-described rather than derived from a live run.
  ["        <tr><td><code>:log-production-batch</code></td><td><span class=\"ok\">phase-3 auto-commit when clean, no physical/financial risk</span></td></tr>"
   "        <tr><td><code>:schedule-maintenance</code></td><td><span class=\"warn\">ALWAYS human approval &middot; equipment verified/registered ground-truth checked, actuation permanently blocked</span></td></tr>"
   "        <tr><td><code>:flag-safety-concern</code></td><td><span class=\"warn\">ALWAYS human approval &middot; high-stakes, never auto regardless of phase</span></td></tr>"
   "        <tr><td><code>:coordinate-shipment</code></td><td><span class=\"warn\">ALWAYS human approval &middot; batch verified/registered ground-truth checked, shipment quantity independently recomputed</span></td></tr>"])

(defn render
  "Renders the full operator-console.html document from a store `db`
  that has already run `run-demo!` (or any other real scenario)."
  [db]
  (let [ledger (vec (store/ledger db))
        batches (store/all-batches db)
        equipment (store/all-equipment db)
        batch-rows (str/join "\n" (map (partial batch-row ledger) batches))
        equipment-rows (str/join "\n" (map equipment-row equipment))
        ledger-rows (str/join "\n" (map ledger-row ledger))]
    (str
     "<html><head><meta charset=\"utf-8\"><title>cloud-itonami-isic-2818 &middot; power-driven hand tool manufacturing</title><style>"
   (jp-go-dds.skin/dds+skin)
   "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Power-driven hand tool manufacturing plant operations (ISIC 2818) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · maintenance/shipment actuation always human-approved</span>\n"
     "</header>\n"
     "<main>\n"
     "  <section class=\"card\">\n"
     "    <h2>Production batches</h2>\n"
     "    <p class=\"muted\">Demo snapshot — build-time-generated from <code>powertoolmfg.store</code> via <code>powertoolmfg.render-html</code> (<code>clojure -M:dev:render-html</code>), regenerated nightly.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Batch</th><th>Product type</th><th>Model</th><th>Hipot test</th><th>Shipped / Quantity</th><th>Defect rate</th><th>QC status</th><th>Last op status</th></tr></thead>\n"
     "      <tbody>\n"
     batch-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Plant equipment</h2>\n"
     "    <p class=\"muted\">Motor-assembly / housing-molding / test-bench units. Maintenance may only ever be scheduled (drafted), never actuated by this actor.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Equipment</th><th>Kind</th><th>Commission status</th><th>Last maintenance date</th><th>Last scheduled maintenance date</th></tr></thead>\n"
     "      <tbody>\n"
     equipment-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Action gate (Power-Driven Hand Tool Plant Operations Governor)</h2>\n"
     "    <p class=\"muted\">HARD holds cannot be overridden. Equipment/batch verified &amp; registered status and shipment quantities are independently recomputed, never trusted from the proposal; motor-assembly/housing-molding/test-bench-equipment actuation and self-issued electrical-safety certification are permanently blocked.</p>\n"
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
     "      <thead><tr><th>Fact</th><th>Op</th><th>Subject</th><th>Basis</th></tr></thead>\n"
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
        html (render db)]
    (spit out html)
    (println "wrote" out "(" (count (store/ledger db)) "ledger facts,"
             (count (store/maintenance-history db)) "maintenance drafts,"
             (count (store/shipment-history db)) "shipment drafts )")))
