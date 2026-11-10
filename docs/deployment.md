# Deployment

This document describes how to deploy **Domácí odměny** to AWS, how to create
the four family accounts, and how to verify a deployment. Operational
procedures (backups, restore, user recovery) live in
[`runbook.md`](runbook.md).

The stack is defined in [`sam/template.yaml`](../sam/template.yaml) and is
deployed with the AWS SAM CLI. The default region is `eu-central-1`.

## What gets created

| Resource | Purpose |
|---|---|
| Lambda function + Function URL | Runs the Quarkus application; the URL is the public endpoint |
| Amazon Cognito user pool, app client and groups | Username/password sign-in, `parents` and `children` groups |
| Four DynamoDB tables (`Members`, `Tasks`, `Activity`, `Pending`) | Provisioned 5 RCU / 5 WCU each, no GSIs, retained on stack deletion |
| CloudWatch log group | Lambda logs with 7-day retention |
| CloudWatch alarms | Lambda errors and throttles |
| AWS Budget | $1 monthly budget with an 80% actual and 100% forecasted email alert |

The application is stateless. All household data lives in DynamoDB, and all
identity data lives in Cognito.

## Prerequisites

- JDK 21
- AWS SAM CLI and AWS credentials with permission to create the resources above
- An email address for budget alerts (optional but recommended)
- The Maven wrapper is committed, so Maven does not need to be installed

## 1. Build

```bash
./mvnw package
```

This produces `target/function.zip`, the artifact referenced by the SAM
template. The build also runs the hermetic test suite. To skip the tests while
iterating:

```bash
./mvnw -DskipTests package
```

## 2. First deployment

The Cognito callback URL must contain the Lambda Function URL, but the Function
URL is only known after deployment. CloudFormation cannot resolve that cycle, so
the first deployment uses placeholder URLs and a second deployment fills them
in.

Generate a session secret once and keep it:

```bash
openssl rand -base64 48
```

Deploy:

```bash
sam deploy \
  --template-file sam/template.yaml \
  --stack-name domaci-odmeny \
  --region eu-central-1 \
  --capabilities CAPABILITY_IAM \
  --resolve-s3 \
  --parameter-overrides \
    SessionSecret="<generated secret>" \
    BudgetAlertEmail="you@example.com"
```

`SessionSecret` encrypts the OIDC session cookie. Reuse the same value on every
deployment; changing it invalidates all existing sessions.

## 3. Second deployment with the real URL

Read the Function URL output (it ends with a trailing slash):

```bash
aws cloudformation describe-stacks \
  --stack-name domaci-odmeny \
  --region eu-central-1 \
  --query "Stacks[0].Outputs[?OutputKey=='FunctionUrl'].OutputValue" \
  --output text
```

Redeploy with the URL and the same `SessionSecret`:

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

Every later deployment must keep the real callback/logout URLs and the same
`SessionSecret`. Store them in a gitignored `samconfig.toml` or pass them
explicitly.

## 4. Create the family accounts

Self sign-up is disabled. Accounts and household members are provisioned with
[`scripts/provision-household.sh`](../scripts/provision-household.sh), which
creates the Cognito users, adds them to the `parents` or `children` group, links
each user's `sub` to a member item, and creates the household record when it is
missing.

The script is idempotent. A newly created user gets a generated one-time
password printed once; an existing member item keeps the display name, base
allowance and active flag that parents may have changed in **Nastavení**.

### Prepare the member file

Copy the example outside the repository so personal data is never committed:

```bash
cp scripts/members.example.tsv ~/domaci-odmeny-members.tsv
$EDITOR ~/domaci-odmeny-members.tsv
```

The file is tab-separated:

```text
member-id   cognito-username   display-name   PARENT|CHILD   base-allowance-CZK
parent-1    mama               Maminka        PARENT         0
parent-2    tata               Táta           PARENT         0
child-1     anna               Anička         CHILD          50
child-2     petr               Petřík         CHILD          50
```

The allowance is a whole number of Czech crowns and is stored in haléře. `0`
means no base allowance. Member ids are stable; ledger entries and statements
reference them, so do not change an id after the household starts using the app.

### Run the provisioning script

```bash
./scripts/provision-household.sh \
  --stack-name domaci-odmeny \
  --region eu-central-1 \
  --members-file ~/domaci-odmeny-members.tsv
```

It reads `CognitoUserPoolId` and `MembersTable` from the stack outputs. Keep the
printed passwords safe and change them after the first sign-in. Re-run the same
command after adding a member; existing users and member records are left
intact.

The household record and the chore catalog are seeded automatically on first
start when `SEED_CATALOG=true` (the default in production). The seeded chores are
`Odpadky` (10 Kč), `Myčka` (10 Kč) and `Vysávání` (20 Kč); parents can change
them in **Nastavení**.

### Manual alternative

If the script cannot be used, create one account with the AWS CLI:

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

aws cognito-idp admin-set-user-password \
  --user-pool-id <CognitoUserPoolId> \
  --username mama \
  --password '<a permanent password>' \
  --permanent
```

Then read the `sub` and create the member item yourself:

```bash
aws cognito-idp admin-get-user \
  --user-pool-id <CognitoUserPoolId> \
  --username mama \
  --query "UserAttributes[?Name=='sub'].Value" \
  --output text
```

The `Members` table is `<stack-name>-Members` and uses the keys `PK` and `SK`:

| Attribute | Value |
|---|---|
| `PK` | `HOUSEHOLD#default` |
| `SK` | `MEMBER#parent-1` |
| `entityType` | `MEMBER` |
| `id` | `parent-1` |
| `householdId` | `default` |
| `cognitoSub` | the Cognito user's `sub` |
| `displayName` | `Maminka` |
| `role` | `PARENT` or `CHILD` |
| `baseAllowanceMinor` | base monthly amount in haléře, e.g. `5000` |
| `active` | `true` |

## 5. Verify the deployment

1. Open the `FunctionUrl` output in a browser.
2. Sign in with a parent account. The parent home page shows the approval queue
   and the children's monthly totals.
3. Open **Výplaty**, **Audit**, **Export** and **Nastavení**. All four pages must
   render.
4. In **Nastavení**, rename the household, rename a child, change the child's
   base allowance and add a chore. Reload the page; the values must persist.
   **Audit** must show the household rename, the member update and the new chore.
5. Sign in with a child account. The child sees only their own chores, history
   and statement; `/audit`, `/statements`, `/export` and `/settings` return
   `403`.
6. Log a chore as the child, approve it as the parent, and confirm the reward
   appears on the child's current-month total and in **Audit**.
7. Check the health endpoint:

   ```bash
   curl -s https://<function-url-id>.lambda-url.eu-central-1.on.aws/q/health
   ```

   It returns `{"status": "UP", ...}`.

## Updates and rollback

- Deploy a new version with the same `sam deploy` command and parameters.
- Lambda versions and aliases are not used; a rollback means redeploying the
  previous build of `target/function.zip` or running `sam deploy` from the
  previous commit.
- DynamoDB tables use `DeletionPolicy: Retain`, so replacing or deleting the
  stack does not delete household data. Delete the tables manually only when you
  really want a clean slate.
- The data model is append-only for money. A code rollback does not roll back
  data, so keep that in mind when changing statement or ledger semantics.

## Teardown

```bash
sam delete --stack-name domaci-odmeny --region eu-central-1
```

The four DynamoDB tables are retained. Delete them separately only after taking
a backup. See [`runbook.md`](runbook.md) for backup and restore procedures.

## Troubleshooting

| Symptom | Likely cause | Action |
|---|---|---|
| Login redirects to Cognito and fails with `redirect_mismatch` | `OidcCallbackUrl` does not match the Function URL | Redeploy with the exact URL, including `/q/oidc-callback` |
| Sessions are lost after every deployment | `SessionSecret` changed | Reuse the same secret |
| User signs in but sees no household data | Cognito `sub` is not linked to a `MEMBER#<id>` item | Create the member item with the correct `cognitoSub` |
| `403` on a parent page | User is not in the `parents` group | Add the user to the group and sign in again |
| `Databáze není dostupná` banner | Lambda cannot reach DynamoDB | Check the function role policy and the table names in the environment |
| Budget alert email | Spend or forecast crossed the threshold | Inspect Cost Explorer; see the cost section in the runbook |
