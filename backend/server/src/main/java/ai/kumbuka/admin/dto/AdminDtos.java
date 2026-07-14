package ai.kumbuka.admin.dto;

import ai.kumbuka.domain.Memory;
import ai.kumbuka.domain.Scope;
import ai.kumbuka.domain.TeamSettings;
import ai.kumbuka.domain.UiSettings;
import ai.kumbuka.service.WritePolicyResolver;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Response + request shapes for the admin REST API. Mirrors handoff §D.
 * Never surfaces private rows (ADR-0003); per-scope endpoints reject the
 * private slug entirely.
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
        boolean locked,   // FEAT-19 / D-CORE-18: content read-only flag (lock icon)
        String description,
        long entryCount,
        Instant createdAt
    ) {
        public static ScopeView from(Scope s, long entryCount) {
            return new ScopeView(
                s.slug, s.name, s.kind.dbValue(),
                Boolean.TRUE.equals(s.fixed),
                Boolean.TRUE.equals(s.archived),
                Boolean.TRUE.equals(s.locked),
                s.description,
                entryCount,
                s.createdAt
            );
        }
    }

    public record EntryView(
        UUID logicalId,       // ADR-0024 §2/§8 (Amendment 3): the entry's reference identity
        String type,
        String key,
        String content,
        String reference,     // D-CORE-7: optional external provenance URL
        String authorSubject, // first-author (v1 creator) — immutable
        String source,        // create channel
        String updatedBy,     // Amendment 4: last-editor subject (null if never edited)
        String updatedSource, // Amendment 4: last-edit channel (null if never edited)
        Instant createdAt,
        Instant updatedAt
    ) {
        public static EntryView from(Memory m) {
            return new EntryView(
                m.logicalId, m.type.dbValue(), m.key, m.content, m.reference,
                m.ownerSubject, m.source.dbValue(),
                m.updatedBy, m.updatedSource == null ? null : m.updatedSource.dbValue(),
                m.createdAt, m.updatedAt
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
        long entriesTotal,
        Map<String, Long> entriesByType,
        List<RecentActivity> recent,
        List<MemberSummary> members
    ) {}

    public record RecentActivity(
        UUID entryId,
        String scopeSlug,
        String type,
        String key,
        String authorSubject,
        String source,
        Instant updatedAt
    ) {
        public static RecentActivity from(Memory m) {
            return new RecentActivity(
                m.logicalId, m.scope.slug, m.type.dbValue(), m.key,
                m.ownerSubject, m.source.dbValue(), m.updatedAt
            );
        }
    }

    public record MemberSummary(
        UUID id,
        String subject,
        String email,
        String displayName,
        String role,
        String status,
        boolean muted   // D-CORE-2
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
        boolean muted,  // D-CORE-2: the caller's own mute state (drives the member notice)
        String locale,  // the caller's UI language preference (en | de); null = unset
        OnboardingState onboarding,  // D-CORE-10.1: per-user wizard dismiss/resume state
        // Per-user UI presentation settings — typed, presentation state ONLY
        // (boundary note on UiSettings). Always present in the view; for an
        // unset field the console falls back to its own default.
        UiSettings settings
    ) {}

    /**
     * D-CORE-10.1 onboarding-wizard state, per-user (keyed by KC sub). Serialized
     * as {@code {"dismissed": bool, "lastStep": int}} — mirrors the console seam
     * (SessionView.onboarding / UpdateMeRequest.onboarding). {@code dismissed}
     * once the owner opts out OR completes the wizard; {@code lastStep} is the
     * resume point while pending.
     */
    public record OnboardingState(boolean dismissed, int lastStep) {}

    /**
     * D-CORE-8: one of the caller's own active Keycloak sessions. Scoped to
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
     * FEAT-32: one of the caller's own self-service credentials (an
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
     * FEAT-32: the {@code GET /api/credentials} response — the caller's
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

    public record CreateEntryRequest(String type, String key, String content, String reference) {}
    public record UpdateEntryRequest(String type, String content, String reference) {}
    /** D-CORE-17 scope-remap: target shared scope + an optional key override to
     *  dodge a target key-collision (rename instead of overwrite). */
    public record RemapEntryRequest(String targetScope, String key) {}

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
