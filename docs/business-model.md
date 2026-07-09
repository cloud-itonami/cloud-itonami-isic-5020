# Business Model: Community Marine Tanker (Water Freight Transport)

## Classification
- Repository: `cloud-itonami-isic-5020`
- ISIC Rev.5: `5020` — water freight transport (tanker)
- Domain: `transport/marine-tanker`
- Social impact: crew and port safety, environmental protection, transparency
- Governor: `:marine-cargo-governor`
- License: AGPL-3.0-or-later

## Scope
This actor covers vessel-shipment intake through per-jurisdiction tanker /
inert-gas / ship-shore regulatory assessment, laden voyage dispatch (sending a
real laden tanker from load port to discharge port), and cargo discharge
settlement (the laden volume received at the discharge port, the cargo transfer
finalized) for a community tanker operator. It does **not**, by itself, hold any
charter party, flag-state, or port-state operating authority required to run a
marine-tanker business in a given jurisdiction, perform the actual physical
cargo handling or vessel navigation, or optimize the voyage plan (voyage routing
and fleet-schedule optimization is a follow-up slice, not this R0). Whoever
deploys a live instance supplies the jurisdiction-specific operating authority,
the real tanker-loading / valve-robot and AIS / VTS / cargo-accounting
integrations, and bears that jurisdiction's liability -- the software supplies
the governed, spec-cited, audited execution scaffold so the operator does not
have to build the compliance layer from scratch.

## Customer
- regional and community tanker operators and fleet managers
- independent tanker owners and coastal / short-sea operators leaving closed
  fleet-management / chartering SaaS
- national shipping lines running community coastal-tanker fleets
- charterers, cargo owners, and port-state regulators who need an auditable,
  spec-cited vessel-shipment record

## Offer
- vessel-shipment intake and directory management
- per-jurisdiction tanker / inert-gas / ship-shore regulatory assessment with an
  official spec-basis citation
- laden voyage dispatch gated on full evidence and a clean IMO / bill-of-lading
  / cargo-grade / displacement / inert-gas / bonding envelope
- cargo discharge settlement (laden-volume finalization, custody transfer) with
  double-discharge prevention
- evidence checklisting (bill of lading, IMO registry / vessel record,
  inert-gas-system operational record, ship-shore bonding confirmation)
- exception and discrepancy workflows
- role-based access and immutable audit ledger

## Revenue
- self-host setup fee
- managed hosting subscription per operator / fleet
- support retainer with SLA
- AIS / VTS and cargo-accounting integration

## The `:marine-cargo-governor` Decision Rule

This blueprint's `:itonami.blueprint/governor` is `:marine-cargo-governor`. It
is the single authority that stands between "a laden voyage could be
dispatched" and "a voyage is allowed to sail," and between "a discharge could be
settled" and "it is allowed to settle." Every rule it enforces is traceable to
the domain (Community Marine Tanker, ISIC 5020) and to the three
`:social-impact` tags in `blueprint.edn` (`:safety`, `:environmental-protection`,
`:transparency`).

This is the rule the companion contract test (`test/tanker/governor_contract_
test.clj`) encodes end-to-end: the TankerAdvisor never dispatches a voyage or
settles a discharge the Marine Cargo Governor would reject, `:voyage/dispatch`
and `:discharge/settle` NEVER auto-commit at any phase, `:vessel/intake` (no
direct capital risk) MAY auto-commit when clean, and every decision (commit OR
hold) leaves exactly one ledger fact.

**Authorizes a voyage dispatch (`:voyage/dispatch`) or discharge settlement
(`:discharge/settle`) only when ALL of the following hold:**

1. **An official spec-basis citation exists for the jurisdiction** -- the
   governor will not authorize any `:bill-of-lading/verify`, `:voyage/dispatch`,
   or `:discharge/settle` proposal whose jurisdiction has no entry in the
   `tanker.facts` catalog (`:no-spec-basis`). This is the direct enforcement of
   `:transparency`: a jurisdiction whose tanker / inert-gas / ship-shore
   requirements cannot be traced to an OFFICIAL public source is never guessed.
   The advisor must not fabricate a jurisdiction's requirements.
2. **The jurisdiction's required evidence is fully on file** -- for a dispatch
   or discharge the vessel-shipment's jurisdiction must have been assessed with
   a complete cargo-handling / inert-gas evidence checklist on record: the bill
   of lading, the IMO registry / vessel record, the inert-gas-system (IGS)
   operational record, and the ship-shore bonding confirmation
   (`:evidence-incomplete`). This protects `:safety` and
   `:environmental-protection`: a tanker that cannot prove its cargo identity,
   its IGS readiness, and its bonding connection never loads or discharges.
3. **The vessel's IMO number passes its 7-digit check-digit validation** -- the
   governor INDEPENDENTLY re-verifies the vessel-shipment's own recorded IMO
   number via the pure function `tanker.registry/imo-number-valid?` (the freight
   tracking-validity discipline applied to the SOLAS / IMO resolution A.600(15)
   scheme: the first six digits are multiplied by the weights 7, 6, 5, 4, 3, 2
   and the units digit of the sum MUST equal the 7th, check, digit). A dispatch
   on an un-verifiable ship identity is no authority to sail a laden cargo
   (`:imo-number-invalid`).
4. **The bill of lading is verified** -- the governor INDEPENDENTLY re-verifies
   the vessel-shipment's own `:bl-verified?` flag (`:bl-unverified`). An
   unverified bill of lading is no authority to dispatch a laden cargo sailing.
5. **The B/L declared cargo grade matches the loaded cargo grade** -- the
   governor INDEPENDENTLY re-verifies the vessel-shipment's own declared
   `:bl-declared-grade` against its loaded `:cargo-grade` (the freight
   tracking-validity discipline applied to cargo identity). A mismatch signals
   contamination or quality fraud, and a dispatch on a mismatched grade is a
   cargo-quality fraud risk (`:cargo-grade-mismatch`).
6. **The load displacement stays within the vessel's safe DWT limit** -- the
   governor INDEPENDENTLY re-verifies the vessel-shipment's own
   `:load-displacement-pct` against its rated `:load-displacement-max`
   (`tanker.governor`'s `vessel-overload-violations`, the fabrication
   measured-ratio-vs-rated-limit discipline applied to deadweight tonnage). A
   laden displacement above the vessel's rated DWT is a structural / loadline
   overload (`:vessel-overload`).
7. **The cargo-tank atmosphere is inerted below the SOLAS 8 vol% O2 ceiling** --
   HSE-CRITICAL: the governor INDEPENDENTLY re-verifies the vessel-shipment's
   own recorded `:inert-gas-o2-percent` against its `:o2-limit-percent` (8 vol%
   per SOLAS II-2/4.5.5 for tankers carrying crude oil / petroleum products with
   a flashpoint <= 60C) via the pure function
   `tanker.registry/inert-gas-o2-excessive?` (the fabrication
   measured-value-vs-rated-limit discipline). An inert-gas system keeps the tank
   atmosphere below the oxygen limit so a flammable mixture cannot form during
   loading, discharging or crude washing -- exceeding it is a true explosion
   precursor. This check is evaluated UNCONDITIONALLY at BOTH the voyage
   dispatch AND the discharge settlement, and NO human approver may override it
   (`:inert-gas-o2-excessive`).
8. **The ship-shore bonding / grounding is confirmed** -- the governor
   INDEPENDENTLY re-verifies the vessel-shipment's own
   `:bonding-grounding-confirmed?` flag (`:bonding-grounding-unconfirmed`). An
   unconfirmed bonding connection risks static-electricity ignition during
   cargo handling -- the dispatch must wait on its confirmation.
9. **The vessel-shipment has not already been dispatched, and its discharge has
   not already been settled** -- a double dispatch of the same vessel-shipment
   is refused off a dedicated `:dispatched?` fact, and a double discharge off a
   dedicated `:discharged?` fact (never a `:status` value), the double-actuation
   guard every sibling actor in this fleet enforces (`:already-dispatched` /
   `:already-discharged`).

**Rejects (HOLD, un-overridable, never even reaches a human) when any of the
above fail.** A proposal with no spec-basis, incomplete evidence, an invalid IMO
check digit, an unverified bill of lading, a cargo-grade mismatch, a load
displacement above the DWT limit, an inert-gas O2 above the SOLAS 8 vol%
ceiling, an unconfirmed ship-shore bonding, or a double dispatch / discharge is
held at the governor node -- a human approver cannot override these, by
construction.

**Always escalates to a human (never auto-commits) for `:voyage/dispatch` and
`:discharge/settle`**, even when every check above is clean. Dispatching a real
laden tanker voyage (a cargo sailing from load port to discharge port) and
settling a real cargo discharge (real laden volume and real custody moving
between vessel and terminal) are the two real-world actuation events this actor
performs; both are always a human tanker operator / master's call. This is
enforced by TWO independent layers that agree on purpose: the governor's
confidence / actuation SOFT gate (a `:voyage/dispatch` / `:discharge/settle`
stake always escalates) and `tanker.phase`'s phase table, which never puts
either op in any phase's `:auto` set. The `:environmental-protection` tag is
enforced upstream of the governor, in the bill-of-lading / IGS evidence step --
the governor's job is dispatch / discharge authorization integrity, not voyage
optimization.

## Required Technologies

`blueprint.edn`'s `:itonami.blueprint/required-technologies` for this business,
and what each one is actually load-bearing for here (not a generic capability
list):

| Technology | What it is FOR in Community Marine Tanker |
|---|---|
| `:robotics` | The autonomous tanker-loading / valve robot that performs the physical act of cargo handling (loading, inerting, discharging a tanker's cargo tanks, and turning the manifold valves). The governor never dispatches hardware itself: a dispatch-clearing action must have cleared the same sign-off a human tanker operator / master would need (see Robotics Premise). |
| `:identity` | Operator, tanker-master, and port-officer identity plus role-based access, so the governor's sign-off is tied to *who* authorized a dispatch or discharge, not just *that* someone did. |
| `:forms` | Structured intake for vessel-shipment booking, per-jurisdiction evidence capture (bill of lading, IMO record, IGS operational record, ship-shore bonding), and exception submission -- the data the Decision Rule above actually evaluates comes in through these forms. |
| `:dmn` | Encodes the `:marine-cargo-governor` Decision Rule itself (spec-basis, evidence completeness, the IMO / B/L / cargo-grade / displacement checks, the HSE-CRITICAL inert-gas O2 check, the bonding check, the double-actuation guards, the actuation gate) as an evaluable decision table rather than code buried in application logic -- this is what makes the governor auditable and swappable per-deployment. |
| `:bpmn` | Orchestrates the intake -> verify -> dispatch -> settle -> audit loop end-to-end (see `docs/operator-guide.md`) across vessel-shipment intake, bill-of-lading assessment, voyage dispatch, and discharge settlement, including the exception escalation gate. |
| `:audit-ledger` | The immutable record of every assessment, dispatch, discharge, exception, and hold -- this is what "an auditable, spec-cited vessel-shipment record for every dispatch and discharge" (Trust Controls, below) actually means in practice, and the evidence an operator needs if a dispatch or discharge is later disputed by a charterer, cargo owner, or port-state regulator. |
| `:optimization` | Voyage routing and fleet-schedule optimization -- selects the route and schedule for a fleet. This R0 build deliberately scopes optimization OUT (see README `Business-process coverage`); the capability is correctly marked required, the integration is a follow-up slice. |

There is NO bespoke `:maritime` capability library in this stack (unlike the
freight sibling's `:logistics`): the tanker-safety checks (IMO-number
check-digit validation, SOLAS inert-gas O2) are self-contained pure functions in
`tanker.registry`, on top of the generic robotics/identity/forms/dmn/bpmn/
audit-ledger stack (see Capability layer).

## Trust Controls
- a jurisdiction with no official spec-basis can never be verified, dispatched,
  or settled against
- a dispatch never starts with incomplete cargo-handling / inert-gas evidence
- a dispatch never starts with an invalid IMO check digit, an unverified bill of
  lading, a cargo-grade mismatch, a load displacement above the DWT limit, an
  inert-gas O2 above the SOLAS 8 vol% ceiling, or an unconfirmed ship-shore
  bonding
- the same vessel-shipment can never be dispatched or discharged twice
- the inert-gas O2 check is re-verified at discharge too -- no non-inerted tank
  atmosphere ever settles a discharge, by any human or robot
- a dispatch or discharge never auto-commits; both always need a human tanker
  operator / master
- every dispatch and discharge (commit OR hold) leaves exactly one immutable
  ledger fact
- vessel-shipment, bill-of-lading, and cargo-accounting data stays outside Git

## Implementation notes (`:implemented`)

The Decision Rule above is implemented faithfully by `tanker.governor` as eight
HARD checks (a human approver cannot override them -- and the inert-gas O2 check
is HSE-CRITICAL, evaluated at BOTH dispatch and discharge) plus the
double-actuation guards, plus one SOFT gate:

- `spec-basis-violations` -- the spec-basis check above, evaluated on every
  `:bill-of-lading/verify`, `:voyage/dispatch`, and `:discharge/settle`.
- `evidence-incomplete-violations` -- the evidence-completeness check above,
  for `:voyage/dispatch` / `:discharge/settle`.
- `imo-number-invalid-violations` -- the IMO check-digit check above, an honest
  reapplication of the freight tracking-validity discipline to the SOLAS /
  IMO resolution A.600(15) 7-digit scheme; evaluated unconditionally on every
  `:voyage/dispatch`.
- `bl-unverified-violations` -- the verified-bill-of-lading check above,
  evaluated on every `:voyage/dispatch`.
- `cargo-grade-mismatch-violations` -- the cargo-grade / B/L-declared-grade
  match check above, an honest reapplication of the freight tracking-validity
  discipline to cargo identity; evaluated on every `:voyage/dispatch`.
- `vessel-overload-violations` -- the load-displacement-vs-DWT check above, an
  honest reapplication of the fabrication measured-ratio-vs-rated-limit
  discipline to deadweight tonnage; evaluated unconditionally on every
  `:voyage/dispatch`.
- `inert-gas-o2-excessive-violations` -- the HSE-CRITICAL inert-gas O2 check
  above, an honest reapplication of the fabrication measured-value-vs-rated-
  limit discipline to the SOLAS II-2/4.5.5 8 vol% ceiling; evaluated
  UNCONDITIONALLY at BOTH every `:voyage/dispatch` AND every
  `:discharge/settle`; overridable by NO human.
- `bonding-grounding-unconfirmed-violations` -- the ship-shore bonding /
  grounding check above, evaluated on every `:voyage/dispatch`.
- `already-dispatched-violations` / `already-discharged-violations` -- the
  double-actuation guards above, off dedicated `:dispatched?` / `:discharged?`
  booleans (never a `:status` value), the same discipline every sibling
  governor's guards establish.
- the confidence floor / actuation SOFT gate -- low confidence, OR a
  `:voyage/dispatch` / `:discharge/settle` stake, escalates to a human; and
  `tanker.phase` independently never auto-commits either op at any phase.

`:voyage/dispatch` and `:discharge/settle` are the two real-world actuation
events (`#{:voyage/dispatch :discharge/settle}`), applied SEQUENTIALLY to the
SAME vessel-shipment (dispatch first, discharge settlement later) rather than
the retail sibling's `:kind`-distinguished alternative-action shape -- the same
sequential dual-actuation shape the repair-shop and quarrying clusters, and the
upstream crude / gas siblings, use. Neither ever auto-commits at any phase. The
HSE-CRITICAL inert-gas O2 check fires at BOTH actuation points (a dispatch and a
discharge), so a non-inerted tank atmosphere is blocked from either handling
cargo or settling. Voyage routing and fleet-schedule optimization (the
`:optimization` line above) is a follow-up slice, not in this R0 build -- see
README `Business-process coverage`.

## Capability layer

Unlike `cloud-itonami-isic-4920` (which wraps a pre-existing bespoke capability
library `kotoba-lang/logistics`), this vertical is SELF-CONTAINED: there is no
`kotoba-lang/maritime` to delegate tanker-safety validation to. The IMO-number
check-digit validation and the SOLAS inert-gas O2 check live as pure functions
in `tanker.registry` and are re-verified independently by the governor, rather
than wrapping an external capability library's own validated function -- the same
'reuse a capability's own validated function' discipline, here applied to this
vertical's OWN pure registry functions.

## Jurisdiction coverage (honest)

`tanker.facts/catalog` currently seeds 4 jurisdictions with an official
spec-basis, each a REAL regime: Japan (MLIT Maritime Bureau 船舶安全法 / Ship
Safety Act plus MARPOL Annex I over tankers), the United States (U.S. Coast
Guard tank-vessel regime, 33 C.F.R., grounded in SOLAS Chapter II-2), the United
Kingdom (Maritime and Coastguard Agency Merchant Shipping Regulations, SOLAS
Chapter II-2), and Norway (Norwegian Maritime Authority, Ship Safety and
Security Act, SOLAS). The SOLAS II-2/4.5.5 inert-gas O2 ceiling (8 vol%) is the
internationally cited cargo-tank-explosion-prevention reference each of these
authorities enforces, recorded per vessel-shipment as its `:o2-limit-percent`.
This is a starting catalog to prove the governor contract end-to-end, not a
claim of global coverage (4 of ~194 jurisdictions worldwide). Adding a
jurisdiction is additive: one map entry in `tanker.facts/catalog`, citing a real
official source -- never fabricate a jurisdiction's requirements to make
coverage look bigger.

## Maturity

`:implemented` -- `TankerAdvisor` + `Marine Cargo Governor` run as real, tested
code (`clojure -M:dev:test`: 42 tests / 204 assertions, 0 failures; lint clean),
promoted from the originally-published `:blueprint`-tier scaffold, following the
SAME governed-actor architecture as the other prior actors across this fleet,
with its own distinct, independently-named governor and its own self-contained
tanker-safety checks. See `docs/adr/0001-architecture.md` for the history and
design.

## Robotics Premise

`blueprint.edn` sets `:itonami.blueprint/robotics true`. In this domain an
autonomous tanker-loading / valve robot performs the physical act of cargo
handling (loading, inerting, discharging a tanker's cargo tanks, and turning the
manifold valves), under the actor, gated by the independent **Marine Cargo
Governor**. The governor never dispatches hardware itself: a dispatch-clearing
action must have cleared the same sign-off a human tanker operator / master
would need. A robot may turn the manifold valve, but only after the governor
(every HARD check clean, including the inert-gas O2 gate) and a human master
both agree it is safe to -- the same operating-state-machine-gated-by-governor
premise every cloud-itonami vertical restates (ADR-2607011000): the blueprint
declares `:robotics true`, the README names the robot that performs the physical
act, and the Marine Cargo Governor is the independent gate that robot's command
must pass. The inert-gas O2 check is a HARD gate neither a human nor a robot
command can override.
