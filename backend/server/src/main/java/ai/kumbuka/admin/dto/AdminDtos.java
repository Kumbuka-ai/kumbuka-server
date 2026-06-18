package ai.kumbuka.admin.dto;

import ai.kumbuka.domain.Memory;
import ai.kumbuka.domain.Scope;
import ai.kumbuka.domain.TeamSettings;
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
        String description,
        long entryCount,
        Instant createdAt
    ) {
        public static ScopeView from(Scope s, long entryCount) {
            return new ScopeView(
                s.slug, s.name, s.kind.dbValue(),
                Boolean.TRUE.equals(s.fixed),
                Boolean.TRUE.equals(s.archived),
                s.description,
                entryCount,
                s.createdAt
            );
        }
    }

    public record EntryView(
        UUID id,
        String type,
        String key,
        String content,
        String reference,     // D-CORE-7: optional external provenance URL
        String authorSubject,
        String source,
        Instant createdAt,
        Instant updatedAt
    ) {
        public static EntryView from(Memory m) {
            return new EntryView(
                m.id, m.type.dbValue(), m.key, m.content, m.reference,
                m.ownerSubject, m.source.dbValue(),
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
                m.id, m.scope.slug, m.type.dbValue(), m.key,
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
        OnboardingState onboarding  // D-CORE-10.1: per-user wizard dismiss/resume state
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

    // ---------- Requests ---------------------------------------------------

    public record CreateScopeRequest(String slug, String name, String description) {}
    public record UpdateScopeRequest(String name, String description) {}

    public record CreateEntryRequest(String type, String key, String content, String reference) {}
    public record UpdateEntryRequest(String type, String content, String reference) {}

    public record UpdateSettingsRequest(
        String writePolicy,      // ask | project | global
        String defaultScopeSlug, // nullable; only used with writePolicy=project
        String createScopes      // admins | members
    ) {}

    public record UpdateMeRequest(String displayName, String locale, OnboardingState onboarding) {}
}
