# Domácí odměny — Application Proposal

- **Status:** Accepted for v1 implementation
- **Last updated:** 2026-11-07
- **Scope:** One household, four users (two parents, two children)
- **Related ADRs:** [ADR index](adr/README.md)

## 1. Summary

Domácí odměny is a small family web application for tracking chores completed by children and calculating a monthly reward.

The application is deliberately **not** a task-assignment or reminder system. Children report work they have already done. Parents verify the report, and approved chores are added to the child's monthly bonus.

The monthly payout is:

```text
base monthly amount
+ sum of approved chore rewards
+ parent corrections/adjustments
- payments already recorded
```

The first version is built as a Quarkus modular monolith, server-rendered with Qute and HTMX, secured with Amazon Cognito, persisted in DynamoDB, and deployed as a single AWS Lambda function behind a Lambda Function URL.

## 2. Goals and non-goals

### Goals

- Make chore reporting faster than writing it on paper.
- Give children a clear, trustworthy record of what they did and what they earned.
- Give parents a simple approval queue and a monthly payout statement.
- Keep the application free to run within AWS always-free limits.
- Keep the codebase small enough for one developer to understand and maintain.
- Use Java/Quarkus as the primary implementation stack.
- Store money exactly, without floating-point errors.
- Keep an auditable history of approvals, adjustments, closes, and payouts.

### Non-goals for v1

- Assigning chores to a specific child.
- Recurring schedules, due dates, overdue states, or reminders.
- Parent-created daily task lists.
- Photo proof of completed work.
- Email, SMS, or push notifications.
- Leaderboards, badges, streaks, or other gamification.
- Savings goals.
- Multi-family or public sign-up.
- Native mobile applications.
- Bank or payment-provider integration.
- Custom domain.
- PDF generation. Printable HTML/CSS is sufficient.

## 3. Users and roles

| Role | Description | Permissions |
|---|---|---|
| **Parent/admin** | Mother or father | View all members and data; approve/reject completions; manage chore catalog and reward amounts; manage base amounts; add corrections; close months; mark months paid; export data |
| **Child** | Son or daughter | View own chore catalog; log own completed chores; view own completion status and earnings; view own monthly statement |

Both parents are equal administrators. Children see only their own data by default.

Authorization is enforced server-side. Hiding a button in the UI is not an authorization control.

## 4. Product scope for v1

### 4.1 Chore catalog

The family has a shared catalog of chores. There is no per-child assignment.

Initial seed data:

| Chore | Czech label | Reward |
|---|---|---:|
| Rubbish | Odpadky | 10 Kč |
| Dishwasher | Myčka | 10 Kč |
| Vacuum | Vysávání | 20 Kč |

Each chore has:

- a name and optional description
- a fixed reward in CZK
- a `requiresApproval` flag, default `true`
- an active/inactive flag

Parents can edit the catalog and reward amounts in settings. They do not assign chores to children as part of the daily workflow.

All active chores are available to both children. The completion records who actually did the work.

### 4.2 Child experience

The child's primary screen answers: **“What did you do?”**

The child:

1. Opens the app.
2. Sees the three chore buttons with their rewards.
3. Taps the completed chore.
4. Optionally adds a note.
5. Submits.

The completion is stored with:

- the child's member ID
- the chore ID
- the completion date/time
- a snapshot of the reward amount
- a status: `PENDING`, `APPROVED`, `REJECTED`, or `REVERSED`
- an optional note

If the chore has `requiresApproval = false`, the completion is approved immediately and added to the ledger.

The child can see:

- their own completion history
- pending/approved/rejected status
- the reason for a rejection
- their own current-month total

The child cannot see:

- siblings' earnings or history
- the family ledger
- approval or admin pages
- settings or exports

### 4.3 Parent experience

The parent's primary screen is the approval queue.

The parent can:

- see pending completions with child, chore, time, and reward
- approve or reject a completion
- provide a rejection reason
- approve several pending completions in one action
- see each child's current-month total
- add a correction/adjustment with a required reason
- close a month
- mark a month paid
- view audit history
- export data

Parents do not log chores on behalf of children in v1. If a child forgets to log work, a parent can add a correction/adjustment with a reason.

### 4.4 Monthly statement

Each child has one statement per month.

The statement shows:

- base monthly amount
- approved chore rewards, grouped by chore
- corrections/adjustments
- total earned
- payments recorded
- balance due
- status: `OPEN`, `CLOSED`, or `PAID`

Example:

```text
Child: Daughter
Period: 2026-11

Base:                 50 Kč
Odpadky × 4:          40 Kč
Myčka × 3:            30 Kč
Vysávání × 2:         40 Kč
Correction:            0 Kč
-----------------------------
Total earned:        160 Kč
Paid:                  0 Kč
Balance due:         160 Kč
```

### 4.5 Settings

Parents can manage:

- household name
- currency (CZK for v1)
- timezone (Europe/Prague for v1)
- children and their base monthly amounts
- chore catalog, rewards, and approval flags
- parent accounts and password resets

## 5. Core workflows

### 5.1 Child logs a completed chore

1. Child selects a chore.
2. App records a completion with the reward snapshot.
3. If approval is required, status is `PENDING`.
4. If approval is not required, status is `APPROVED` and a ledger entry is written atomically.
5. Child sees confirmation and current status.

### 5.2 Parent approves or rejects

**Approve:**

1. Parent opens the pending queue.
2. Parent approves a completion.
3. A DynamoDB transaction:
   - verifies the completion is still `PENDING`,
   - updates it to `APPROVED`,
   - writes an immutable ledger entry with the reward snapshot,
   - removes it from the pending queue.
4. The child's monthly total updates.

The transaction and condition expression make approval idempotent. Double-clicking “Approve” cannot pay twice.

**Reject:**

1. Parent rejects a completion with a required reason.
2. Completion becomes `REJECTED`.
3. No ledger entry is written.
4. Child sees the rejection and reason.

### 5.3 Corrections and adjustments

A parent can add a positive or negative correction with a required reason. This is a correction tool, not a second reward model.

Examples:

- correcting a mistaken reward
- adding an occasional extra bonus
- compensating for work a child forgot to log

Corrections are ledger entries and are visible in the monthly statement and audit history.

### 5.4 Month close and payout

1. Parent reviews the month.
2. The app blocks closing if there are pending completions in that period.
3. On close, the app snapshots:
   - base monthly amount
   - approved chore rewards
   - corrections
   - total earned
4. The statement becomes `CLOSED`.
5. When money is handed over, the parent records a payout and marks the statement `PAID`.

A closed month is immutable. If a correction is needed, the parent must explicitly reopen the month with a reason, or record the correction in the next open month.

### 5.5 Late work

The accounting period is based on the completion date in the `Europe/Prague` timezone.

- Work completed before month end belongs to that month.
- If the month is already closed, late work is recorded in the next open month, or the parent reopens the closed month with an audit reason.
- Future completion dates are rejected.

## 6. Money rules

- All amounts are stored as integer **haléře** (`long amountMinor`). `50 Kč` is stored as `5000`.
- No floating-point types are used for money.
- Each chore has a fixed reward.
- The reward amount is snapshotted when the completion is created. Later rate changes do not rewrite history.
- The base monthly amount is configured per child. It is snapshotted when the month is closed.
- The base amount is not prorated in v1. A child added mid-month receives the full configured base amount.
- The ledger is append-only.
- Corrections are new ledger entries, not edits to old entries.
- Approved completions can be reversed with a reversing ledger entry; they are not deleted.
- A closed statement is immutable except through an explicit, audited reopen.
- Payouts are recorded as ledger entries. Partial payments are possible in the data model, even if the UI initially offers a simple “mark paid” action.

## 7. Domain model

| Entity | Purpose | Key fields |
|---|---|---|
| **Household** | Family/tenant settings | `id`, `name`, `currency`, `timezone` |
| **Member** | Parent or child | `id`, `householdId`, `cognitoSub`, `displayName`, `role`, `baseAllowanceMinor`, `active` |
| **Task** | Chore definition | `id`, `householdId`, `name`, `description`, `rewardMinor`, `requiresApproval`, `active` |
| **Completion** | Child's claim that work was done | `id`, `householdId`, `taskId`, `memberId`, `completedAt`, `period`, `status`, `rewardMinorSnapshot`, `note`, `approvedBy`, `approvedAt`, `rejectionReason` |
| **LedgerEntry** | Immutable money movement | `id`, `householdId`, `memberId`, `period`, `type`, `amountMinor`, `description`, `referenceId`, `createdBy`, `createdAt` |
| **Statement** | Monthly snapshot | `id`, `householdId`, `memberId`, `period`, `baseAllowanceMinor`, `taskBonusMinor`, `adjustmentsMinor`, `totalMinor`, `paidMinor`, `status`, `closedAt`, `paidAt` |
| **AuditEvent** | Append-only audit trail | `id`, `householdId`, `actorId`, `action`, `entityType`, `entityId`, `timestamp`, `details` |

Relationship sketch:

```text
Member 1 ──── * Completion * ──── 1 Task
Member 1 ──── * LedgerEntry
Member 1 ──── * Statement
Completion 1 ──── 0..1 LedgerEntry
```

### Domain invariants

- A completion belongs to a member and task in the same household.
- A completion's reward snapshot is never negative.
- An approved completion has exactly one reward ledger entry.
- A pending completion has no reward ledger entry.
- A rejected completion has no reward ledger entry.
- Ledger entries are never edited or deleted.
- A statement is unique per member and period.
- A statement cannot be closed while the period has pending completions.
- A child cannot approve a completion.
- A child cannot access another child's data.
- A deactivated member or task retains its history.
- Money is always in integer minor units.

## 8. Data storage design

### DynamoDB tables

For four users, use four small tables with provisioned capacity, **5 RCU / 5 WCU each**. That is 20/20 capacity units, inside the 25/25 always-free allowance.

| Table | Purpose | Key pattern |
|---|---|---|
| **Members** | Household members | `PK = HOUSEHOLD#<id>`, `SK = MEMBER#<id>` |
| **Tasks** | Chore catalog | `PK = HOUSEHOLD#<id>`, `SK = TASK#<id>` |
| **Activity** | Completions, ledger entries, statements, audit events | `PK = MEMBER#<id>` or `PK = AUDIT#<yyyy-mm>`, `SK = PERIOD#<yyyy-mm>#<TYPE>#<timestamp>#<id>` |
| **Pending** | Approval queue | `PK = PENDING`, `SK = CREATED#<timestamp>#<id>` |

No global secondary indexes are required in v1. The pending queue table avoids a “status index” GSI.

### Why this layout

- The data volume is tiny: four members, a few dozen tasks, and a few hundred completions per year.
- Every query is simple: member + period, all tasks, all members, or all pending items.
- Provisioned capacity keeps the database inside the always-free allowance.
- No GSI means no additional capacity cost.
- The pending queue is small and can be read with one query.

### Transactions and idempotency

- Logging a completion that requires approval writes the completion and the pending item in one transaction.
- Approving a completion uses a transaction with a condition that the completion is still `PENDING`; it updates the completion, writes the ledger entry, and deletes the pending item.
- Rejecting a completion updates the completion and deletes the pending item.
- Closing a statement uses a condition that the statement is `OPEN` and that no pending completions remain for the period.
- Payout uses a transaction that writes the payout ledger entry and updates the statement.

## 9. Architecture

```text
Phone / tablet / browser
        │ HTTPS + Cognito session cookie
        ▼
AWS Lambda Function URL
        │
        ▼
Quarkus application
  ├── Qute + HTMX UI
  ├── Application services
  ├── Domain model
  ├── DynamoDB repositories
  └── Cognito/OIDC security
        │
        ├── Amazon Cognito   (login, groups, sessions)
        ├── Amazon DynamoDB  (family data)
        └── CloudWatch Logs  (errors, audit)
```

### Stack

| Layer | Choice | Reason |
|---|---|---|
| Language | Java 21 | Preferred stack; records and modern date/time APIs |
| Framework | Quarkus 3.x | Fast startup, low memory, Lambda support, native option |
| UI | Qute + HTMX + Pico.css | Server-rendered, minimal JavaScript, no Node build |
| Auth | Amazon Cognito | Free for four users; no custom password storage |
| Database | DynamoDB | Only genuinely always-free AWS database option |
| Compute | AWS Lambda + Function URL | No API Gateway charge, no servers |
| Infrastructure | AWS SAM | Simple single-stack template |
| CI/CD | GitHub Actions + OIDC | No long-lived AWS keys |
| Region | eu-central-1 / Frankfurt | Low latency from Czechia, EU data residency |

### Authentication

- Cognito user pool with **username sign-in**.
- Self-sign-up disabled.
- Parent-managed accounts.
- Optional email for parents; children can use username-only accounts.
- Groups: `parents`, `children`.
- Quarkus OIDC authorization code flow with Cognito Hosted UI.
- Stateless, encrypted session cookie. This works well on Lambda.
- Cognito `sub` is mapped to a `Member` record in DynamoDB.
- Password reset is handled by a parent/admin through Cognito.
- MFA is optional for parents and can be added later.

### UI

- Mobile-first, Czech labels, CZK formatting, Europe/Prague dates.
- Child home: “What did you do?” with large chore buttons and reward amounts.
- Child history: own completions and statuses.
- Child month: own current-month total and statement.
- Parent home: pending approvals and per-child totals.
- Parent month: statement, corrections, close, mark paid.
- Parent settings: chores, rewards, children, base amounts.
- Parent audit/export page.
- PWA manifest and icons; installable from the browser. Offline writes are not in v1.
- HTMX is vendored and served from the application. No CDN dependency.
- No Node.js build step.

### Deployment

- One SAM stack containing Cognito, DynamoDB tables, Lambda, IAM role, Function URL, CloudWatch log group, and budget alarm.
- Start with the JVM Lambda build. Cold starts of 1–3 seconds are acceptable for a family app.
- Switch to Quarkus native later only if cold starts become annoying.
- Lambda memory: 512–1024 MB. Timeout: 10 seconds.
- Reserved concurrency: 5, as a cost-abuse guardrail.
- No custom domain in v1. The Lambda Function URL provides HTTPS.
- No API Gateway, S3 runtime hosting, RDS, EC2, or NAT Gateway.

### Local development

- `quarkus dev` for the application.
- DynamoDB Local in Docker for persistence.
- Keycloak Dev Services for local OIDC with test parent/child users.
- The same domain code and UI locally; only identity provider and database endpoint differ.
- Seed script for the three chores and 50 Kč base amount.

## 10. AWS free tier and cost

### Important account caveat

A brand-new AWS account created today is a **6-month Free plan** with up to **$200 in credits**. The account closes after 6 months or when credits are exhausted.

To keep the application long-term:

1. Build on the Free plan now.
2. Before the 6-month period expires, upgrade to the Paid plan.
3. Continue using only always-free services.
4. Set an AWS Budgets alert at **$1/month** immediately.
5. Use `eu-central-1`.
6. Do not add API Gateway, RDS, EC2, NAT Gateway, or a custom domain in v1.

The runtime can still be **$0/month** on a Paid plan because Lambda, DynamoDB, Cognito, CloudWatch Logs, and Lambda Function URL remain within always-free limits at this scale.

If the account predates the new sign-up experience, the older 12-month free tier may apply instead. Check the AWS Billing console for the account type and credit expiry.

### Expected usage

| Service | Always-free allowance | Expected usage |
|---|---|---|
| Lambda | 1M requests + 400,000 GB-seconds/month | A few thousand requests |
| Lambda Function URL | No separate charge | — |
| DynamoDB | 25 GB + 25 RCU + 25 WCU, provisioned | <100 MB, 20/25 capacity |
| Cognito | 10,000 MAU | 4 users |
| CloudWatch Logs | 5 GB/month | <100 MB with 7-day retention |

### Cost guardrails

- AWS Budgets with a $1/month alert.
- CloudWatch Logs retention of 7 or 14 days.
- No NAT Gateway, RDS, EC2, API Gateway, or S3 in the runtime path.
- Lambda reserved concurrency of 5.
- No secrets in the repository.
- Least-privilege IAM: Lambda can access only the required tables.
- Soft-delete members and tasks; keep financial history.
- Parent-only JSON/CSV export as the simple backup mechanism.
- Deployment artifacts may use a small S3 bucket; the cost is negligible and can be covered by credits during the Free plan.

## 11. Security and privacy

- No public sign-up.
- Cognito handles password storage, login, and password reset.
- HTTPS only via the Function URL.
- Secure, HTTP-only, encrypted session cookies.
- CSRF protection for all state-changing forms.
- Server-side role and ownership checks.
- Children can access only their own data.
- Parents can access all household data.
- Input validation and output escaping.
- No third-party analytics or advertising scripts.
- No photos in v1.
- Data stored in the EU region.
- No tokens or passwords in logs.
- Least-privilege IAM.
- Root account protected with MFA; daily work uses an IAM admin identity.

## 12. Testing

### Unit tests

- base + bonus + adjustment calculations
- integer minor-unit arithmetic
- Europe/Prague period boundaries, including month and DST edges
- approval/rejection state transitions
- authorization rules
- statement close and payout rules

### Integration tests

- DynamoDB Local with the same table definitions as production
- atomic approval and idempotency
- pending queue behavior
- statement close blocked by pending completions
- ledger append-only behavior

### Web tests

- Qute rendering
- form validation
- CSRF protection
- child cannot access parent pages or sibling data

### Local auth tests

- Keycloak Dev Services with test users in `parents` and `children` groups.

### Smoke tests

- Cognito login against the deployed Function URL
- one end-to-end flow: child logs chore → parent approves → monthly total updates

## 13. Operations

### Monitoring

- CloudWatch Logs with structured JSON logging.
- Log retention: 7 or 14 days.
- CloudWatch alarm on Lambda errors and throttles.
- AWS Budgets alert at $1/month.

### Backup and export

- Parent-only JSON export of all household data.
- Parent-only CSV export of ledger and statements.
- Soft-delete members and tasks.
- DynamoDB point-in-time recovery is optional and not free; enable only if the small cost is acceptable.
- Document a restore procedure in the runbook.

### Deployment

- GitHub Actions with OIDC to AWS.
- `./mvnw verify` before deployment.
- SAM build and deploy.
- Separate local development from production.
- One production stack is sufficient for v1.
- Lambda versions/aliases for rollback.
- Backward-compatible data changes only.

## 14. Delivery plan

### Slice 1 — Deployable skeleton

- Quarkus project with Qute/HTMX
- Cognito login
- DynamoDB Local and AWS tables
- SAM stack with Function URL
- One protected page

### Slice 2 — First vertical flow

- Household and four members
- Seeded chore catalog
- Child logs a chore
- Parent approves/rejects
- Ledger entry appears

### Slice 3 — Money workflow

- Monthly statement
- Corrections/adjustments
- Close month
- Mark paid
- Export

### Slice 4 — Hardening

- Mobile/PWA polish
- Audit view
- Tests for money, periods, and authorization
- Backup/export runbook
- Cost alarms and deployment documentation

## 15. Acceptance criteria for v1

- Four users can log in with username/password.
- A child sees only their own data.
- A child can log any active chore in under 10 seconds.
- A chore with `requiresApproval = true` enters the parent queue.
- A chore with `requiresApproval = false` is credited immediately.
- A parent can approve or reject a pending completion.
- Approval credits exactly once, even if the button is double-clicked.
- Rejection shows a reason to the child.
- The monthly total is `50 Kč base + approved chore rewards + corrections`.
- A parent can add a correction with a reason.
- A month cannot be closed while pending completions exist.
- A closed month is immutable unless explicitly reopened.
- A parent can mark a month paid.
- A parent can export the data.
- The application deploys with the documented SAM command.
- Runtime cost remains $0 within AWS always-free limits.

## 16. Risks and mitigations

| Risk | Mitigation |
|---|---|
| Parent approval becomes a bottleneck | Bulk approve; per-task auto-approve option |
| Children forget to log work | Parent correction/adjustment with reason |
| Both children claim the same chore | Parent approval is the verification step; duplicate warning can be added later |
| DynamoDB access patterns become limiting | Start with simple tables; add a GSI or migrate later if needed |
| Cognito Hosted UI feels clunky for children | Brand the Hosted UI; custom login page later if needed |
| Lambda cold starts | Start JVM; switch to native later if needed |
| AWS Free plan expires | Upgrade to Paid before 6 months; always-free services keep runtime at $0 |
| Cost drift | Budget alarm, log retention, no paid services, reserved concurrency |
| Data loss | Export, soft delete, optional PITR, infrastructure as code |
| Public Function URL abuse | Cognito auth, reserved concurrency, budget alarm, app-level validation |

## 17. Deferred decisions

- Recurring schedules and reminders.
- Per-child chore eligibility restrictions.
- Photo proof.
- Email/push notifications.
- Leaderboard or gamification.
- Savings goals.
- Multi-family support.
- Custom domain.
- Native mobile app.
- PDF statements.
- Partial-payment UI.
- MFA for parents.
- Parent-on-behalf chore logging.

## 18. Architecture decision records

- [ADR-0001: Use a Quarkus modular monolith](adr/0001-use-quarkus-modular-monolith.md)
- [ADR-0002: Use server-rendered Qute + HTMX UI](adr/0002-use-server-rendered-qute-htmx-ui.md)
- [ADR-0003: Use AWS Lambda Function URL instead of API Gateway](adr/0003-use-aws-lambda-function-url.md)
- [ADR-0004: Use DynamoDB with small provisioned tables](adr/0004-use-dynamodb-with-small-provisioned-tables.md)
- [ADR-0005: Use Cognito username/password authentication](adr/0005-use-cognito-username-password-auth.md)
- [ADR-0006: Use an append-only ledger and integer minor units](adr/0006-use-append-only-ledger-and-integer-minor-units.md)
- [ADR-0007: Use self-reported chores with parent approval](adr/0007-self-reported-chores-with-parent-approval.md)
- [ADR-0008: Use SAM and free-tier guardrails](adr/0008-use-sam-and-free-tier-guardrails.md)
