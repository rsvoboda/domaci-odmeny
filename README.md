# Domácí odměny

A small family web application for tracking chores completed by children and
calculating a monthly reward.

Children report work they have **already done**, parents approve it, and
approved chores are added to the child's monthly bonus. It is deliberately not
a task-assignment or reminder system.

- Product proposal: [`docs/proposal.md`](docs/proposal.md)
- Architecture decisions: [`docs/adr/`](docs/adr/README.md)

## Current status — Slice 2 (first vertical flow)

Implemented:

- Quarkus 3.40 / Java 21 project with Qute + HTMX + Pico.css
- Server-side security policies, role-aware child and parent home screens
- Cognito/OIDC configuration for production
- Development login (no identity provider needed locally)
- Household settings and a seeded chore catalog
  (`Odpadky` 10 Kč, `Myčka` 10 Kč, `Vysávání` 20 Kč)
- Child flow: log a completed chore with an optional note, see the current
  month's base + bonus + total, browse history and rejection reasons
- Parent flow: approval queue, approve (credits the reward) or reject with a
  required reason
- Append-only ledger entries written atomically with the completion state
- DynamoDB adapters using transactions with condition expressions, so double
  approval cannot credit twice
- SAM template: Cognito, four DynamoDB tables, Lambda + Function URL, log
  retention, alarms and a $1 budget
- 42 hermetic tests: money/period primitives, domain workflow, web flow, CSRF,
  authorization

Not implemented yet: monthly statements, corrections, closing a month, payouts,
exports, PWA. See the delivery plan in the proposal.

## Prerequisites

- JDK 21 or newer (the build targets Java 21)
- Podman (for DynamoDB Local)
- AWS SAM CLI and AWS credentials, only for deployment

The Maven wrapper is committed, so no local Maven installation is required.

## Run locally

1. Start DynamoDB Local:

   ```bash
   podman compose up -d dynamodb
   ```

   If you use `podman-compose` instead of the Podman compose provider:

   ```bash
   podman-compose up -d dynamodb
   ```

2. Start the application:

   ```bash
   ./mvnw quarkus:dev
   ```

3. Open <http://localhost:8080>. You are redirected to the development login
   page and can choose one of four seeded users (two parents, two children).

The application still starts if DynamoDB Local is not running; the home page
shows a warning banner instead of the data.

### The Slice 2 flow

1. Log in as `child1`. The home page lists the three chores. Tap one, optionally
   add a note, and submit.
2. Log out and log in as `parent1`. The approval queue shows the pending chore.
   Approve it (the reward is credited) or reject it with a reason.
3. Log in as `child1` again. The current-month total now includes the reward,
   and the history shows the status (or the rejection reason).

The seed chores require approval. Setting `requiresApproval` to `false` on a
chore credits it immediately without a queue item.

### Development login

`DevAuthMechanism` is a development-only `HttpAuthenticationMechanism`. It is
excluded from the production build by `@IfBuildProperty`. In production,
Cognito handles authentication.

## Tests

```bash
./mvnw test
```

The tests are hermetic. Web tests use `@TestSecurity`, and an in-memory
`@Alternative` implementation of every repository replaces DynamoDB, so neither
DynamoDB nor Cognito is needed.

DynamoDB transaction behavior (condition expressions, idempotent approval) is
smoke-tested against DynamoDB Local through the dev profile.

## Build

```bash
./mvnw package
```

The Quarkus AWS Lambda extension produces `target/function.zip`, which is the
artifact referenced by the SAM template.

## Deploy to AWS

The stack lives in `sam/template.yaml` and uses region `eu-central-1` by
default through the deployment command.

### 1. Build

```bash
./mvnw package
```

### 2. First deployment

The Cognito callback URL must contain the Lambda Function URL, but the Function
URL is only known after deployment. CloudFormation cannot resolve that cycle,
so the first deployment uses placeholder URLs and a second deployment fills
them in.

```bash
sam deploy \
  --template-file sam/template.yaml \
  --stack-name domaci-odmeny \
  --region eu-central-1 \
  --capabilities CAPABILITY_IAM \
  --resolve-s3 \
  --parameter-overrides \
    SessionSecret="$(openssl rand -base64 48)" \
    BudgetAlertEmail="you@example.com"
```

Keep the generated `SessionSecret` somewhere safe and reuse it on every
deployment; changing it invalidates existing sessions.

### 3. Second deployment with the real URL

Read the `FunctionUrl` output (it ends with a trailing slash):

```bash
aws cloudformation describe-stacks \
  --stack-name domaci-odmeny \
  --region eu-central-1 \
  --query "Stacks[0].Outputs[?OutputKey=='FunctionUrl'].OutputValue" \
  --output text
```

Redeploy with the URL, keeping the same `SessionSecret`:

```bash
sam deploy \
  --template-file sam/template.yaml \
  --stack-name domaci-odmeny \
  --region eu-central-1 \
  --capabilities CAPABILITY_IAM \
  --resolve-s3 \
  --parameter-overrides \
    SessionSecret="<the same secret>" \
    BudgetAlertEmail="you@example.com" \
    OidcCallbackUrl="https://<function-url-id>.lambda-url.eu-central-1.on.aws/q/oidc-callback" \
    OidcLogoutUrl="https://<function-url-id>.lambda-url.eu-central-1.on.aws/"
```

### 4. Create users

Self sign-up is disabled. Create the four accounts as an administrator and add
them to the right group:

```bash
aws cognito-idp admin-create-user \
  --user-pool-id <CognitoUserPoolId> \
  --username mama \
  --user-attributes Name=name,Value=Maminka \
  --message-action SUPPRESS

aws cognito-idp admin-add-user-to-group \
  --user-pool-id <CognitoUserPoolId> \
  --username mama \
  --group-name parents
```

Use group `children` for children. Set the first password with
`admin-set-user-password --permanent`.

> Member records in DynamoDB are not provisioned by the SAM stack yet. The
> `cognitoSub` of each Cognito user must be linked to a member record. Until a
> provisioning step exists, create the four `MEMBER#<id>` items in the Members
> table and set `cognitoSub` to the Cognito user's `sub`.

### Two-phase deployment caveat

Every later deployment must keep the real `OidcCallbackUrl`, `OidcLogoutUrl` and
`SessionSecret` values. Put them in a `samconfig.toml` or pass them explicitly.
`samconfig.toml` is gitignored because it may contain the session secret.

## Configuration

| Property | Environment variable | Default | Purpose |
|---|---|---|---|
| `app.household-id` | `HOUSEHOLD_ID` | `default` | Household partition key |
| `app.household-name` | `HOUSEHOLD_NAME` | `Naše domácnost` | Name shown in the UI |
| `app.currency` | `CURRENCY` | `CZK` | Household currency |
| `app.timezone` | `TIMEZONE` | `Europe/Prague` | Accounting period timezone |
| `app.seed.catalog` | `SEED_CATALOG` | `false` (`true` in prod) | Seed household + chore catalog if empty |
| `app.dynamodb.region` | `AWS_REGION` | `eu-central-1` | DynamoDB region |
| `app.dynamodb.endpoint-override` | — | empty | Local DynamoDB endpoint |
| `app.dynamodb.tables.members` | `MEMBERS_TABLE` | `Members` | Members table |
| `app.dynamodb.tables.tasks` | `TASKS_TABLE` | `Tasks` | Tasks table |
| `app.dynamodb.tables.activity` | `ACTIVITY_TABLE` | `Activity` | Activity table |
| `app.dynamodb.tables.pending` | `PENDING_TABLE` | `Pending` | Pending queue |
| `quarkus.oidc.auth-server-url` | `OIDC_AUTH_SERVER_URL` | — (prod only) | Cognito issuer URL |
| `quarkus.oidc.client-id` | `OIDC_CLIENT_ID` | — (prod only) | Cognito app client |
| `quarkus.oidc.credentials.secret` | `OIDC_CLIENT_SECRET` | — (prod only) | Cognito app client secret |
| `quarkus.oidc.token-state-manager.encryption-secret` | `OIDC_SESSION_SECRET` | — (prod only) | Session cookie encryption |

## Project structure

```text
src/main/java/cz/domaci/odmeny/
├── config/            # typed application configuration
├── shared/            # Money, Period — domain primitives
├── household/         # household settings and period/time helpers
├── member/            # Member domain, repository interface, service
├── task/              # chore catalog
├── completion/        # logging, approval workflow, pending queue
├── ledger/            # append-only ledger entries and earnings
├── security/          # dev-only login mechanism and resources
├── infrastructure/
│   └── dynamodb/      # client, repository adapters, local bootstrap
└── web/               # Qute-backed HTTP resources and view models

src/main/resources/
├── templates/         # Qute layout, child/parent pages, fragments
├── META-INF/resources # vendored HTMX, Pico.css, favicon
└── application.properties

sam/template.yaml      # Cognito, DynamoDB, Lambda + Function URL, guardrails
compose.yaml           # DynamoDB Local for development
```

## Design notes

- **Money** is always an integer number of haléře (`50 Kč = 5000`). See
  ADR-0006.
- **Periods** are calendar months in `Europe/Prague`.
- **Authorization** is enforced by HTTP path policies on the server; hiding a
  button is never an authorization control.
- **CSRF** uses the Quarkus `rest-csrf` double-submit cookie. HTMX sends the
  token as the `X-CSRF-TOKEN` header from the layout.
- **DynamoDB keys** follow ADR-0004. A member is looked up by querying the
  household partition and filtering on `cognitoSub`; four members do not
  justify a GSI. Household settings live under `SK=HOUSEHOLD#<id>` in the
  Members table.
- **Approval is atomic.** The completion update, the reward ledger entry and the
  queue removal are one DynamoDB transaction guarded by condition expressions,
  so a double approval or a lost race fails with a conflict instead of crediting
  twice.
- **The ledger is append-only.** Reward entries use a deterministic id
  (`reward-<completionId>`), and corrections in later slices will add new
  entries rather than edit old ones.
- **Tables are retained** when the stack is deleted, to protect financial
  history. Delete them manually if you really want a clean slate.

## Known gaps and follow-ups

- Production member provisioning and Cognito `sub` linking are not automated.
  In production the stack seeds the household and chore catalog only; member
  records must be created and linked to Cognito users separately.
- The proposal's settings UI (editing the catalog and rewards) is not built yet.
  Chores are seeded and can be changed directly in DynamoDB.
- Keycloak Dev Services is not used. The dev login mechanism keeps local
  startup fast and container-free while exercising the same domain and UI code
  as production.
- PWA manifest, offline behavior, exports, audit view and the runbook are
  later slices.
