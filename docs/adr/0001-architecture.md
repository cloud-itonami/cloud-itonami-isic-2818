# ADR-0001: PowerToolAdvisor ⊣ Power-Driven Hand Tool Plant Operations Governor architecture

## Status

Accepted. `cloud-itonami-isic-2818` promoted from `:spec` to
`:implemented` in the `kotoba-lang/industry` registry, following the
verified fresh-scaffold protocol established by prior actors in this
fleet.

## Context

`cloud-itonami-isic-2818` publishes an OSS blueprint for power-driven
hand tool (drills, saws, sanders and similar hand-held power tools)
**plant operations coordination** (production-batch product-type/
hipot-test-voltage/quantity/defect-rate data logging, motor-assembly/
housing-molding/test-bench-equipment maintenance scheduling, safety-
concern flagging, and outbound product shipment coordination). Like
every actor in this fleet, the blueprint alone is not an
implementation: this ADR records the governed-actor architecture that
promotes it to real, tested code, following the same langgraph
StateGraph + independent Governor + Phase 0->3 rollout pattern
established across the cloud-itonami fleet.

The closest domain analog is `cloud-itonami-isic-2710` (Manufacture of
electric motors, generators, transformers and electricity
distribution and control apparatus): both are back-office coordination
actors for a fixed manufacturing plant with electrically-tested,
electromechanical finished-goods output and a real physical/consumer
safety dimension, and both share the same four-op shape
(`:log-production-batch`/`:schedule-maintenance`/`:flag-safety-
concern`/`:coordinate-shipment`), the same two-entity verified/
registered gate structure (equipment for maintenance scheduling, batch
for shipment coordination), and the same permanent equipment-actuation
and certification-authority blocks. This build mirrors
`cloud-itonami-isic-2710`'s architecture closely but adapts the
hazard profile, equipment vocabulary, and product taxonomy to the
power-driven hand tool plant: its finished goods are hand-held,
portable, consumer/professional-grade tools (drills, circular saws,
jigsaws, sanders, angle grinders) rather than fixed heavy electrical
apparatus, so its equipment kinds are `:motor-assembly-line` and
`:housing-molding-press` (plus a shared `:test-bench` role) rather
than 2710's winding machine and dielectric test bench, and its routine
withstand-test field is `:hipot-test-kv` (double-insulation hipot
test, plausibility-checked 0-15 kV -- informed by IEC 60745's routine
double-insulation withstand-test practice for hand-held power tools,
which specifies test voltages in the low single-digit kV range, well
below this actor's generously-bounded ceiling) rather than 2710's
`:dielectric-test-kv` (high-voltage power-apparatus hipot test,
plausibility-checked 0-2500 kV against IEC 60076-3's lightning-impulse
withstand table). Like 2710, shipment quantity is tracked in
finished-unit UNITS (`:units`/`:quantity-units`/`:shipped-units`),
since power-driven hand tools are likewise discrete counted units
rather than a bulk weight.

This vertical shares 2710's DOMAIN-SPECIFIC permanent block: like
electric motors/generators/transformers, power-driven hand tools are
subject to electrical-safety certification regimes (e.g. UL 60745
series, IEC 60745, CE marking under the EU Low Voltage Directive).
This actor is never the certification authority -- any proposal
(regardless of op) that declares `:issue-certification? true` is a
HARD, PERMANENT, unconditional block
(`powertoolmfg.governor/certification-authority-blocked-violations`),
the same "no phase, no human override" posture as the equipment-
actuation block.

This vertical has NO pre-existing `kotoba-lang/powertoolmfg`-style
capability library to wrap (verified: no such repo exists). This build
therefore uses self-contained domain logic -- pure functions in
`powertoolmfg.registry` (equipment/batch verification, shipment-
quantity recompute, product-type validation, hipot-test-voltage
plausibility validation, defect-rate plausibility validation) are
re-verified independently by the governor, the same "ground truth, not
self-report" discipline established across prior actors (most
directly `cloud-itonami-isic-2710`'s `elecequipmfg.registry`).

This blueprint's own `:itonami.blueprint/governor` keyword,
`:power-driven-hand-tool-plant-operations-governor`, is grep-verified
UNIQUE fleet-wide (`gh search code
"power-driven-hand-tool-plant-operations-governor" --owner
cloud-itonami`, zero hits before this repo was created).

## Decision

### Decision 1: Self-contained domain logic (no external power-tool-manufacturing capability library to wrap)

Unlike actors that delegate to pre-existing domain libraries, this
power-driven-hand-tool vertical has NO pre-existing capability library
to wrap. The equipment/batch-verification / shipment-quantity /
product-type / hipot-test-voltage / defect-rate validation functions
live as pure functions in `powertoolmfg.registry` and are re-verified
independently by `powertoolmfg.governor` -- the same "ground truth,
not self-report" discipline established across prior actors (most
directly `cloud-itonami-isic-2710`'s `elecequipmfg.registry`).

### Decision 2: Coordination, not control — scope boundary at the back-office

This actor is **strictly back-office coordination** of power-driven
hand tool plant operations. It does NOT:
- Control motor-assembly, housing-molding, or test-bench equipment directly
- Make plant-safety or certification decisions (exclusive to the human plant supervisor / accredited certification body)
- Actuate motor-assembly/housing-molding/test-bench equipment
- Self-issue an electrical-safety certification mark (e.g. UL/CE)

All proposals are `:effect :propose` only. The advisor proposes; the
governor validates; escalation paths funnel to human plant-supervisor
approval. This is not a replacement for the supervisor's authority or
the certification body's authority — it is a proposal-screening and
documentation layer.

**CRITICAL SAFETY BOUNDARY**: power-driven hand tool manufacturing is
a safety-critical domain (hipot double-insulation test hazard,
electrical-safety certification, downstream consumer/worker-safety
consequence). Safety-concern flagging NEVER auto-commits. All safety
concerns escalate immediately to human review.

### Decision 3: Safety-concern escalation — always human sign-off

`:flag-safety-concern` (electrical-safety concern, motor-overheat
concern, UL/CE-compliance concern, equipment-safety concern) ALWAYS
escalates, never auto-commits. This is not a "low-stakes proposal" --
it is a circuit-breaker that must reach human authority.

### Decision 4: Two independent verified/registered gates (equipment AND batch), not one

Like `cloud-itonami-isic-2710`, this vertical has TWO entity kinds
each gating a different op: `:schedule-maintenance` independently
verifies the referenced **equipment** unit's own `:verified?`/
`:registered?` fields; `:coordinate-shipment` independently verifies
the referenced **batch**'s own `:verified?`/`:registered?` fields.
Both are the same "plant/batch record must be independently
verified/registered before any action" HARD invariant applied to the
two distinct record kinds this domain actually has.
`:coordinate-shipment` additionally independently recomputes whether a
batch's own recorded shipped-to-date unit quantity plus the
proposal's own claimed unit quantity would exceed the batch's own
recorded production quantity -- never taken on the advisor's
self-report.

### Decision 5: HARD invariants (no override)

Four HARD governor invariants (elaborated into twelve concrete checks
in `powertoolmfg.governor`, mirroring `cloud-itonami-isic-2710`'s own
elaboration of its HARD invariants into concrete checks) block
proposals and cannot be overridden by human approval:
1. Plant/batch record (equipment for maintenance, batch for shipment) must be independently verified/registered before any action is taken against it, and a shipment's quantity must independently recompute within the batch's own logged production quantity
2. Proposals must be `:effect :propose` only (never direct equipment control)
3. Direct motor-assembly/housing-molding/test-bench-equipment control, equipment actuation, or self-issued electrical-safety certification is permanently blocked
4. The op allowlist is closed — `:log-production-batch`/`:schedule-maintenance`/`:flag-safety-concern`/`:coordinate-shipment` only

## Consequences

(+) Power-driven hand tool plant operations back-office now has a
documented, governed, auditable coordination layer that funnels all
decisions through independent validation before human approval.

(+) The "coordination, not control" boundary is explicit in code: all
`:effect :propose`, all real-world actuation requires human plant-
supervisor sign-off, and no certification mark can ever be
self-issued.

(+) Scope is bounded and verifiable: four HARD invariants (elaborated
into twelve concrete governor checks) protect against scope creep into
unauthorized equipment operation, equipment actuation, or
certification self-issuance. Safety concerns are a circuit-breaker,
not a threshold.

(+) Safety-critical discipline is explicit: safety-concern flagging
cannot be rate-limited, suppressed, or auto-decided by phase gate.
Human review is mandatory.

(-) Still a simulation/proposal layer, not a real plant-operations
control system. Equipment actuation, line operation, and certification
issuance remain human-/institution-controlled via external channels.

(-) No integration with real plant-management databases (equipment
telemetry, batch tracking, freight dispatch, certification-body APIs)
— this is a standalone coordinator blueprint.

## Verification

- `cloud-itonami-isic-2818`: `kbb -M:test` green (all tests pass;
  see the superproject ADR and `kotoba-lang/industry` registry entry
  for the exact `Ran N tests containing M assertions, 0 failures, 0
  errors` output, verified from an independent fresh clone), `clojure
  -M:lint` clean, `kbb -M:dev:run` demo narrative exercises
  proposal submission, escalation, and every HARD-hold scenario
  directly (not-propose-effect, unknown-op, equipment-not-verified,
  batch-not-verified, shipment-quantity-exceeded, equipment-actuate-
  blocked, certification-authority-blocked, already-scheduled,
  invalid-product-type, invalid-hipot-test-kv, invalid-defect-rate).
- All source is `.cljc` (portable ClojureScript / JVM / nbb) — no
  JVM-only interop; the actor graph is invoked exclusively via
  `langgraph.graph/run*` (not `.invoke`, which is not cljs-portable).
- Audit ledger is append-only, all decisions are traced; every settled
  request (commit or hold) leaves exactly one ledger fact.
- `deps.edn` pins `io.github.kotoba-lang/langgraph` and
  `io.github.kotoba-lang/langchain` via `:local/root` directly in the
  top-level `:deps` (not only under a `:dev` alias), so a bare
  `kbb -M:test` resolves offline inside the monorepo checkout.
