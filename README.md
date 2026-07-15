# cloud-itonami-isic-2818: Manufacture of power-driven hand tools

Open Business Blueprint for **ISIC 2818**: manufacture of power-driven hand tools — an autonomous "actor" (LLM advisor behind an independent Governor, langgraph-clj StateGraph, append-only audit ledger) that coordinates back-office **power-tool-plant operations**: production-batch data logging (product-type/hipot-test-voltage/quantity/defect-rate), motor-assembly/housing-molding/test-bench-equipment maintenance scheduling, safety-concern flagging, and outbound product shipment coordination.

This repository designs a forkable OSS business for power-tool-plant
operations: run by a qualified operator so a plant keeps its own
operating records instead of renting a closed SaaS.

## Scope: plant operations coordination, not assembly/molding/testing-line control

ISIC 2818 covers the **manufacturing plant** that assembles the motor, molds the housing, and tests the finished power-driven hand tool (drills, saws, sanders and similar hand-held power tools) — including hipot double-insulation withstand testing — before shipment. This actor coordinates the back-office record keeping around that plant — it never touches the motor-assembly/housing-molding/test-bench equipment directly, and it is never an electrical-safety certification authority (e.g. UL/CE compliance marks).

## What this actor does

Proposes **plant operations coordination**, not equipment operation:
- `:log-production-batch` — assembly/test batch, output-quality/test-result data logging (administrative, not an operational decision)
- `:schedule-maintenance` — assembly/test-bench-equipment maintenance scheduling proposal
- `:flag-safety-concern` — surface an electrical-safety/motor-overheat/UL-CE-compliance concern (always escalates)
- `:coordinate-shipment` — outbound product shipment coordination proposal

## What this actor does NOT do

**CRITICAL SCOPE BOUNDARY — this is a safety-critical domain**
(motor-assembly/housing-molding/test-bench line equipment, hipot
double-insulation test hazard, electrical-safety certification,
downstream consumer/worker-safety consequence):

- Does NOT control motor-assembly, housing-molding, or test-bench equipment directly
- Does NOT make plant-safety or certification decisions (that's the plant supervisor's / certification body's exclusive human/institutional authority)
- Does NOT actuate motor-assembly/housing-molding/test-bench equipment (human plant supervisor decides)
- Does NOT self-issue an electrical-safety certification mark (e.g. UL/CE — the accredited certification body's exclusive authority — a PERMANENT, unconditional block)
- ONLY proposes/coordinates operations back-office; all actuation and certification requires explicit human/institutional authority
- Safety-concern flagging ALWAYS escalates — never auto-decided, no confidence threshold or phase below escalation

## Architecture

Classic governed-actor pattern (`powertoolmfg.operation/build`, a langgraph-clj StateGraph):
1. **`powertoolmfg.advisor`** (sealed intelligence node, `PowerToolAdvisor`): proposes decisions only, never commits
2. **`powertoolmfg.governor`** (independent, `Power-Driven Hand Tool Plant Operations Governor`): validates against domain rules, re-derived from `powertoolmfg.registry`'s pure functions and `powertoolmfg.store`'s SSoT -- never trusts the advisor's own self-report
   - HARD invariants (always `:hold`, no override):
     - Plant/batch record must be independently verified/registered (`:verified?` AND `:registered?`) before any action is taken against it (equipment before maintenance scheduling, batch before shipment coordination)
     - The request's own `:effect` must be `:propose` (never a direct-write bypass)
     - `:op` must be in the closed four-op allowlist
     - The proposal's own `:effect` must be one of the four propose-shaped effects (no direct motor-assembly/housing-molding/test-bench-equipment control)
     - Directly actuating motor-assembly/housing-molding/test-bench equipment (`:actuate-equipment? true`) is a PERMANENT, unconditional block
     - Self-issuing an electrical-safety certification mark (`:issue-certification? true`, any op) is a PERMANENT, unconditional block
     - A shipment may not push a batch's own recorded shipped quantity past its own logged production quantity (independently recomputed)
     - No double-scheduling the same maintenance record
     - No fabricated `:product-type` value on a production-batch patch
     - No physically implausible `:hipot-test-kv` value on a production-batch patch
     - No physically implausible `:defect-rate-percent` value on a production-batch patch
   - ESCALATE (always human sign-off, overridable by a human):
     - `:flag-safety-concern` always escalates, regardless of confidence
     - Low-confidence proposals
3. **`powertoolmfg.phase`** (Phase 0->3 rollout): `:schedule-maintenance`/`:flag-safety-concern`/`:coordinate-shipment` are NEVER in any phase's `:auto` set (permanent, matching the governor's own posture); only `:log-production-batch` may auto-commit at phase 3 when clean
4. **`powertoolmfg.store`** (append-only audit ledger + SSoT): a single `MemStore` backend behind a `Store` protocol (see ns docstring for why a second Datomic-backed backend is out of scope for this build)

## Development

```bash
# Run tests (top-level deps.edn already pins langgraph+langchain local/root)
clojure -M:test

# Run tests via the workspace :dev override alias (equivalent, kept for sibling-repo parity)
clojure -M:dev:test

# Run the demo
clojure -M:dev:run

# Lint
clojure -M:lint
```

## Status

`:implemented` — `governor.cljc`/`store.cljc`/`advisor.cljc`/`registry.cljc` + `deps.edn` complete the module set; tests green, demo runnable, langgraph-clj integration verified.

## License

AGPL-3.0-or-later
