# ADR-0002: Use a server-rendered Qute + HTMX UI

- **Status:** Accepted
- **Date:** 2026-11-07

## Context

The UI is small: a child logs a chore, a parent approves it, and both view a monthly statement. The primary device is a phone. The application should be quick to build, easy to secure, and free of a separate frontend deployment.

A single-page application would require a separate build pipeline, API versioning, browser token handling, CORS configuration, and more client-side state management.

## Decision

Use **server-rendered HTML with Qute**, enhanced with **HTMX** for partial updates:

- Qute templates rendered by Quarkus
- HTMX for approve/reject and other small interactions
- Pico.css for a lightweight mobile-first style
- HTMX and CSS served from the application; no CDN dependency
- no Node.js build step
- progressive enhancement where practical
- CSRF protection through Quarkus `rest-csrf`
- server-side authorization on every request

## Consequences

**Positive**

- One deployable artifact for UI and backend.
- Authentication uses secure cookies rather than browser-stored access tokens.
- No CORS or API versioning in v1.
- Less JavaScript and fewer moving parts.
- Fast initial page loads and simple caching behavior.
- Easy to keep the UI Czech-only and accessible.

**Negative**

- Less rich client-side interactivity than a SPA.
- HTMX-specific patterns must be understood by maintainers.
- A future native mobile app will require a separate JSON API.

**Follow-up**

- Keep application services reusable so a REST API can be added later.
- Vendor HTMX and CSS assets rather than loading them from a third party.
- Add a PWA manifest and icons for home-screen installation.
