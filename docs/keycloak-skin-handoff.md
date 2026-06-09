<!-- SPDX-License-Identifier: AGPL-3.0-only -->
# kumbuka.ai · Keycloak Skin — Handoff Spec

**Status:** draft · **all 16 login pages + account-look + 3 emails built** — ready for implementation session
**Date:** 2026-06-06
**Scope:** visual design + token mapping for the kumbuka Keycloak theme (login pages, account console look, transactional emails). This is a **reference**, not a wired theme — a follow-up implementation session turns these mockups into a real Keycloakify/FreeMarker theme.

The console is the sibling surface. These pages must feel like the same product the instant the user is redirected out to Keycloak, and again on the way back.

---

## 1 · Source of truth

| What | Where | Notes |
|---|---|---|
| Tokens | `console.css` (project root) | Ported verbatim into `design/keycloak/keycloak.css`. |
| Brand marks | `assets/brand/` | `kumbuka-mark.svg` (accent `#FF5B1F`), `…-white.svg` (knockout for deep ink), `…-current.svg` (inherits), `kumbuka-lockup.svg`. |
| Shared login CSS | `design/keycloak/keycloak.css` | Becomes `theme/kumbuka/login/resources/css/kumbuka.css`. |

> ⚠ The brief also lists `docs/kumbuka-concept.md` and `docs/DESIGN_HANDOFF.md` as authoritative. They are **not in the project yet** (placeholder noted in `docs/README.md`). This spec relies on the brief's inline restatement + `console.css`; any assumption that would normally be resolved by those docs is flagged **[ASSUMPTION]** below.

---

## 2 · Ratified decisions (ADR stubs)

These were proposed and adopted as the brief's recommended defaults. Each is cheap to reverse.

### ADR-01 · Canonical look = deep-ink editorial
Full-bleed deep-ink canvas (`#0F1620`) with a faint hairline grid, a centered **paper** card (`#F4F1EA`), radius 0, 1px hairline border, **no shadow**. Knot mark (accent) + white wordmark above the card; the single orange per view is the **primary button** (mark + button are vertically staggered, which the brand's orange-discipline rule permits).
**Rationale:** the login is the brand's front door; deep-ink is the most "console / developer-tool" of the three brand surfaces and contrasts the paper card cleanly.

### ADR-02 · One canonical look — **ratified**
Keycloak ships one canonical theme. **No `prefers-color-scheme` parity.** Rationale (ratified): the deep-ink frame around the paper card is already theme-neutral — dark outside, light inside, high contrast in any OS setting. Parity would double all 16 pages for no real gain, and there is no theme signal before login anyway (the theme cookie only arrives from the console). We did **not** build a light-canvas auth variant, by decision.

### ADR-03 · Self-hosted fonts — **ratified**
The three families are **self-hosted** in the theme — no external font CDN on the auth host (privacy, CSP, offline).
- **Bundle:** `woff2`, **subset latin + latin-ext**, license **OFL**, under `login/resources/fonts/` + `@font-face`; strict `font-src 'self'` CSP.
- **Exact weights to bundle (and nothing more):**
  - Space Grotesk — **500, 600, 700**
  - Inter — **400, 500, 600**
  - JetBrains Mono — **400, 500, 600**
- **Preview condition (ratified):** the mockups load the **same weight set** via CDN so the preview cannot promise a look the bundled theme won't hold. The pinned link is:
  `fonts.googleapis.com/css2?family=Space+Grotesk:wght@500;600;700&family=Inter:wght@400;500;600&family=JetBrains+Mono:wght@400;500;600&display=swap`

### ADR-04 · Login layout = centered single column, max 432px
Brand block → card → footer (locale + realm meta). Mobile collapses card padding only.

### ADR-05 · Copy language = English **[ASSUMPTION]**
Matches the kumbuka console and Keycloak's default English message bundle. The design-system brand voice is German; if these pages must be DE (or bilingual via `kc-locale`), the card copy and `messages_*.properties` keys change — structure does not.

---

## 3 · Token map — kumbuka → theme

CSS custom properties in `keycloak.css`, ported from `console.css`:

| Role | Token | Value |
|---|---|---|
| Canvas (page) | `--canvas` | `#0F1620` |
| Canvas deep | `--canvas-2` | `#0A0E14` |
| Card surface | `--paper` | `#F4F1EA` |
| Field surface | `--c-field` | `#FBFAF6` |
| Placeholder fill | `--paper-2` | `#EBE6DB` |
| Body ink | `--c-text` / `--ink` | `#141820` |
| Structure (navy) | `--navy` | `#2D4059` |
| **Signal orange** | `--accent` | `#FF5B1F` |
| Muted text | `--c-muted` | `rgba(15,22,32,.55)` |
| Hairline border | `--c-border` | `rgba(15,22,32,.18)` |
| On-canvas text | `--on-canvas` | `#F0EDE6` |
| OK / warn / bad | `--ok` `--warn` `--bad` | `#3E7C5E` `#D48C2E` `#C44536` |
| Display font | `--font-display` | Space Grotesk |
| Body font | `--font-body` | Inter |
| Mono font | `--font-mono` | JetBrains Mono |
| Radius | — | `0` everywhere |
| Shadow | — | none |

---

## 4 · Page inventory → Keycloak templates

Each mockup uses Keycloak's real form structure (field ids, labels, link slots, error placement) so it maps 1:1 to the `.ftl`.

### Login theme (`login/`) — FreeMarker, fully restylable

| Mockup | Keycloak template | Status |
|---|---|---|
| `mockups/login.html` | `login.ftl` | ✅ spine |
| `mockups/login-otp.html` | `login-otp.ftl` | ✅ spine |
| `mockups/error.html` | `error.ftl` | ✅ spine |
| `mockups/info.html` | `info.ftl` | ✅ spine |
| `mockups/login-config-totp.html` | `login-config-totp.ftl` | ✅ core |
| `mockups/select-authenticator.html` | `select-authenticator.ftl` | ✅ core |
| `mockups/webauthn-authenticate.html` | `webauthn-authenticate.ftl` | ✅ core |
| `mockups/webauthn-register.html` | `webauthn-register.ftl` | ✅ built |
| `mockups/login-update-password.html` | `login-update-password.ftl` | ✅ built (login form) |
| `mockups/login-update-profile.html` | `login-update-profile.ftl` | ✅ built (login form) |
| `mockups/login-reset-password.html` | `login-reset-password.ftl` | ✅ built (login form) |
| `mockups/login-verify-email.html` | `login-verify-email.ftl` | ✅ built (info) |
| `mockups/terms.html` | `terms.ftl` | ✅ built |
| `mockups/login-page-expired.html` | `login-page-expired.ftl` | ✅ built (info/error) |
| `mockups/logout-confirm.html` | `logout-confirm.ftl` | ✅ built |
| `mockups/login-info-logout.html` | `info.ftl` (logged-out variant) | ✅ built (info) |

**Core six (component coverage — sign-off gate):** `login` (standard form → also covers update-password/profile, reset), `login-otp` (code input), `login-config-totp` (layout-heaviest: image + secret + input), `webauthn-authenticate` (device/button interaction, no classic inputs), `select-authenticator` (choice list), `error`/`info` (message/end-state → covers page-expired, verify-email, logout, email-sent). The remaining ten are recombinations of these primitives with no new look decision.

> Slots designed, restrained/mono, kept in `login.html`: `#kc-social-providers` (IdP/SSO), `#kc-registration` (request-access). Enabling them later needs no redesign.

### Account console (`account/`) — React/PatternFly, **token map only**

Skinned via **PatternFly CSS variables + a logo**, not bespoke layout. Deliverable `account-look.html` is a representative preview + the variable map (kumbuka → `--pf-*`). We do **not** promise pixel-bespoke account pages — that's the platform constraint.
Surfaces: personal info · signing-in/credentials · two-factor · device activity · linked accounts.
Per the **hybrid link-out** decision: profile lives in the kumbuka console; credentials/MFA/passkey/sessions live here in the Keycloak account console.

### Emails (`email/`) — html + text

| Mockup | Keycloak template | Status |
|---|---|---|
| `email/invite.html` + `.txt` | `email/html/executeActions.ftl` (set-up-credentials invite) | ✅ built |
| `email/verify.html` + `.txt` | `email/html/email-verification.ftl` | ✅ built |
| `email/reset.html` + `.txt` | `email/html/password-reset.ftl` | ✅ built |

> **Email notes:** table-based, inline styles, web-safe font fallbacks (clients don't reliably load webfonts), deep-ink brand band over paper body. `{{LINK}}`, `{{LINK_EXPIRATION}}`, `{{USER_EMAIL}}` are placeholders for the FreeMarker expressions (`${link}`, `${linkExpiration}`, `${user.email}`). Preview logo points at a **local white-knockout PNG**; production swaps to the absolute `https://assets.kumbuka.ai/brand/kumbuka-mark-white.png`.

> Invite sets **no password** — the user creates credentials via the link (matches the console's "invite via IdP" flow).

---

## 5 · Accessibility notes

- **Contrast.** Body ink `#141820` on paper `#F4F1EA` ≈ 14:1. On-canvas text `#F0EDE6` on `#0F1620` ≈ 15:1. Orange `#FF5B1F` is used for **fills with dark text** (`#1a0d05` on accent ≈ 7:1) and for **links on paper** (`#FF5B1F` on `#F4F1EA` ≈ 3.1:1 — acceptable for ≥18px / bold link text and non-text accents; **do not** use accent for small body-size prose). Mono link sizes are ≥11px 500-weight; verify each in build.
- **Focus.** 2px accent ring, 2px offset, on every input/button/link/checkbox. Never `outline:none` without the ring (brand rule).
- **Labels.** Every field has a real `<label for>`; OTP uses `inputmode="numeric"` + `autocomplete="one-time-code"`; username/password carry correct `autocomplete`.
- **Error placement.** Global alert at top of `#kc-content-wrapper` (`.kc-alert`); field errors directly under the field (`.kc-input-error`) — mirrors Keycloak's `messagesPerField`.
- **Reduced motion.** No motion in these pages beyond hover color; nothing to gate.
- **Targets.** Buttons ≥44px tall; reveal/checkbox hit areas ≥32px.

---

## 6 · Open questions for you

1. **Dark-mode parity** (ADR-02) — wanted, or one canonical deep-ink look?
2. **Copy language** (ADR-05) — English, German, or bilingual via locale switch?
3. **Social/registration slots** — keep designed-but-hidden, or remove from markup entirely?
4. **Greenlight the full set** — proceed with the remaining 12 login pages + `account-look.html` + the 3 emails?
