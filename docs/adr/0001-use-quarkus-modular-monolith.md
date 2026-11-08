# ADR-0001: Use a Quarkus modular monolith

- **Status:** Accepted
- **Date:** 2026-11-07

## Context

The application has four users, one household, and a small, well-understood domain: members, chores, completions, a ledger, and monthly statements. It must run on AWS with minimal operational effort and near-zero cost. The preferred implementation stack is Java/Quarkus.

A microservice architecture would add deployment units, network calls, distributed transactions, and operational overhead without providing any meaningful benefit at this scale.

## Decision

Build a single Quarkus application as a **modular monolith**:

- Java 21
- Quarkus 3.x
- one Maven module
- feature-oriented packages such as `member`, `task`, `completion`, `ledger`, `statement`, and `security`
- clear separation between web, application, domain, and infrastructure code
- one deployable Lambda function

The domain model must not depend on AWS APIs. DynamoDB access belongs in infrastructure adapters behind repository interfaces.

## Consequences

**Positive**

- One artifact to build, test, deploy, and monitor.
- Simple local development and debugging.
- Local transactions are straightforward.
- The codebase stays understandable for one developer.
- Modules can be extracted later if the application ever grows.

**Negative**

- The whole application scales as one unit.
- Package boundaries require discipline; there is no compiler-enforced module boundary unless we add one later.
- A bug in one feature can affect the whole application.

**Follow-up**

- Keep domain logic free of Quarkus and AWS annotations where practical.
- Add tests around module boundaries and authorization rules.
