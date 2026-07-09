# cloud-itonami-isic-5020

Open Business Blueprint for **ISIC Rev.5 5020**: Water freight
transport (tanker) -- vessel-shipment intake, per-jurisdiction tanker
/ inert-gas / ship-shore regulatory assessment, laden voyage
dispatch, and cargo discharge settlement for a community operator.

This repository publishes a marine-tanker actor -- vessel-shipment
intake, per-jurisdiction marine-cargo safety regulatory assessment,
voyage dispatch and discharge settlement -- as an OSS business that
any qualified operator can fork, deploy, run, improve and sell, so a
regional tanker operator never surrenders cargo-handling, inert-gas
and bill-of-lading data to a closed fleet-management / chartering
SaaS.

Built on this workspace's
[`langgraph`](https://github.com/kotoba-lang/langgraph)
StateGraph runtime (portable `.cljc`, supervised superstep loop,
interrupts, Datomic/in-mem checkpoints) -- the same actor pattern as
every prior actor in this fleet -- here it is **TankerAdvisor ⊣ Marine
Cargo Governor**. This blueprint's own `:itonami.blueprint/governor`
keyword, `:marine-cargo-governor`, is a UNIQUE keyword fleet-wide
(grep-verified: no other blueprint declares it) -- a fresh,
independent build.

**Unlike `cloud-itonami-isic-4920` (which wraps a pre-existing
bespoke capability library `kotoba-lang/logistics`), this vertical is
SELF-CONTAINED**: there is no `kotoba-lang/maritime` to delegate
tanker-safety validation to, so the IMO-number structural check (the
SOLAS / IMO resolution A.600(15) 7-digit check-digit scheme) and the
SOLAS inert-gas O2 check (8 vol% per SOLAS II-2/4.5.5) live as pure
functions in `tanker.registry` and are re-verified independently by
the governor, rather than wrapping an external capability library's
own validated function.

> **Why an actor layer at all?** An LLM is great at drafting a
> vessel-shipment summary, normalizing records, and reading a tank
> atmosphere gauge -- but it has **no notion of which jurisdiction's
> tanker / inert-gas / ship-shore law is official, no license to
> dispatch a real laden tanker voyage or settle a real cargo
> discharge, and no way to know on its own whether the vessel's IMO
> number actually passes its 7-digit check-digit validation, whether
> the bill of lading is actually verified, whether the B/L declared
> cargo grade actually matches the loaded grade, whether the load
> displacement actually stays within the vessel's safe DWT limit,
> whether the cargo-tank atmosphere is actually inerted below the
> SOLAS 8 vol% O2 ceiling, or whether the ship-shore bonding is
> actually confirmed**. Letting it dispatch a voyage or settle a
> discharge directly invites fabricated regulatory citations, a laden
> tanker sailing with an unverified bill of lading and a
> non-inerted tank atmosphere, and a discharge starting into a
> flammable ullage space -- exposing the crew and the port to a
> catastrophic cargo-tank explosion and the operator to real
> liability, for whoever runs it. This project seals the
> TankerAdvisor into a single node and wraps it with an independent
> **Marine Cargo Governor**, a human **approval workflow**, and an
> immutable **audit ledger**.

## Scope: what this actor does and does not do

This actor covers vessel-shipment intake through tanker / inert-gas /
ship-shore regulatory assessment, laden voyage dispatch and cargo
discharge settlement. It does **not**, by itself, hold any charter
party, flag-state, or port-state operating authority required to run
a marine-tanker business in a given jurisdiction, and it does not
claim to. It also does not perform the actual physical cargo handling
or vessel navigation itself, or optimize the voyage plan -- voyage
routing and fleet-schedule optimization (the blueprint's own
`:optimization` technology) is a follow-up slice, not in this R0.
Whoever deploys and operates a live instance (a qualified tanker
operator / master) supplies any jurisdiction-specific operating
authority, the real tanker-loading / valve-robot dispatch integration
and the real AIS / VTS / cargo-accounting integrations, and bears
that jurisdiction's liability -- the software supplies the governed,
spec-cited, audited execution scaffold so that operator does not
have to build the compliance layer from scratch.

### Actuation

**Dispatching a real laden tanker voyage and settling a real cargo
discharge are never autonomous, at any phase, by construction.** Two
independent layers enforce this (`tanker.governor`'s `:voyage/
dispatch`/`:discharge/settle` high-stakes gate and `tanker.phase`'s
phase table, which never puts either op in any phase's `:auto` set)
-- see `tanker.phase`'s docstring and `test/tanker/phase_test.clj`'s
`voyage-dispatch-never-auto-at-any-phase`/`discharge-settle-never-
auto-at-any-phase`. The actor may draft, check and recommend; a
human tanker operator / master is always the one who actually
dispatches a laden voyage or settles a discharge. Grounded in
marine-cargo safety doctrine (the same discipline every regulator in
`tanker.facts` codifies: a real voyage dispatch and a real discharge
settlement are human sign-off acts) -- a genuine DUAL-actuation
shape, applied SEQUENTIALLY to the SAME vessel-shipment (dispatch
first, discharge settlement later), unlike `retailops`/4711's own
`:kind`-distinguished alternative-action shape.

## The core contract

```
vessel-shipment intake + jurisdiction facts (tanker.facts, spec-cited)
        |
        v
   ┌───────────────────────┐   proposal      ┌───────────────────────────┐
   │ TankerAdvisor         │ ─────────────▶ │ Marine Cargo Governor      │  (independent system)
   │ (sealed)              │  + citations    │ spec-basis · evidence-     │
   └───────────────────────┘                 │ incomplete · imo-number-   │
          │                 commit ◀┼ invalid (check digit) · bl-    │
          │                         │ unverified · cargo-grade-     │
    record + ledger        escalate ┼ mismatch · vessel-overload ·  │
          │              (ALWAYS for│ inert-gas-o2-excessive (HSE-  │
          │       :voyage/dispatch/ │ CRITICAL, BOTH dispatch AND   │
          │       :discharge/settle)│ discharge) · bonding-grounding│
          ▼                          │ -unconfirmed · already-       │
      human approval                 │ dispatched · already-discharged│
                                      └───────────────────────────┘
```

**The TankerAdvisor never dispatches a voyage or settles a discharge
the Marine Cargo Governor would reject, and never does so without a
human sign-off.** Hard violations (fabricated regulatory
requirements; unsupported evidence; an invalid IMO check digit; an
unverified bill of lading; a cargo-grade mismatch; a load
displacement above the DWT limit; an inert-gas O2 above the SOLAS 8
vol% ceiling; an unconfirmed ship-shore bonding; a double dispatch
or discharge) force **hold** and *cannot* be approved past; a clean
dispatch/discharge proposal still always routes to a human.

## Run

```bash
clojure -M:dev:run     # walk one clean dispatch + discharge lifecycle, plus eight HARD-hold cases, through the actor
clojure -M:dev:test    # governor contract · phase invariants · store parity · registry conformance · facts coverage
clojure -M:lint        # clj-kondo (errors fail; CI mirrors this)
```

## Robotics premise

All cloud-itonami verticals are designed on the premise that a
**robot performs the physical domain work**. Here an autonomous
tanker-loading / valve robot performs the physical act of cargo
handling (loading, inerting, and eventually discharging a tanker's
cargo tanks, and turning the manifold valves), under the actor,
gated by the independent **Marine Cargo Governor**. The governor
never dispatches hardware itself: a dispatch-clearing action must
have cleared the same sign-off a human tanker operator / master
would need. This restates the fleet-wide robotics premise three ways
(ADR-2607011000): the blueprint declares `:robotics true`, the
README names the robot that performs the physical act, and the
Marine Cargo Governor is the independent gate that robot's command
must pass -- a robot may turn the manifold valve, but only after the
governor and a human master both agree it is safe to. The inert-gas
O2 check is a HARD gate neither a human nor a robot command can
override: no non-inerted tank atmosphere ever handles cargo.

## Open business

This repository is not only source code. It is a public, forkable
business model:

| Layer | What is open |
|---|---|
| OSS core | Actor runtime, Marine Cargo Governor, dispatch/discharge draft records, audit ledger |
| Business blueprint | Customer, offer, pricing, unit economics, sales motion |
| Operator playbook | How to fork, license, deploy and support the service in a jurisdiction |
| Trust controls | Governance, security reporting, actuation invariant, audit requirements |

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md) to start this as an
open business on itonami.cloud, and
[`docs/adr/0001-architecture.md`](docs/adr/0001-architecture.md) for the
full architecture and decision record.

## Capability layer

This blueprint resolves its technology stack via
[`kotoba-lang/industry`](https://github.com/kotoba-lang/industry) (ISIC
`5020`). Unlike the freight sibling, this vertical is NOT backed by a
separate bespoke domain capability lib: the tanker-safety checks
(IMO-number check-digit validation, SOLAS inert-gas O2) are
self-contained pure functions in `tanker.registry`, on top of the
generic robotics/identity/forms/dmn/bpmn/audit-ledger stack.

## Layout

| File | Role |
|---|---|
| `src/tanker/store.cljc` | **Store** protocol -- `MemStore` ‖ `DatomicStore` (`langchain.db`) + append-only audit ledger + dispatch AND discharge history (dual history). The double-actuation guard checks dedicated `:dispatched?`/`:discharged?` booleans rather than a `:status` value |
| `src/tanker/registry.cljc` | Dispatch/settlement draft records, plus the self-contained tanker-safety pure functions (`imo-number-valid?` -- the SOLAS A.600(15) 7-digit check-digit scheme; `inert-gas-o2-excessive?` -- the SOLAS II-2/4.5.5 8 vol% O2 ceiling) the governor re-verifies against -- no external capability library to delegate to |
| `src/tanker/facts.cljc` | Per-jurisdiction tanker / inert-gas / ship-shore catalog with an official spec-basis citation per entry, honest coverage reporting |
| `src/tanker/tankeradvisor.cljc` | **TankerAdvisor** -- `mock-advisor` ‖ `llm-advisor`; intake / bill-of-lading verify / dispatch / settlement proposals |
| `src/tanker/governor.cljc` | **Marine Cargo Governor** -- 8 HARD checks (spec-basis · evidence-incomplete · imo-number-invalid, the freight tracking-validity discipline on the SOLAS check digit · bl-unverified · cargo-grade-mismatch · vessel-overload, the fabrication ratio discipline · inert-gas-o2-excessive, HSE-CRITICAL at BOTH dispatch AND discharge · bonding-grounding-unconfirmed) + 2 double-actuation guards + 1 soft (confidence/actuation gate) |
| `src/tanker/phase.cljc` | **Phase 0→3** -- read-only → assisted intake → assisted verify → supervised (dispatch/settlement always human; vessel-shipment intake is the ONLY auto-eligible op, no direct capital risk) |
| `src/tanker/operation.cljc` | **OperationActor** -- langgraph StateGraph |
| `src/tanker/sim.cljc` | demo driver |
| `test/tanker/*_test.clj` | governor contract · phase invariants · store parity · registry conformance · facts coverage |

## Business-process coverage (honest)

This actor covers vessel-shipment intake through tanker / inert-gas /
ship-shore regulatory assessment, laden voyage dispatch and cargo
discharge settlement -- the core governed lifecycle:

| Covered | Not covered (out of scope for this R0) |
|---|---|
| Vessel-shipment intake + per-jurisdiction evidence checklisting, HARD-gated on an official spec-basis citation (`:vessel/intake`/`:bill-of-lading/verify`) | Real AIS / VTS / tanker-robot integration, voyage routing and fleet-schedule optimization |
| Voyage dispatch, HARD-gated on full evidence, a valid IMO check digit, a verified bill of lading, a grade match, a within-DWT load displacement, an inert-gas O2 below the SOLAS 8 vol% ceiling, a confirmed ship-shore bonding, plus a double-dispatch guard (`:voyage/dispatch`) | |
| Discharge settlement, HARD-gated on full evidence, the inert-gas O2 ceiling re-checked at discharge, and no double-discharge (`:discharge/settle`) | |
| Immutable audit ledger for every intake / verify / dispatch / discharge decision | |

Extending coverage is additive: add the next gate (e.g. a vapour-
emission-control or tank-pressure check) as its own governed op with
its own HARD checks and tests, following the SAME "an independent
governor re-verifies against the actor's own records before any
real-world act" pattern this repo's flagship ops already establish.

## Jurisdiction coverage (honest)

`tanker.facts/coverage` reports how many requested jurisdictions
actually have an official spec-basis in `tanker.facts/catalog` --
currently 4 seeded (JPN, USA, GBR, NOR) out of ~194 jurisdictions
worldwide. This is a starting catalog to prove the governor contract
end-to-end, not a claim of global coverage. Adding a jurisdiction is
additive: one map entry in `tanker.facts/catalog`, citing a real
official source -- never fabricate a jurisdiction's requirements to
make coverage look bigger.

## Maturity

`:implemented` -- `TankerAdvisor` + `Marine Cargo Governor` run as
real, tested code (see `Run` above), promoted from the
originally-published `:blueprint`-tier scaffold, following the SAME
governed-actor architecture as the other prior actors across this
fleet, with its own distinct, independently-named governor and its
own self-contained tanker-safety checks. See
`docs/adr/0001-architecture.md` for the history and design.

## License

Code and implementation templates are AGPL-3.0-or-later.
