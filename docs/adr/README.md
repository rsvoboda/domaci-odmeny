# Architecture Decision Records

This directory contains short Architecture Decision Records (ADRs) for Domácí odměny.

Each ADR captures one significant decision, the context in which it was made, and its consequences. The format is intentionally lightweight:

- **Status** — Proposed, Accepted, Superseded, or Deprecated
- **Date** — decision date
- **Context** — the problem and constraints
- **Decision** — what was chosen
- **Consequences** — positive, negative, and follow-up implications

## Index

| ADR | Decision | Status |
|---|---|---|
| [ADR-0001](0001-use-quarkus-modular-monolith.md) | Use a Quarkus modular monolith | Accepted |
| [ADR-0002](0002-use-server-rendered-qute-htmx-ui.md) | Use a server-rendered Qute + HTMX UI | Accepted |
| [ADR-0003](0003-use-aws-lambda-function-url.md) | Use AWS Lambda Function URL instead of API Gateway | Accepted |
| [ADR-0004](0004-use-dynamodb-with-small-provisioned-tables.md) | Use DynamoDB with small provisioned tables | Accepted |
| [ADR-0005](0005-use-cognito-username-password-auth.md) | Use Cognito username/password authentication | Accepted |
| [ADR-0006](0006-use-append-only-ledger-and-integer-minor-units.md) | Use an append-only ledger and integer minor units | Accepted |
| [ADR-0007](0007-self-reported-chores-with-parent-approval.md) | Use self-reported chores with parent approval | Accepted |
| [ADR-0008](0008-use-sam-and-free-tier-guardrails.md) | Use SAM and free-tier guardrails | Accepted |

## Adding a new ADR

1. Copy the structure of an existing ADR.
2. Use the next four-digit number.
3. Keep it short and focused on one decision.
4. Add it to the index above.
5. Link it from the proposal if it affects the v1 scope.
