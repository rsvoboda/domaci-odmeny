# ADR-0003: Use AWS Lambda Function URL instead of API Gateway

- **Status:** Accepted
- **Date:** 2026-11-07

## Context

The application needs one HTTPS endpoint for a server-rendered Quarkus application. There are four users, low traffic, and a requirement to stay within AWS always-free limits.

API Gateway is not always-free for new AWS accounts, while Lambda Function URLs add no separate charge: requests are billed as Lambda requests. The application already needs Cognito authentication at the application level because it uses a server-rendered session cookie.

## Decision

Deploy the Quarkus application as a single AWS Lambda function and expose it through a **Lambda Function URL**.

- Function URL auth type: `NONE`
- Authentication is enforced by Quarkus OIDC and Cognito.
- No API Gateway in v1.
- No S3/CloudFront runtime hosting in v1.
- Lambda reserved concurrency is limited to reduce abuse risk.
- AWS Budgets provides a cost guardrail.

## Consequences

**Positive**

- No separate API Gateway charge.
- One less AWS service to configure and monitor.
- HTTPS endpoint is available immediately.
- Works well with server-rendered pages and cookies.
- Simple SAM template.

**Negative**

- Function URL does not provide API Gateway–style throttling, usage plans, or a built-in Cognito authorizer.
- The public endpoint relies on application-level authentication and validation.
- The default `*.on.aws` URL is not user-friendly.
- A custom domain later requires CloudFront, ACM, and Route 53, which are not free.

**Follow-up**

- Set Lambda reserved concurrency to 5.
- Add an AWS Budgets alarm at $1/month.
- Add application-level validation and rate-limit protection if abuse is observed.
- Revisit API Gateway or CloudFront only if the requirements change.
