# ADR-0005: Use Cognito username/password authentication

- **Status:** Accepted
- **Date:** 2026-11-07

## Context

The application has exactly four users: two parents and two children. Accounts are created by the parents; there is no public registration. Children may not have their own email addresses. The application must not store or manage passwords itself.

Amazon Cognito provides a free tier of 10,000 monthly active users and supports username sign-in, groups, hosted login, and password reset.

## Decision

Use **Amazon Cognito** for authentication:

- one user pool per environment
- username sign-in
- self-sign-up disabled
- parent-managed accounts
- optional email for parents; username-only accounts allowed for children
- groups `parents` and `children`
- Cognito Hosted UI with the authorization code flow
- Quarkus OIDC `web-app` application type
- stateless, encrypted session cookie
- Cognito `sub` mapped to a `Member` record in DynamoDB
- roles derived from Cognito groups
- password reset handled by a parent/admin through Cognito
- MFA optional for parents, deferred for children

## Consequences

**Positive**

- No custom password storage or password-reset flow.
- Free for four users.
- Standard OIDC integration with Quarkus.
- Group-based roles map cleanly to parent/child permissions.
- Works without children having email addresses.
- Cognito handles token issuance and refresh.

**Negative**

- Cognito Hosted UI is less customizable than a custom login page.
- Account provisioning and password resets require admin API calls or console use.
- Cognito adds an external dependency and some configuration complexity.
- A custom login experience would require more work later.

**Follow-up**

- Brand the Hosted UI with the family name.
- Provide a small admin script or page for account creation and password resets.
- Keep the OIDC session cookie encrypted with a stable secret from environment configuration.
- Add MFA for parents later if desired.
