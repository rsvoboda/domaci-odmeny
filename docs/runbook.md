# Operations runbook

This runbook covers backup, restore, user recovery, verification and cost
guardrails for **Domácí odměny**. Deployment steps are in
[`deployment.md`](deployment.md).

## Service summary

- **Compute:** one AWS Lambda function behind a Function URL, region
  `eu-central-1`.
- **Data:** four provisioned DynamoDB tables, each 5 RCU / 5 WCU:
  `Members`, `Tasks`, `Activity`, `Pending`.
- **Identity:** Amazon Cognito user pool with `parents` and `children` groups.
- **Money semantics:** append-only ledger; `Activity` holds completions, ledger
  entries, statements and audit events. Corrections, payouts and closes add new
  records rather than editing history.
- **Time:** accounting periods are calendar months in `Europe/Prague`.

The application is stateless. A lost Lambda or a redeploy does not lose data;
DynamoDB and Cognito are the only stateful services.

## Backup strategy

There are two complementary backups:

1. **Application export** — a parent opens **Export** and downloads
   `/export/data.json`, `/export/ledger.csv`, `/export/statements.csv` and
   `/export/audit.csv`. The JSON file is the complete, human-readable snapshot
   of the household. Keep these files outside AWS.
2. **DynamoDB backups** — AWS-side point-in-time recovery and on-demand backups
   protect against accidental writes and table deletion.

### Recommended schedule

| What | When | Retention |
|---|---|---|
| Application JSON + CSV export | Monthly, after closing the previous month | Keep forever (small files) |
| DynamoDB on-demand backup of all four tables | Before every schema change, bulk correction or risky deployment | 35 days or longer |
| DynamoDB point-in-time recovery (PITR) | Continuous, if enabled | 35 days |

The export is the primary disaster-recovery artifact because it is independent
of AWS and covers the whole household in one file. DynamoDB backups are the
fastest way to restore the exact table state.

### Enable point-in-time recovery

PITR is not enabled by the SAM template to keep the stack inside the free tier.
Enable it once per table when the household starts carrying data you cannot
recreate:

```bash
for table in Members Tasks Activity Pending; do
  aws dynamodb update-continuous-backups \
    --region eu-central-1 \
    --table-name "domaci-odmeny-$table" \
    --point-in-time-recovery-specification PointInTimeRecoveryEnabled=true
done
```

Check the status:

```bash
aws dynamodb describe-continuous-backups \
  --region eu-central-1 \
  --table-name domaci-odmeny-Activity \
  --query "ContinuousBackupsDescription.PointInTimeRecoveryDescription.PointInTimeRecoveryStatus"
```

### Take an on-demand backup

```bash
STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
for table in Members Tasks Activity Pending; do
  aws dynamodb create-backup \
    --region eu-central-1 \
    --table-name "domaci-odmeny-$table" \
    --backup-name "domaci-odmeny-$table-$STAMP"
done
```

List backups:

```bash
aws dynamodb list-backups \
  --region eu-central-1 \
  --query "BackupSummaries[?contains(BackupName, 'domaci-odmeny')].[BackupName,BackupStatus,BackupCreationDateTime]" \
  --output table
```

## Restore procedure

DynamoDB backups are **per table**. There is no cross-table transactional
restore. Restore all four tables from the same point in time, then verify the
relationships (members, completions, ledger entries, statements, audit events).

### Option A — restore into new tables and repoint the application

This is the safe option because it leaves the current tables untouched.

1. Stop writes: temporarily set the Lambda reserved concurrency to `0`, or
   change the Function URL auth type so nobody can write while restoring.

   ```bash
   aws lambda put-function-concurrency \
     --region eu-central-1 \
     --function-name domaci-odmeny-app \
     --reserved-concurrent-executions 0
   ```

2. Restore each table to a new name:

   ```bash
   aws dynamodb restore-table-from-backup \
     --region eu-central-1 \
     --target-table-name "domaci-odmeny-Activity-restored" \
     --backup-arn "<backup-arn>"
   ```

   Repeat for `Members`, `Tasks` and `Pending`. Wait until all four tables are
   `ACTIVE`:

   ```bash
   aws dynamodb wait table-exists --region eu-central-1 --table-name domaci-odmeny-Activity-restored
   ```

3. Point the Lambda at the restored tables by updating the environment
   variables (`MEMBERS_TABLE`, `TASKS_TABLE`, `ACTIVITY_TABLE`,
   `PENDING_TABLE`) in the SAM template or with
   `aws lambda update-function-configuration`.

4. Re-enable writes by restoring the reserved concurrency (the template value is
   `5`):

   ```bash
   aws lambda put-function-concurrency \
     --region eu-central-1 \
     --function-name domaci-odmeny-app \
     --reserved-concurrent-executions 5
   ```

5. Verify with the checklist below.

6. After a successful verification, rename or delete the old tables during a
   quiet period.

### Option B — restore in place with PITR

Only use this when you are certain about the target time. Restoring in place
requires deleting the current table, so take an on-demand backup first.

```bash
# 1. Take a safety backup of the current table.
aws dynamodb create-backup \
  --region eu-central-1 \
  --table-name domaci-odmeny-Activity \
  --backup-name "pre-restore-$(date -u +%Y%m%dT%H%M%SZ)"

# 2. Restore the table to a new name, then follow option A.
aws dynamodb restore-table-to-point-in-time \
  --region eu-central-1 \
  --source-table-name domaci-odmeny-Activity \
  --target-table-name domaci-odmeny-Activity-restored \
  --restore-date-time "2026-11-09T20:00:00Z"
```

PITR always restores into a new table; the last step is the same as option A.

### Restore from the application export

The JSON export is a backup of last resort. It is not directly importable by the
application yet, so use it to reconstruct data manually (for example with a
small script that writes `PutItem` calls) if AWS backups are unavailable. Keep
the export alongside the DynamoDB backups.

## Recovery objectives

| Objective | Target |
|---|---|
| **RPO** (maximum data loss) | Up to 24 hours without PITR; 5 minutes with PITR enabled |
| **RTO** (time to restore service) | About 1 hour for the table-restore procedure, plus verification |

The application export is taken monthly, so it is a coarse-grained backup. PITR
is the recommended option once the household has several months of history.

## Cognito user recovery

Cognito is the identity source. The user pool does not export passwords, so
password recovery is always an administrative reset.

Household members and Cognito links are normally provisioned with
[`scripts/provision-household.sh`](../scripts/provision-household.sh). A re-run
repairs a missing member item or a stale `cognitoSub` while keeping the
app-managed display name, allowance and active flag.

### Reset a password

```bash
aws cognito-idp admin-set-user-password \
  --user-pool-id <CognitoUserPoolId> \
  --username mama \
  --password '<new temporary password>' \
  --permanent
```

To force a change at next sign-in, omit `--permanent` and send a temporary
password instead.

### Unlock or re-enable a user

```bash
aws cognito-idp admin-enable-user \
  --user-pool-id <CognitoUserPoolId> \
  --username mama
```

### Recreate a deleted user

1. Create the user again with `admin-create-user`.
2. Add it to `parents` or `children`.
3. Read the new `sub`.
4. Update the member item in the `Members` table so `cognitoSub` matches the new
   `sub`. The member `id`, display name, role and base allowance stay unchanged,
   so the child's history and balance are preserved. Re-running
   `scripts/provision-household.sh` with the same member file performs steps 1–4
   without overwriting the app-managed member values.

If the member item is also lost, recreate it with the same `id`; ledger entries
and statements reference member ids, not Cognito subjects.

### Lost access to the only parent account

Use an AWS administrator to reset the parent's password (above). There is no
in-app password reset flow. If the parent's Cognito user was deleted, recreate
it and relink the member record. Do not create a second member record for the
same person; that would split their history.

## Verification checklist

Run this after a restore, a deployment or a Cognito change.

1. **Health**

   ```bash
   curl -s https://<function-url-id>.lambda-url.eu-central-1.on.aws/q/health
   ```

   Expect `{"status": "UP", ...}`.

2. **Sign-in** — a parent and a child can sign in with their username/password.
3. **Authorization** — the child receives `403` for `/audit`, `/statements`,
   `/export` and `/settings`; the parent can open all four.
4. **Money continuity** — open **Výplaty** and compare the current and previous
   month totals with the last export. Closed and paid statements must match.
5. **Settings continuity** — open **Nastavení** and confirm the household name,
   member names, allowances and the chore catalog match the last known state.
   Save one value and confirm **Audit** records the change.
6. **Audit continuity** — open **Audit** for the current month and confirm the
   most recent actions are present and attributed to the right actor.
7. **Pending queue** — the parent home page shows the same pending items as
   before the incident (or none, if the queue was empty).
8. **Export** — download `/export/data.json` and confirm it parses as JSON and
   contains the expected member count.

Record the verification result and the backup used in the incident notes.

## Cost guardrails

The stack is designed to stay inside the AWS free tier:

- Four provisioned DynamoDB tables at 5 RCU / 5 WCU.
- Lambda memory 1024 MB and reserved concurrency 5.
- CloudWatch log retention 7 days.
- No NAT gateway, no load balancer, no always-on compute.

The SAM template creates a $1 monthly budget with two email notifications:

- **Actual 80%** — spend has already crossed 80% of the budget.
- **Forecasted 100%** — the projected month-end spend will exceed the budget.

### When a budget alert fires

1. Open **AWS Cost Explorer** and group by service and by usage type.
2. Check for unexpected Lambda invocations, DynamoDB read/write capacity or
   CloudWatch log ingestion.
3. Confirm the Lambda reserved concurrency is still `5`.
4. Check that no table was switched from provisioned to on-demand and that no
   extra table or backup was created.
5. If the spend is legitimate (for example PITR was enabled), update the budget
   limit and document the decision.
6. If the spend is unexpected, disable the offending resource and rotate any
   credentials that may have leaked.

### CloudWatch alarms

| Alarm | Meaning | First action |
|---|---|---|
| `ErrorsAlarm` | The Lambda returned at least one error in 5 minutes | Open the log group `/aws/lambda/<stack>-app` and inspect the stack trace |
| `ThrottlesAlarm` | The Lambda was throttled | Check reserved concurrency and invocation spikes |

Logs are JSON in production and are retained for 7 days.

## Routine checks

| Frequency | Check |
|---|---|
| Weekly | CloudWatch alarms and the Lambda error rate |
| Monthly | Download the application export after closing the month; verify a backup exists |
| Monthly | Review the budget and Cost Explorer |
| Quarterly | Restore one table into a scratch name to prove the backup is usable, then delete it |
| On every deployment | Re-run the verification checklist |
| On every credential or user change | Confirm the member `cognitoSub` links are still correct |

## Incident checklist

1. **Assess** — is the problem compute (Lambda), data (DynamoDB), identity
   (Cognito) or configuration (environment variables)?
2. **Protect data** — if writes are dangerous, set reserved concurrency to `0`
   before changing anything.
3. **Communicate** — tell the household whether logging is paused and when it
   will resume.
4. **Recover** — use the smallest intervention: redeploy, reset a password,
   restore one table, or restore all four.
5. **Verify** — run the checklist above.
6. **Document** — record the timeline, the backup used, the data range affected
   and the follow-up actions.
