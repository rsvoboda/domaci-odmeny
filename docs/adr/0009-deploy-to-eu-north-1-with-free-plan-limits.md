# ADR-0009: Deploy to eu-north-1 with Free-plan limits

- **Status:** Accepted
- **Date:** 2026-11-11
- **Partially supersedes:** [ADR-0008](0008-use-sam-and-free-tier-guardrails.md)

## Context

ADR-0008 chose `eu-central-1` (Frankfurt) as the deployment region and put an
AWS Budgets resource in the SAM stack. The actual target account has two
constraints that were discovered during the first deployment:

1. An Organizations service control policy (`p-glnwopnj`) explicitly denies
   CloudFormation, Lambda, DynamoDB, Cognito, CloudWatch Logs, SNS, SQS, Events,
   EC2 and API Gateway in every region except `eu-north-1` (Stockholm).
2. The AWS Free plan rejects Lambda functions with more than 512 MB of memory
   (`'MemorySize' value failed to satisfy constraint: Member must have value
   less than or equal to 512`).
3. CloudFormation in `eu-north-1` does not recognize the
   `AWS::Budgets::Budget` resource type, even when the resource is behind a
   false condition, so the stack cannot be created there with the budget
   resource present.

The application itself has no Frankfurt-specific dependency. Accounting periods
are defined in `Europe/Prague`, which is independent of the AWS region.

## Decision

- Deploy the stack to **`eu-north-1`** and make it the default region in the
  deployment documentation, scripts and configuration defaults.
- Set `LambdaMemorySize` to **512 MB** by default, with a note that the Free
  plan caps it there. The parameter can still be raised on an account that
  allows more.
- Keep **`AWS::Budgets::Budget` out of the SAM stack**. Create the optional $1
  budget with [`scripts/create-budget.sh`](../../scripts/create-budget.sh),
  which uses the Budgets API directly and is idempotent.
- Keep the timezone, currency and data model unchanged. Only the AWS region
  differs from ADR-0008.

## Consequences

**Positive**

- The stack deploys successfully in the only region the account permits.
- The budget guardrail remains available, just outside the stack.
- The template is portable: on an account without the SCP it can be deployed
  to `eu-central-1` again by changing `--region` (and optionally re-adding the
  budget resource).

**Negative**

- Household data is stored in Stockholm rather than Frankfurt. Both are in the
  EU, but this is a deviation from the original data-residency note in the
  proposal.
- The budget is not part of the stack, so a `sam delete` does not remove it and
  a fresh account needs one extra script run.
- Lambda cold starts may be slightly slower at 512 MB than at 1024 MB.

**Follow-up**

- If the SCP is relaxed, move the deployment back to `eu-central-1` and update
  this ADR and the deployment docs. DynamoDB data would need a migration or a
  fresh start.
- Revisit the Lambda memory size when the account moves to a paid plan.
