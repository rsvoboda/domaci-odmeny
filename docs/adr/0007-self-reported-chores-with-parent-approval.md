# ADR-0007: Use self-reported chores with parent approval

- **Status:** Accepted
- **Date:** 2026-11-07

## Context

The family currently tracks chores on paper after the work is done. Parents do not want to assign chores in the application or maintain a daily to-do list. Children are expected to report their own completed work.

The application therefore needs a fast self-reporting flow, a shared chore catalog, and a lightweight verification step.

## Decision

Use **self-reported chores with parent approval**:

- one shared chore catalog for the household
- no per-child chore assignment
- no recurring schedules, due dates, or reminders in v1
- all active chores are available to both children
- a child logs a completed chore
- each chore has a `requiresApproval` flag, default `true`
- if approval is required, the completion enters the parent queue
- if approval is not required, the completion is auto-approved and credited
- parents approve or reject with a reason
- rejection reasons are visible to the child
- parents do not log chores on behalf of children in v1; they can add a correction instead
- parent approval is the verification mechanism for duplicate or disputed claims

## Consequences

**Positive**

- Matches the existing paper workflow.
- Very fast for the child: open app, tap chore, submit.
- No assignment maintenance or scheduling overhead.
- Parent approval provides a trust check.
- Auto-approval can be enabled for routine, low-risk chores.
- The model is easy to extend with schedules or eligibility later.

**Negative**

- Parent approval can become a bottleneck if many completions accumulate.
- Two children could claim the same chore; the parent must resolve it.
- There is no “expected vs completed” reporting in v1.
- Children can only log work they remember to report.

**Follow-up**

- Add bulk approval.
- Consider a duplicate warning when the same chore is logged twice in a short window.
- Add optional per-child eligibility and recurring schedules later if the family needs them.
