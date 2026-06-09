# Claude Code — Implementation Session: kumbuka.ai Keycloak theme

Build the **kumbuka Keycloak theme** (login + account + email), wire it into the
realm import and Docker Compose, and verify the full flow. Faithful to the design
session's mockups and handoff.

## Read first — authoritative, in this order
1. `docs/keycloak-skin-handoff.md` + `design/keycloak/` — the visual spec and
   mockups (the binding reference for this session).
2. `docs/kumbuka-concept.md` (§5 auth topology, §8 brand, §6/§F account link-out)
   and `docs/DESIGN_HANDOFF.md` (§B tokens, §H naming).
3. `design/prototype/console.css` — token source.
4. The current scaffold: `docker-compose.yml`, `keycloak/realm-import/` (the realm
   being rebranded `memory*` → `kumbuka` per §H), any Caddy / auth-host config.
5. **Confirm the pinned Keycloak version from the compose image tag and follow
   that version's official theming docs.** The login theme is FreeMarker; the
   account and admin consoles are React/PatternFly. Adapt to the version — do not
   assume.

## What to build
A single theme named **`kumbuka`** under `keycloak/themes/kumbuka/`:
- **`login/`** — FreeMarker templates extending `parent=keycloak.v2`; override
  *only* what the design changes. `theme.properties` (parent, styles, locales,
  any common imports); `resources/` (CSS built from `console.css` tokens,
  self-hosted `woff2` fonts, the brand logos incl. white-knockout). Cover the full
  page set from the handoff, **including `error.ftl` and `info.ftl`**.
- **`account/`** — skin the React account console via `theme.properties` +
  **PatternFly CSS variables** per the token map: brand logo, colors, fonts. **Do
  NOT fork the React app.** If a requested change isn't reachable through the
  supported variables/properties, flag it rather than rewriting the console.
- **`email/`** — branded html+text templates for enrolment/invite, email
  verification, password reset; keep all message interpolation intact.

## Non-negotiables (correctness / anti-slop)
- **Preserve Keycloak semantics.** Keep every form field name, action URL, hidden
  input, error/message block, and `${msg(...)}` / `${kcSanitize(...)}`
  interpolation. **Never hardcode user-facing strings** — use/extend the message
  bundles (English now; structured so more locales drop in later). Don't break
  "remember me", forgot-password, OTP, WebAuthn/passkey, IdP buttons, or
  required-action flows.
- **CSP-safe.** No external font/style/script origins on the auth host —
  everything from the theme's `resources/`. If a page needs inline styles/scripts,
  route them through the theme properly rather than weakening Keycloak's
  Content-Security-Policy.
- **Reproducible assignment.** Set `loginTheme` / `accountTheme` / `emailTheme` =
  `kumbuka` in the **`kumbuka` realm import JSON** — not by hand in the admin UI.
  The brand must come up from a clean `compose up`.
- **Dev vs prod.** Dev: mount `keycloak/themes/kumbuka` into
  `/opt/keycloak/themes` and disable theme caching (e.g.
  `--spi-theme-cache-themes=false`, `--spi-theme-static-max-age=-1`) so edits are
  live. Prod: bake the theme into the image (or a theme JAR) — document both in the
  README.
- **Accessibility.** Labels bound to inputs, visible focus rings (the design's
  orange), sufficient contrast on deep ink, sane tab order, page `<title>` and
  `lang` set.

## Verify before "done"
- `compose up` with the `kumbuka` realm → the BFF redirect lands on the **skinned**
  sign-in; signing in returns cleanly to the console.
- Walk the set: bad password (error styling), forgot-password, OTP if enabled,
  passkey register + authenticate, forced password update, verify-email, terms,
  page-expired, logout, and a raw error page — **all branded**.
- The invite/enrolment email renders branded in both html and text.
- The account console shows the kumbuka brand (logo/colors/fonts), and the
  link-out from the console reaches it.
- **No external network requests** from the auth pages (fonts/styles local); no
  CSP violations in the browser console.

## Deliverables & structure
`keycloak/themes/kumbuka/{login,account,email}/…`; theme assignment in the realm
import; the compose volume + dev flags; a prod note + theme-build step in the
README; `docs/adr/` for any decision taken (e.g. dark-mode parity, font
self-hosting). **Touch presentation and theme wiring only — no app/auth logic.**

## Discipline
Plan first: show the theme directory layout, the list of templates you'll
**override vs inherit**, and the compose/realm changes — and **wait for my go**
before scaffolding. Where the design and Keycloak's structure conflict, keep
Keycloak's contract and flag the conflict. Don't invent decisions; ask or record
an ADR. English artifacts. AGPL-3.0.
