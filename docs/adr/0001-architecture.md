# ADR-0001: TankerAdvisor ⊣ Marine Cargo Governor architecture

## Status

Accepted. `cloud-itonami-isic-5020` promoted from `:blueprint` to
`:implemented` in the `kotoba-lang/industry` registry.

## Context

`cloud-itonami-isic-5020` publishes an OSS business blueprint for community
marine-tanker water freight transport (vessel-shipment intake, per-jurisdiction
tanker / inert-gas / ship-shore regulatory assessment, laden voyage dispatch,
and cargo discharge settlement). Like every prior actor in this fleet, the
blueprint alone is not an implementation: this ADR records the governed-actor
architecture that promotes it to real, tested code, following the same langgraph
StateGraph + independent Governor + Phase 0->3 rollout pattern established by
`cloud-itonami-isic-6511` (life insurance) and applied across the fleet.

Like the upstream crude-extraction and natural-gas siblings, and
`cloud-itonami-isic-0810` (quarrying), this vertical has NO bespoke domain
capability library in `kotoba-lang` to wrap (verified: no
`kotoba-lang/maritime`-style repo exists, and `kotoba-lang/robotics` is the
generic cross-cutting robotics contract every cloud-itonami vertical already
uses, not a domain-specific library for this vertical). This build therefore
uses self-contained domain logic -- the same pattern the majority of this fleet's
actors use, and the explicit differentiator from `cloud-itonami-isic-4920`
(which wraps a pre-existing `kotoba-lang/logistics` library). The tanker-safety
checks (the IMO-number 7-digit check-digit validation, the SOLAS inert-gas O2
ceiling) live as pure functions in `tanker.registry` and are re-verified
independently by the governor.

This blueprint's own `:itonami.blueprint/governor` keyword,
`:marine-cargo-governor`, is grep-verified UNIQUE fleet-wide -- no naming-
collision precedent question, a fresh independent build.

## Decision

### Decision 1: fresh governor identity, no reuse precedent needed

`:marine-cargo-governor` is grep-verified unique across every `blueprint.edn`
in this fleet. This build follows the SAME governed-actor architecture as every
prior actor, but with its own distinct governor identity.

### Decision 2: self-contained domain logic (no `kotoba-lang/maritime` to wrap)

Unlike `cloud-itonami-isic-4920` (freight, which delegates tracking-number
validation to a real, pre-existing `kotoba-lang/logistics` capability library),
this marine-tanker vertical has NO pre-existing maritime capability library to
delegate tanker-safety validation to. The two pure physical checks (the
IMO-number check-digit validation, the SOLAS inert-gas O2 ceiling) are therefore
pure functions defined in `tanker.registry` and called directly by
`tanker.governor` -- the SAME 'reuse a capability's own validated function'
discipline `retailops.governor`'s ean13 check establishes for a capability
library, here applied to this vertical's OWN pure registry functions rather than
a separate library. No literal code is shared with any sibling (different
domain), but the discipline is the same.

### Decision 3: dual-actuation shape, SEQUENTIAL on the SAME `vessel-shipment` entity

Unlike the retail sibling's `order` entity (distinguished by `:kind`,
alternative sale-or-reorder actions), this vertical's `dispatch` and `settle`
actuation events apply SEQUENTIALLY to the SAME `vessel-shipment` -- a laden
voyage dispatch happens first (the cargo sailing from load port to discharge
port), the cargo discharge settlement happens later (laden-volume finalization,
custody transfer), on the same vessel-shipment record. This matches the
repair-shop cluster's `ticket`, the quarrying cluster's `extraction`, and the
upstream crude / gas siblings' `well` shape (two real-world acts, in order, on
one entity). `high-stakes` is `#{:voyage/dispatch :discharge/settle}`; neither
ever auto-commits at any phase.

### Decision 4: the marine-cargo physical check suite -- honest reapplications of established fleet disciplines

The physical checks the governor runs on every `:voyage/dispatch` (and, for the
inert-gas O2 check, on every `:discharge/settle` too) are each an honest
reapplication of an established fleet discipline to a marine-tanker value,
documented as such rather than claimed as novel inventions (the same convention
`cloud-itonami-isic-0162`'s Decision 3 establishes for `dose-matches-claim?`):

- `imo-number-valid?` reapplies the **freight tracking-validity** discipline to
  the SOLAS / IMO resolution A.600(15) 7-digit check-digit scheme: the vessel's
  IMO number's first six digits are multiplied by the weights 7, 6, 5, 4, 3, 2
  and the units digit of the sum MUST equal the 7th (check) digit. A dispatch on
  a structurally invalid ship identity is no authority to sail a laden cargo.
  This is the SAME 'reuse a validated structural check' discipline
  `retailops.governor`'s ean13 and the freight siblings' tracking-number checks
  establish, applied to the IMO scheme.
- `vessel-overload-violations` reapplies the **fabrication measured-ratio-vs-
  rated-limit** discipline to deadweight tonnage: the vessel-shipment's own
  recorded `:load-displacement-pct` must stay at or below its rated
  `:load-displacement-max`. A laden displacement above the vessel's rated DWT is
  a structural / loadline overload -- a dispatch-readiness gate.
- `inert-gas-o2-excessive?` reapplies the **fabrication measured-value-vs-rated-
  limit** discipline to the cargo-tank atmosphere: the vessel-shipment's own
  recorded `:inert-gas-o2-percent` must stay at or below its `:o2-limit-percent`
  (8 vol% per SOLAS II-2/4.5.5 for tankers carrying crude oil / petroleum
  products with a flashpoint <= 60C). An inert-gas system keeps the tank
  atmosphere below the oxygen limit so a flammable mixture cannot form during
  loading, discharging or crude washing -- exceeding it is a true explosion
  precursor. This check is **HSE-CRITICAL and is evaluated UNCONDITIONALLY at
  BOTH every `:voyage/dispatch` AND every `:discharge/settle`**: no human
  approver, no phase, and no confidence score may override it, so no
  non-inerted tank atmosphere ever handles cargo or settles a discharge.
- The bill-of-lading, cargo-grade, and bonding checks are direct entity-grounded
  checks (the same 're-verify the entity's own recorded truth' discipline every
  sibling governor establishes), not range functions: `:bl-verified?`, the
  `:bl-declared-grade` vs `:cargo-grade` match, and the
  `:bonding-grounding-confirmed?` flag.

Each of the range functions returns `true` when the value is provably OUTSIDE the
safe envelope; the conservative marine-cargo choice, missing data is a violation
(cannot verify safe to dispatch / discharge). The IMO and O2 checks are evaluated
UNCONDITIONALLY on every dispatch; the O2 check is additionally evaluated on
every discharge. No new unconditional-evaluation ordinals are claimed beyond
documenting the HSE-CRITICAL two-actuation-point evaluation of the inert-gas O2
check (the same 'document the discipline-reapplication as such' honesty this
fleet follows, per Decision 3 of `cloud-itonami-isic-0162`).

### Decision 5: HSE-CRITICAL inert-gas O2 evaluated at BOTH actuation points

The inert-gas O2 check is unique in this governor in two ways: it is the one
physical range check evaluated at BOTH the dispatch and the discharge (not just
the dispatch), and it is the one check no human approver may ever override. A
cargo-tank atmosphere above the SOLAS 8 vol% O2 ceiling is an explosion
precursor whether the tanker is loading or discharging, so the governor
re-verifies it UNCONDITIONALLY at both real-world actuation events. This is the
direct enforcement of the `:safety` and `:environmental-protection`
`:social-impact` tags: a non-inerted tank atmosphere never handles cargo.

### Decision 6: dedicated double-actuation-guard booleans

`:dispatched?` / `:discharged?` are dedicated booleans on the
`vessel-shipment` record, never a single `:status` value -- the same discipline
every prior governor's guards establish, informed by `cloud-itonami-isic-6492`'s
real status-lifecycle bug (ADR-2607071320).

### Decision 7: Store protocol, MemStore + DatomicStore parity

`tanker.store/Store` is implemented by both `MemStore` (atom-backed, default
for dev/tests/demo) and `DatomicStore` (`langchain.db`-backed), proven to
satisfy the same contract in `test/tanker/store_contract_test.clj`. The
ledger stays append-only on every backend: which vessel-shipment was screened
for an invalid IMO check digit, an unverified bill of lading, a cargo-grade
mismatch, an overloaded displacement, an inert-gas O2 above the SOLAS 8 vol%
ceiling, or an unconfirmed ship-shore bonding, which shipment had a voyage
dispatched, which discharge was settled, on what jurisdictional basis, approved
by whom -- always a query over an immutable log.

### Decision 8: Phase 0->3 with `:voyage/dispatch`/`:discharge/settle` NEVER auto

`tanker.phase`'s phase table puts `:vessel/intake` (no direct capital risk) in
phase 3's `:auto` set as its only member; `:voyage/dispatch` and
`:discharge/settle` are deliberately ABSENT from every phase's `:auto` set,
including phase 3 -- a permanent structural fact. `tanker.governor`'s
high-stakes gate enforces the same invariant independently: two layers agree
that actuation is always a human tanker operator / master's call.

### Decision 9: mock + LLM advisor pair

`tanker.tankeradvisor` provides a deterministic `mock-advisor` (default, runs
offline) and an `llm-advisor` backed by a `langchain.model/ChatModel`. The LLM
advisor's EDN proposal is parsed defensively: any parse/shape failure yields a
safe low-confidence noop so the governor escalates/holds -- an LLM hiccup can
never auto-dispatch a voyage or auto-settle a discharge.

## Alternatives considered

- **Wrapping a bespoke `kotoba-lang/maritime` capability library.** Considered
  and explicitly ruled out: no such library exists, and `kotoba-lang/robotics`
  is generic, not maritime-specific. Forcing a false capability-library
  integration would be dishonest; this build correctly uses self-contained
  domain logic instead.
- **A `:kind`-distinguished entity** (matching the retail sibling's `order`
  shape). Rejected: dispatch and settlement happen SEQUENTIALLY on the SAME
  vessel-shipment in this domain, not as alternative actions -- the repair-shop
  / quarrying / upstream-crude cluster's sequential shape is the honest match
  here.
- **Evaluating the inert-gas O2 check only at the dispatch.** Rejected: a
  cargo-tank atmosphere above the SOLAS 8 vol% ceiling is an explosion precursor
  whether the tanker is loading or discharging. The HSE-CRITICAL discipline
  demands the check be re-verified UNCONDITIONALLY at BOTH actuation points,
  overridable by no human -- a discharge into a non-inerted tank atmosphere is
  exactly the failure this check exists to prevent.
- **Building voyage routing / fleet-schedule optimization in this R0.** Rejected
  in favor of a scoped R0 slice (the `:optimization` capability is correctly
  marked required, the integration is a follow-up), consistent with this fleet's
  'extending coverage is additive' convention.

## Consequences

- Marine-tanker water freight transport (ISIC 5020) actor on the same
  governed-actor architecture as the rest of the cloud-itonami fleet.
- Establishes the marine-cargo physical check suite as honest reapplications of
  established fleet disciplines (tracking-validity, ratio/value-vs-rated-limit,
  entity-grounded flag checks) to marine cargo -- no genuinely-new-concept
  check, all discipline-reuse documented as such per `cloud-itonami-isic-0162`
  Decision 3, with the HSE-CRITICAL two-actuation-point inert-gas O2 evaluation
  documented explicitly.
- `MemStore` || `DatomicStore` parity is proven by
  `test/tanker/store_contract_test.clj`.
- 42 tests / 204 assertions pass; lint is clean; the demo
  (`clojure -M:dev:run`) walks one clean dispatch + discharge lifecycle, plus
  the HARD-hold scenarios (no spec-basis, invalid IMO, unverified B/L,
  cargo-grade mismatch, vessel overload, inert-gas O2 excessive at dispatch AND
  at discharge, bonding unconfirmed, double dispatch, double discharge),
  end-to-end.
- `blueprint.edn` required no field-sync fixes (already correct) -- only the
  `:maturity` flip itself.

## References

- `cloud-itonami-isic-6511/docs/adr/0001-architecture.md` (origin of the
  general governed-actor architecture pattern)
- `cloud-itonami-isic-4920/docs/adr/0001-architecture.md` (freight sibling;
  contrast: wraps a pre-existing `kotoba-lang/logistics` capability library)
- `cloud-itonami-isic-0162/docs/adr/0001-architecture.md` (origin of the
  'honest reapplication, documented as such' convention this build follows
  for its physical checks)
- the upstream crude-extraction sibling's `docs/adr/0001-architecture.md` (same
  sequential dual-actuation shape this build mirrors)
- 船舶安全法 (Ship Safety Act), 国土交通省 (MLIT) 海事局; MARPOL Annex I (Japan)
- Tank Vessels, 33 C.F.R.; SOLAS Chapter II-2 (fire protection / inert gas)
  (US, U.S. Coast Guard)
- Merchant Shipping Regulations, SOLAS Chapter II-2 (UK, Maritime and
  Coastguard Agency)
- Norwegian Ship Safety and Security Act; SOLAS (Norway, Norwegian Maritime
  Authority / Sjøfartsdirektoratet)
- SOLAS Chapter II-2, regulation 4.5.5: inert-gas-system O2 limit of 8 vol% for
  tankers carrying crude oil / petroleum products with a flashpoint <= 60C
- IMO resolution A.600(15): IMO Ship Identification Number Scheme (7-digit
  check-digit scheme, weights 7, 6, 5, 4, 3, 2)
