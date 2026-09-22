package ai.kumbuka.admin.dto;

import ai.kumbuka.domain.Scope;
import ai.kumbuka.domain.TeamSettings;
import ai.kumbuka.domain.UiSettings;
import ai.kumbuka.service.WritePolicyResolver;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Response + request shapes for the admin REST API.
 *
 * <p>Entry shapes ({@code EntryView}, {@code RecentActivity} and the entry
 * requests) left with the memory engine. What remains describes scopes, team
 * settings, members and sessions — the administrative surface. Counts taken
 * from the entry tables are gone from the views that carried them rather than
 * reported as {@code 0}: a zero would assert an empty store, and the true
 * statement is that this service no longer counts it.
 */
public final class AdminDtos {

    private AdminDtos() {}

    // ---------- Responses --------------------------------------------------

    public record ScopeView(
        String slug,
        String name,
        String kind,
        boolean fixed,
        boolean archived,
        boolean locked,   // content read-only flag (lock icon)
        String description,
        Instant createdAt
    ) {
        public static ScopeView from(Scope s) {
            return new ScopeView(
                s.slug, s.name, s.kind.dbValue(),
                Boolean.TRUE.equals(s.fixed),
                Boolean.TRUE.equals(s.archived),
                Boolean.TRUE.equals(s.locked),
                s.description,
                s.createdAt
            );
        }
    }

    public record SettingsView(
        String writePolicy,
        String effectiveWritePolicy,
        String defaultScopeSlug,
        String defaultScopeStatus,
        String createScopes
    ) {
        public static SettingsView from(TeamSettings s, WritePolicyResolver.Resolved r) {
            return new SettingsView(
                s.getWritePolicy().dbValue(),
                r.effective().dbValue(),
                r.defaultScopeSlug(),
                r.defaultScopeStatus().name().toLowerCase(),
                s.getCreateScopes().dbValue()
            );
        }
    }

    public record OverviewView(
        long scopesTotal,
        long scopesArchived,
        List<MemberSummary> members
    ) {}

    public record MemberSummary(
        UUID id,
        String subject,
        String email,
        String displayName,
        String role,
        String status,
        boolean muted   // per-member mute
    ) {}

    public record SessionView(
        String subject,
        String email,
        String displayName,
        String role,
        String accountConsoleUrl,
        // Authorize-endpoint base for Keycloak Application Initiated Actions; the
        // console appends &redirect_uri=…&kc_action=… to deep-link password / 2FA
        // / passkey management. Carries a fresh PKCE challenge (kumbuka-admin
        // enforces S256). See SessionResource.securityActionUrl().
        String securityActionUrl,
        boolean muted,  // the caller's own mute state (drives the member notice)
        String locale,  // the caller's UI language preference (en | de); null = unset
        OnboardingState onboarding,  // per-user wizard dismiss/resume state
        // Per-user UI presentation settings — typed, presentation state ONLY
        // (boundary note on UiSettings). Always present in the view; for an
        // unset field the console falls back to its own default.
        UiSettings settings
    ) {}

    /**
     * onboarding-wizard state, per-user (keyed by KC sub). Serialized
     * as {@code {"dismissed": bool, "lastStep": int}} — mirrors the console seam
     * (SessionView.onboarding / UpdateMeRequest.onboarding). {@code dismissed}
     * once the owner opts out OR completes the wizard; {@code lastStep} is the
     * resume point while pending.
     */
    public record OnboardingState(boolean dismissed, int lastStep) {}

    /**
     * one of the caller's own active Keycloak sessions. Scoped to
     * {@code subject == caller} at the resource layer; never exposes another
     * member's session. {@code clients} are the OAuth clients seen on the
     * session (e.g. {@code kumbuka-admin}, {@code kumbuka-connector-<alias>}),
     * used only as a human label. {@code current} marks the session backing
     * this very request (best-effort, from the {@code sid} claim).
     */
    public record ActiveSessionView(
        String id,
        String ipAddress,
        Instant startedAt,
        Instant lastAccessAt,
        boolean rememberMe,
        List<String> clients,
        boolean current
    ) {}

    /**
     * one of the caller's own self-service credentials (an
     * authenticator app or a passkey / security key). Scoped to
     * {@code subject == caller} at the resource layer. Keycloak stores no
     * "last used", so only {@code userLabel} + {@code createdDate} are shown;
     * recovery-codes are never listed here (presence-only, see
     * {@link CredentialsView}).
     */
    public record CredentialView(
        String id,
        String type,          // otp | webauthn | webauthn-passwordless
        String userLabel,     // user-chosen label, may be null/blank
        Instant createdDate
    ) {}

    /**
     * the {@code GET /api/credentials} response — the caller's
     * self-service credentials plus a presence-only recovery-codes flag.
     * {@code recoveryCodesConfigured} is true when the caller holds a
     * {@code recovery-authn-codes} credential; the codes themselves are NEVER
     * read or returned (Keycloak renders them on its own themed AIA page, the
     * ratified reconciliation). The console uses the flag only to flip its
     * recovery card between GENERATE and RE-GENERATE.
     */
    public record CredentialsView(
        List<CredentialView> credentials,
        boolean recoveryCodesConfigured
    ) {}

    // ---------- Requests ---------------------------------------------------

    public record CreateScopeRequest(String slug, String name, String description) {}
    public record UpdateScopeRequest(String name, String description) {}

    public record UpdateSettingsRequest(
        String writePolicy,      // ask | project | global
        String defaultScopeSlug, // nullable; only used with writePolicy=project
        String createScopes      // admins | members
    ) {}

    /**
     * {@code settings} is a field-wise patch: only the fields it carries are
     * applied, everything else keeps its stored value (merge, not replace —
     * see {@code UiSettings.merge}). Unknown or wrong-typed settings fields
     * are rejected with 400 at deserialization, never ignored or stored.
     */
    public record UpdateMeRequest(
        String displayName, String locale, OnboardingState onboarding, UiSettings settings) {}
}
