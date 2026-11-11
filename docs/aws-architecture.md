# AWS architecture and deployment mechanics

This page describes **what runs on AWS**, how the pieces fit together, and how a
deployment actually happens. It complements:

- [`deployment.md`](deployment.md) — step-by-step deployment commands
- [`runbook.md`](runbook.md) — backup, restore, user recovery, cost guardrails
- [`adr/`](adr/README.md) — why these choices were made

Everything below reflects the live stack `domaci-odmeny` in `eu-north-1`
(Stockholm). The application itself is region-agnostic; the region is forced by
an Organizations SCP, see [ADR-0009](adr/0009-deploy-to-eu-north-1-with-free-plan-limits.md).

## Request path

```
Browser
  │ HTTPS
  ▼
Lambda Function URL  (public, AuthType=NONE, BUFFERED)
  │
  ▼
Lambda "domaci-odmeny-app"   Quarkus 3.40 / Java 21 / 512 MB / 10 s / concurrency 5
  │  1. no session  → 302 to the Cognito Hosted UI
  │  2. /q/oidc-callback exchanges the code, sets an encrypted session cookie
  │  3. resolves the Cognito "sub" → MEMBER#<id> item → role (PARENT/CHILD)
  ▼
DynamoDB (4 tables)      Cognito (users, groups, domain, app client)
```

The UI is server-rendered (Qute + HTMX + vendored Pico.css). There is no
separate frontend build, no API Gateway, no load balancer and no VPC.

## Services in use

| Service | Role | Configuration |
|---|---|---|
| **Lambda** | Runs the entire Quarkus application as one function | `java21`, `x86_64`, **512 MB** (Free-plan cap), timeout 10 s, reserved concurrency 5, handler `io.quarkus.amazon.lambda.runtime.QuarkusStreamHandler`, ~30 MB deployment package |
| **Lambda Function URL** | Public HTTPS entry point, replaces API Gateway/ALB | `AuthType=NONE`, `InvokeMode=BUFFERED`, plus `Lambda::Permission` resources for public invoke |
| **Amazon Cognito** | Identity only; the app never stores passwords | User pool `<stack>-users`, admin-create-only (no self sign-up), case-insensitive usernames, password ≥12 with upper/lower/digit, MFA off. Hosted UI domain `domaci-odmeny-<account>.auth.<region>.amazoncognito.com`. App client with secret, code flow, scopes `openid profile email`, token revocation on, user-existence errors hidden. Groups `parents` / `children` are mapped to Quarkus roles through the `cognito:groups` claim |
| **DynamoDB** | All household state | Four tables, each **provisioned 5 RCU / 5 WCU** (20/20 in total), `PK`+`SK` string keys, **no GSIs**, SSE on, **no PITR**, `DeletionPolicy: Retain` |
| **CloudWatch Logs** | Lambda logs | `/aws/lambda/<stack>-app`, **7-day retention**, JSON in the prod profile (Logs Insights friendly) |
| **CloudWatch Alarms** | Error and throttle detection | `Errors >= 1` and `Throttles >= 1` per 5 minutes, `TreatMissingData=notBreaching` |
| **IAM** | Permissions | `FunctionRole`: `AWSLambdaBasicExecutionRole` plus an inline policy scoped to exactly the four table ARNs (Get/Put/Update/Delete/Query/Scan/ConditionCheck/Transact*) |
| **CloudFormation** | Owns the stack | Stack `domaci-odmeny`, deployed through the `AWS::Serverless` transform |
| **S3** | Build artifact only | One bucket, `aws-sam-cli-managed-default-samclisourcebucket-*`, holds the uploaded `function.zip`. No application data |
| **AWS Budgets** | Optional cost guardrail | Not created by the stack; `scripts/create-budget.sh` adds a $1 budget outside it |

**Deliberately not used:** API Gateway, ALB, EC2, VPC, ECS, RDS, S3 for data,
SNS, SES, Secrets Manager, SSM Parameter Store, X-Ray, WAF. Each is replaced by
something simpler or free-tier friendly (Function URL, environment variables,
DynamoDB, CloudWatch alarms).

### What lives in each table

| Table | Contents |
|---|---|
| `Members` | Household record (`PK=HOUSEHOLD#<id>`, `SK=HOUSEHOLD#<id>`) and one item per person (`SK=MEMBER#<id>`) with `cognitoSub`, role, base allowance in haléře and the active flag |
| `Tasks` | Chore catalog (`Odpadky` 10 Kč, `Myčka` 10 Kč, `Vysávání` 20 Kč); the reward is snapshotted into a completion when the work is logged |
| `Activity` | Append-only ledger: completions, approvals, ledger entries, month snapshots **and audit events** (`PK=AUDIT#<yyyy-MM>`) |
| `Pending` | Approval queue (`PK=PENDING`, `SK=CREATED#<padded-millis>#<id>`) |

Money is stored as integer haléře. Accounting periods are calendar months in
`Europe/Prague`, independent of the AWS region.

## How deployment works

### 1. Build

```bash
./mvnw package
```

Runs the test suite and then produces `target/function.zip` (~30 MB) through the
`quarkus-amazon-lambda-http` extension: the application, its dependencies and the
Lambda handler shim. Nothing is containerised — no Docker or Podman image is
involved in deployment.

### 2. Deploy with SAM

```bash
SECRET="$(cat ~/.config/domaci-odmeny/session-secret)"

sam deploy --template-file sam/template.yaml --stack-name domaci-odmeny \
  --region eu-north-1 --capabilities CAPABILITY_IAM --resolve-s3 \
  --parameter-overrides "SessionSecret=$SECRET" \
    "OidcCallbackUrl=https://<function-url-id>.lambda-url.eu-north-1.on.aws/q/oidc-callback" \
    "OidcLogoutUrl=https://<function-url-id>.lambda-url.eu-north-1.on.aws/"
```

`--resolve-s3` creates or reuses the managed artifact bucket, uploads the zip,
then CloudFormation creates a changeset and applies it. There is no CI pipeline;
deployment is a local, manual command.

### 3. The two-phase chicken-and-egg

Cognito needs the Function URL as its callback, but the Function URL only exists
after the function is created. The first deployment therefore runs with the
placeholder `http://localhost:8080/q/oidc-callback`; you then read the
`FunctionUrl` stack output and redeploy with the real callback and logout URLs.

Every later deployment must keep those URLs **and** the same `SessionSecret` —
changing the secret invalidates all sessions.

### 4. Configuration injection

The template wires everything through Lambda environment variables, so no code
changes per environment:

| Environment variable | Source |
|---|---|
| `OIDC_AUTH_SERVER_URL` | `https://cognito-idp.<region>.amazonaws.com/<UserPool>` |
| `OIDC_CLIENT_ID` / `OIDC_CLIENT_SECRET` | app client reference / `GetAtt ClientSecret` |
| `OIDC_SESSION_SECRET` | `SessionSecret` parameter (`NoEcho`) |
| `MEMBERS_TABLE`, `TASKS_TABLE`, `ACTIVITY_TABLE`, `PENDING_TABLE` | table references |
| `HOUSEHOLD_ID=default`, `SEED_CATALOG=true` | template constants |

`AWS_REGION` is provided by Lambda automatically. The DynamoDB client uses the
execution role credentials from the default provider chain, so no access keys
exist anywhere in the stack or the repository.

### 5. Provisioning people (outside the stack)

CloudFormation cannot create family accounts, so
[`scripts/provision-household.sh`](../scripts/provision-household.sh) uses the
Cognito Admin APIs and DynamoDB writes:

1. Creates the household record only when it is missing.
2. `admin-create-user` + `admin-add-user-to-group` (`parents` / `children`), then
   `admin-set-user-password --permanent`.
3. Reads the Cognito `sub` and writes `MEMBER#<id>` with the link.

The script is idempotent and never overwrites a name, allowance or active flag
that parents changed in **Nastavení**; a re-run only repairs the Cognito link.
Passwords are permanent — changes are an administrative reset documented in the
[runbook](runbook.md#reset-a-password).

### 6. First-start seeding

On cold start with `SEED_CATALOG=true`, `LocalDataInitializer` creates the
household (`Naše domácnost`, CZK, `Europe/Prague`) and the three chores **only if
the corresponding records are missing**, so a redeploy never duplicates them.
`app.seed.enabled=false` in production, so no development members are created.

### 7. Updates, rollback and teardown

- **Update code:** `./mvnw package`, then the same `sam deploy` command; the
  changeset replaces the function code and leaves data untouched.
- **Rollback:** CloudFormation rolls a failed deployment back automatically. The
  tables survive because of `DeletionPolicy: Retain`.
- **Teardown:** `sam delete --stack-name domaci-odmeny --region eu-north-1`
  removes compute and identity but **keeps the four DynamoDB tables**. They must
  be deleted manually for a clean slate; a rollback that leaves retained tables
  also blocks a fresh `CREATE` until they are removed.

## Account constraints baked into the design

| Constraint | Effect |
|---|---|
| Organizations SCP `p-glnwopnj` denies CloudFormation, Lambda, DynamoDB, Cognito and most other services outside `eu-north-1` | The stack lives in Stockholm; periods stay `Europe/Prague` ([ADR-0009](adr/0009-deploy-to-eu-north-1-with-free-plan-limits.md)) |
| AWS Free plan caps Lambda at 512 MB | `LambdaMemorySize` defaults to 512 |
| `AWS::Budgets::Budget` is not a CloudFormation resource type in `eu-north-1` | The budget lives in `scripts/create-budget.sh`, not in the stack |
| Free plan limits total account concurrency to 400 | Reserved concurrency is 5, leaving 395 unreserved |

## Cost picture

Current spend is **$0**. The stack is designed to stay inside the free tier:

- Lambda: 400,000 GB-seconds and 1M requests per month; 512 MB × short requests
  keeps usage far below the limit.
- DynamoDB: 25 RCU / 25 WCU provisioned capacity free; the stack uses 20 / 20.
- Cognito: 10,000 monthly active users free.
- CloudWatch: 5 GB log ingestion free, plus 7-day retention to cap storage.
- Function URLs, CloudFormation and IAM have no charge at this scale.
- PITR is intentionally off; on-demand backups cost extra and are documented in
  the runbook instead.

## Known gaps

1. **The two alarms have no actions** (`AlarmActions: []`). They turn red in the
   console, but nobody is notified; attaching an SNS topic would fix that.
2. **No PITR on DynamoDB.** Backups are the in-app export (`/export/*`) plus
   optional on-demand `create-backup`, see the [runbook](runbook.md#backup-strategy).
3. **The Function URL is public** by necessity. Protection is entirely Quarkus
   OIDC plus server-side role checks; parent-only pages return `403` for
   children.
4. **No in-app password change or reset.** All password operations are
   administrative (`admin-set-user-password`).
5. **Single region, no multi-AZ story of its own.** Durability comes from
   DynamoDB's regional replication, not from the application.
