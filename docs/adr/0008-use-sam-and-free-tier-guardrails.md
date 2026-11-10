# ADR-0008: Use SAM and free-tier guardrails

- **Status:** Accepted (region and budget details partially superseded by [ADR-0009](0009-deploy-to-eu-north-1-with-free-plan-limits.md))
- **Date:** 2026-11-07

## Context

The application must be hosted on AWS using a brand-new free account. The runtime should stay at $0 within always-free limits. The infrastructure is small: one Lambda function, four DynamoDB tables, one Cognito user pool, a Function URL, and CloudWatch logging.

A new AWS account today is a 6-month Free plan with up to $200 in credits; the account closes after 6 months or when credits are exhausted. Long-term operation therefore requires upgrading to a Paid plan while continuing to use always-free services.

## Decision

Use **AWS SAM** for infrastructure as code and enforce free-tier guardrails:

- one SAM stack per environment
- region `eu-central-1` (Frankfurt)
- Lambda + Function URL
- four DynamoDB tables with 5 RCU / 5 WCU each
- Cognito user pool and Hosted UI domain
- CloudWatch log group with 7-day retention
- AWS Budgets alert at $1/month
- Lambda reserved concurrency of 5
- no API Gateway, RDS, EC2, NAT Gateway, or custom domain in v1
- JVM Lambda build first; Quarkus native later only if cold starts become a problem
- GitHub Actions with OIDC for deployment; no long-lived AWS keys

## Consequences

**Positive**

- Reproducible infrastructure with a small, readable template.
- No servers to patch.
- Runtime stays within always-free limits at family scale.
- Budget and log-retention guardrails reduce the risk of surprise charges.
- The architecture is easy to tear down and recreate.

**Negative**

- SAM is less flexible than CDK for complex infrastructure.
- The new AWS Free plan expires after 6 months; the account must be upgraded to Paid to keep the app.
- Deployment artifacts may use a small S3 bucket, which can incur negligible cost after credits expire.
- Function URL lacks API Gateway–style throttling and custom-domain support.
- JVM Lambda cold starts are slower than native.

**Follow-up**

- Enable MFA on the AWS root account.
- Create an IAM admin identity for daily work.
- Set the budget alert before deploying the application.
- Document the upgrade-to-Paid step and the expected $0 runtime cost.
- Add a custom domain only if the family decides the `*.on.aws` URL is not acceptable.
