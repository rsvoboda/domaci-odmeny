# ADR-0006: Use an append-only ledger and integer minor units

- **Status:** Accepted
- **Date:** 2026-11-07

## Context

The application calculates money owed to children. The calculation must be exact, explainable, and auditable. Children and parents must be able to trust that approvals, corrections, and payouts are not silently changed.

Floating-point arithmetic is unsuitable for money. Editing or deleting financial records would destroy the audit trail.

## Decision

Use an **append-only ledger** with **integer minor units**:

- all amounts stored as `long amountMinor` (haléře); `50 Kč = 5000`
- no `float` or `double` for money
- each task has a fixed reward
- the reward is snapshotted when a completion is created
- the base monthly amount is snapshotted when a statement is closed
- approved completions create immutable ledger entries
- corrections create new ledger entries with a required reason
- approved completions are reversed, never deleted
- monthly statements aggregate ledger entries
- periods are determined in `Europe/Prague`
- closed statements are immutable unless explicitly reopened with an audit reason

Ledger entry types:

- `TASK_REWARD`
- `BASE_ALLOWANCE`
- `ADJUSTMENT`
- `PAYOUT`
- `REVERSAL`

## Consequences

**Positive**

- Exact arithmetic with no rounding errors.
- Every balance can be explained by a sequence of ledger entries.
- Rate changes do not rewrite history.
- Corrections and reversals are visible and auditable.
- Monthly statements are reproducible.
- The model supports partial payments later.

**Negative**

- More records than a simple “current balance” field.
- Reporting requires aggregation.
- Corrections require explicit reversal/adjustment flows.
- Closed-period handling adds workflow complexity.

**Follow-up**

- Unit-test money arithmetic and period boundaries.
- Use a `Money` value object and a `Period` value object.
- Never expose direct ledger editing in the UI.
