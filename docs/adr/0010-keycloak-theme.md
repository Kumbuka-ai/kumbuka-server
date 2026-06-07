# ADR-0010: kumbuka Keycloak theme — scope, parent themes, packaging

- Status: Accepted
- Date: 2026-06-06

## Context

Keycloak sits in the middle of two user journeys: an admin opens the
console and the BFF redirects them to Keycloak for sign-in; a member is
invited by an admin and the first surface they see is a Keycloak
transactional email + the same sign-in page. The handoff
(`docs/keycloak-skin-handoff.md`) specifies 16 login pages, the account
console, and 3 email templates — all in the kumbuka deep-ink editorial
look. The questions this ADR pins down are *what we build*, *what we
deliberately don't build*, and *how it ships*.

## Decision

### Scope

A single theme `kumbuka/` under `keycloak/themes/`, declaring three
sub-themes:

- **`login/`** — FreeMarker, parent `keycloak.v2`. Overrides
  `template.ftl` plus the 14 page templates listed in the handoff
  (login, login-otp, login-config-totp, webauthn-{authenticate,
  register}, select-authenticator, login-{reset,update}-password,
  login-update-profile, login-verify-email, login-page-expired,
  terms, logout-confirm, error, info). Other templates (register,
  login-username/password split, x509, recovery codes, oauth-grant,
  IdP link, etc.) **inherit**: our overridden `template.ftl` already
  carries the brand frame around them. Inheriting keeps the surface
  area small and lets upstream KC fixes land for free.

- **`account/`** — parent `keycloak.v3` (the React + PatternFly v5
  account console shipped with KC 26). We **skin via PatternFly CSS
  variables + a logo**, not by forking the React app. If a brand
  request can't be expressed through a PF variable or a `logo`
  property override, it gets flagged here as a follow-up rather than
  swapped in via a JS bundle override.

- **`email/`** — parent `base`. Overrides the three templates the
  brand has art-directed (`executeActions`, `email-verification`,
  `password-reset`), each as `html` + `text`. Other transactional
  emails inherit the base look.

### Parent themes & version pinning

`keycloak:26.0` is pinned via the compose image tag. The
`keycloak.v2` (login) and `keycloak.v3` (account) parents are
verified against that release. When we bump Keycloak, this ADR
gets revisited — moving between major login-theme parents (v2 → v3
on the login side, when KC ships it) is a breaking change for
overridden templates.

### Look constraints (ratified from the handoff)

- **One canonical deep-ink look.** No `prefers-color-scheme` parity.
  The deep-ink canvas + paper card is theme-neutral; we'd otherwise
  double 16 pages for no real gain.
- **Self-hosted fonts.** Inter 400/500/600, Space Grotesk 500/600/700,
  JetBrains Mono 400/500/600 — `woff2`, subset latin + latin-ext,
  OFL-1.1, under `<theme>/resources/fonts/`. No external font origins.
  Bundled to ~308 KB.
- **CSP-safe.** No inline `<script>` content the brand authored (KC's
  own WebAuthn module scripts stay — they're shipped by upstream). No
  inline `onclick` / `onchange` / `onsubmit`. Our enhancers live in
  `resources/js/kumbuka.js` and bind via `data-*` attributes.
- **Strings live in `messages_en.properties`.** We override only what
  the brand actually changes the wording of — we never hardcode
  user-facing strings into the FTL.

### Packaging

- **Dev:** the theme is bind-mounted into the canonical Keycloak image
  at `/opt/keycloak/themes/kumbuka` and Keycloak runs with theme
  caching off (`--spi-theme-cache-{themes,templates}=false`,
  `--spi-theme-static-max-age=-1`). Edits hot-reload.
- **Prod:** the theme is baked into a dedicated image
  (`keycloak/Dockerfile` → `ghcr.io/kumbuka-ai/kumbuka-keycloak`).
  Caching stays on, no bind mount. The `prod-kc` compose profile
  swaps the bake-image service in for the bind-mount dev service.

### Assignment

`loginTheme`, `accountTheme`, and `emailTheme` are set to `"kumbuka"`
on the realm via the realm import JSON. The brand comes up from a
clean `compose up`; no manual admin-UI step.

## Consequences

- A future KC upgrade that changes a `template.ftl` macro signature
  in `keycloak.v2` would silently break our override. Mitigation: the
  override is intentionally tiny (one wrapper + the page templates
  whose markup the brand re-flows); a KC upgrade gets a manual diff
  pass before being landed.
- Inheriting unstyled templates (register, recovery codes, etc.)
  means a few flows render in the parent's look until someone enables
  them. The handoff calls them out; they remain a one-template add
  later.
- Account-console limit: PatternFly CSS-variable overrides reach
  ~95% of the brand decisions. The few that don't (e.g. micro-spacing,
  custom illustrations) get caught in code review and either accepted
  as-is or escalated to upstream PatternFly.
- The font bundle (~308 KB) is the only blob committed to the repo
  besides brand SVGs. Reproducibility was preferred over fetching at
  build time — a build-time fetch would tie us to a third-party host
  for what is a one-time pull.
