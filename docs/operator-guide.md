# Operator Guide

## First Deployment
1. Register operators, fleets, vessel-shipments, and tanker masters.
2. Import vessel-shipment, bill-of-lading, and cargo-accounting history.
3. Seed the per-jurisdiction spec-basis catalog (`tanker.facts`) for the
   jurisdictions you actually operate in, citing real official sources only.
4. Run read-only spec-basis validation per jurisdiction.
5. Configure exception escalation and cargo-accounting accounts.
6. Publish a dry-run discharge settlement and audit export.

## Minimum Production Controls
- spec-basis validation before any assessment, dispatch, or settlement
- full cargo-handling / inert-gas evidence (bill of lading, IMO registry /
  vessel record, IGS operational record, ship-shore bonding) before any dispatch
- IMO-number check-digit, bill-of-lading, cargo-grade, DWT-displacement,
  inert-gas-O2 (at BOTH dispatch and discharge), and bonding checks before any
  dispatch
- exception escalation gate
- audit export for every dispatch, settlement, and hold
- backup manual dispatch and discharge-settlement process

## A Day in the Life: Intake → Verify → Dispatch → Settle → Audit

Community Marine Tanker / Water Freight Transport (ISIC 5020,
`cloud-itonami-isic-5020`) runs on the same intake / advise / govern / decide /
commit-or-hold loop as every itonami blueprint, but here the loop is concrete: a
regional tanker operator needs to bring a vessel-shipment (say, an Akita-to-
Yokohama laden crude run on a coastal tanker) from intake through bill-of-lading
safety assessment to a laden voyage dispatch and a cargo discharge settlement.
Walking through one vessel-shipment, end to end:

1. **Intake.** The operator books the vessel-shipment through `:forms`: vessel
   IMO number, bill-of-lading number, load port and discharge port, operator,
   jurisdiction, and the vessel-shipment's own physical record (cargo grade and
   the B/L-declared grade, laden volume in barrels, load-displacement percent and
   its safe DWT maximum, the cargo-tank inert-gas O2 percent and its SOLAS O2
   limit (8 vol%), ship-shore bonding status). This creates a vessel-shipment
   record at `:vessel/intake` status. The TankerAdvisor only normalizes the
   patch; it does not invent the IMO number, bill-of-lading number, cargo grade,
   or jurisdiction.
2. **Verify.** The TankerAdvisor drafts a per-jurisdiction tanker / inert-gas /
   ship-shore evidence checklist (`:bill-of-lading/verify`) from `tanker.facts`,
   citing the jurisdiction's official spec-basis (owner authority, legal basis,
   provenance) and listing the required evidence (bill of lading, IMO registry /
   vessel record, inert-gas-system operational record, ship-shore bonding
   confirmation). The `:marine-cargo-governor` sign-off gate must clear: it
   checks the jurisdiction actually has an official spec-basis on file (never
   invent one). A jurisdiction with no spec-basis is a HARD hold at the governor
   node -- it never even reaches a human. This assessment always escalates to a
   human for approval; it is never auto.
3. **Dispatch.** Before the laden voyage can sail, the `:marine-cargo-governor`
   sign-off gate runs the full HARD check set against the vessel-shipment's own
   ground truth: the spec-basis exists, the evidence checklist is complete, the
   IMO number passes its 7-digit check-digit validation, the bill of lading is
   verified, the B/L declared grade matches the loaded cargo grade, the load
   displacement is within the DWT limit, the cargo-tank inert-gas O2 is below the
   SOLAS 8 vol% ceiling, the ship-shore bonding is confirmed, and the shipment
   has not already been dispatched. Any failure is a HARD hold that a human
   cannot override. If every check is clean, the proposal STILL always escalates
   to a human tanker operator / master -- a `:voyage/dispatch` never auto-commits
   at any phase. On approval, the voyage-dispatch record is drafted
   (`<JURISDICTION>-VOYAGE-000001`) and the shipment's `:dispatched?` flag is
   set.
4. **Settle.** Once the laden voyage has actually sailed and the cargo has been
   discharged, the discharge is settled (`:discharge/settle`): laden-volume
   finalization and custody transfer. The governor re-checks the spec-basis, the
   evidence completeness, the HSE-CRITICAL inert-gas O2 (re-verified at discharge
   too), and that this shipment's discharge has not already been settled. As with
   the dispatch, a clean settlement STILL always escalates to a human tanker
   operator / master -- `:discharge/settle` never auto-commits. On approval the
   discharge-settlement record is drafted (`<JURISDICTION>-DISCHARGE-000001`)
   and the shipment's `:discharged?` flag is set.
5. **Audit.** The assessment, the dispatch sign-off, the voyage-dispatch record,
   the settlement sign-off, and the discharge-settlement record are all appended
   to the `:audit-ledger` -- immutable and exportable, so a custody or
   cargo-quality dispute can be traced back to the exact spec-basis citation,
   evidence checklist, and master sign-off that authorized the dispatch and
   discharge. If something is wrong with the vessel-shipment (a non-inerted tank
   atmosphere, a cargo-grade discrepancy, an unconfirmed bonding connection),
   that gets raised as an exception and routed through the escalation gate
   instead of being silently suppressed -- a dispatch for that shipment then
   waits on governor sign-off of the exception's resolution.

Any deviation from this loop is exactly what the Trust Controls in
`docs/business-model.md` exist to catch: a vessel-shipment verified against a
fabricated spec-basis, a dispatch started with incomplete evidence, an invalid
IMO number or a non-inerted tank atmosphere, an exception suppressed to force a
dispatch through, or a discharge posted without a human sign-off.

## Feel the Decision Gate: `clojure -M:dev:run`

This vertical has no companion playable prototype yet (unlike the freight
sibling's `itonami/freight-dispatch` game). The fastest hands-on way to feel why
the `:marine-cargo-governor` gate exists is the bundled demo, which walks one
clean vessel-shipment through intake → verify → dispatch → settle (each
dispatch/settle pausing for human approval) and then exercises every HARD-hold
failure mode in isolation:

- a jurisdiction with no official spec-basis → HOLD (`:no-spec-basis`),
- an IMO number that fails its 7-digit check-digit validation → HOLD
  (`:imo-number-invalid`),
- an unverified bill of lading → HOLD (`:bl-unverified`),
- a B/L declared grade that does not match the loaded cargo grade → HOLD
  (`:cargo-grade-mismatch`),
- a load displacement above the vessel's safe DWT limit → HOLD
  (`:vessel-overload`),
- a cargo-tank inert-gas O2 above the SOLAS 8 vol% ceiling → HOLD
  (`:inert-gas-o2-excessive`, HSE-CRITICAL -- exercised at BOTH the dispatch
  AND the discharge),
- an unconfirmed ship-shore bonding / grounding → HOLD
  (`:bonding-grounding-unconfirmed`),
- a double dispatch of the same vessel-shipment → HOLD (`:already-dispatched`),
- a double discharge of the same vessel-shipment → HOLD (`:already-discharged`).

Each HOLD settles at the governor node and never reaches a human approver -- the
same failure mode the audit ledger is built to catch and the minimum production
controls above are built to prevent. It is not a substitute for those controls,
but it is the fastest way for a new operator (or a reviewer) to feel, hands-on,
why the gate exists before touching a real deployment.

## Certification
Certified operators must prove spec-basis-grounded assessment, evidence-backed
dispatch readiness (IMO check digit, bill of lading, cargo-grade match, DWT
displacement, inert-gas O2, bonding), and human review for every dispatch- and
discharge-affecting action.
