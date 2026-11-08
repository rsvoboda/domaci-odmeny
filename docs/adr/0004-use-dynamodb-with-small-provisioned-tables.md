# ADR-0004: Use DynamoDB with small provisioned tables

- **Status:** Accepted
- **Date:** 2026-11-07

## Context

The application needs durable storage for four users and a few hundred records per year. It must run on AWS within always-free limits.

There is no permanent free AWS relational database. RDS is limited to a 12-month/trial offer, and Aurora Serverless has a minimum cost. DynamoDB offers 25 GB of storage and 25 RCU/25 WCU of provisioned capacity per month in the always-free tier.

The access patterns are simple and known:

- get all members of a household
- get all active chores
- get a member's activity for a month
- get the pending approval queue
- get a monthly statement

## Decision

Use **DynamoDB with four small tables** and provisioned capacity:

| Table | Purpose | Capacity |
|---|---|---|
| `Members` | Household members | 5 RCU / 5 WCU |
| `Tasks` | Chore catalog | 5 RCU / 5 WCU |
| `Activity` | Completions, ledger entries, statements, audit events | 5 RCU / 5 WCU |
| `Pending` | Approval queue | 5 RCU / 5 WCU |

Total: 20 RCU / 20 WCU, inside the 25/25 always-free allowance. No GSIs in v1.

Key patterns:

- `Members`: `PK = HOUSEHOLD#<id>`, `SK = MEMBER#<id>`
- `Tasks`: `PK = HOUSEHOLD#<id>`, `SK = TASK#<id>`
- `Activity`: `PK = MEMBER#<id>` or `PK = AUDIT#<yyyy-mm>`, `SK = PERIOD#<yyyy-mm>#<TYPE>#<timestamp>#<id>`
- `Pending`: `PK = PENDING`, `SK = CREATED#<timestamp>#<id>`

Use DynamoDB transactions for completion logging, approval, rejection, and payout. Use condition expressions for idempotency.

## Consequences

**Positive**

- Fits inside the always-free allowance.
- No GSI capacity cost.
- Simple queries with predictable performance.
- Pending queue is a direct query, not a filtered scan.
- Transactions prevent double approval and double payment.
- Infrastructure is easy to define in SAM.

**Negative**

- No joins or ad-hoc SQL.
- Reporting queries must be designed around access patterns.
- Provisioned capacity can throttle bursts above 5 RCU/WCU per table; this is acceptable for four users.
- Adding new query patterns may require a GSI, a new table, or a migration.

**Follow-up**

- Keep repository interfaces storage-agnostic.
- Add integration tests against DynamoDB Local.
- Revisit the data model if reporting requirements grow significantly.
