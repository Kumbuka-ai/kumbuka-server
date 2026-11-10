// Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
// SPDX-License-Identifier: AGPL-3.0-only
// This file is part of Kumbuka and is licensed under the GNU Affero
// General Public License v3.0 only. See the LICENSE file in the
// repository root for the full licence text.

package ai.kumbuka.repo;

import ai.kumbuka.domain.Tag;
import ai.kumbuka.domain.TagStatus;
import ai.kumbuka.tenancy.TenantBound;
import ai.kumbuka.util.TagTokenValidator;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The tag vocabulary of a scope: declare, list, withdraw. Called in-process by
 * the composition that publishes these verbs; this class carries no protocol.
 *
 * <p>Scopes are addressed by their {@code slug}, as everywhere else. Every
 * method takes the calling {@code subject} explicitly, the way
 * {@link ai.kumbuka.service.MemberWritePolicy} does — the subject is whoever
 * the surface authenticated, never something this class infers.
 *
 * <p><b>Visibility and the write right come from {@code platform.scope_access}
 * and from nowhere else.</b> A scope the view does not answer for the caller is
 * reported as not found, whatever the reason (another tenant, an inactive
 * member, somebody else's private scope); a scope it answers with
 * {@code can_write = false} (locked, or a muted member on a shared scope) refuses
 * declare and withdraw. Reading the view rather than re-deriving its rules
 * keeps the core and the services on one answer. The lookup binds
 * {@code app.subject} transaction-locally; {@code app.tenant_id} is bound by
 * {@link TenantBound} as for every other repository.
 *
 * <p>The tenant axis is enforced structurally, as in {@link ScopeRepository}:
 * Hibernate's {@code @TenantId} filter on {@link Tag} and the RLS policy on
 * {@code platform.tag}. The queries below do not repeat the predicate.
 */
@Transactional
@TenantBound
@ApplicationScoped
public class TagRepository implements PanacheRepositoryBase<Tag, UUID> {

    @Inject EntityManager em;

    /** One tag as a caller sees it. */
    public record TagEntry(UUID tagId, UUID scopeId, String token, String description, TagStatus status) {
        static TagEntry of(Tag t) {
            return new TagEntry(t.id, t.scopeId, t.token, t.description, t.status);
        }
    }

    // ---- verbs --------------------------------------------------------------

    /**
     * Declares {@code token} in the scope. Refused, in this order: a malformed
     * token (field {@code token}) or an over-long description (field
     * {@code description}); a scope the caller cannot see; a caller without the
     * write right; a token already present in the scope, declared or withdrawn —
     * a withdrawn token is not declared again, and the refusal names its status.
     */
    @Transactional
    public TagEntry declare(String subject, String scopeSlug, String token, String description) {
        requireSubject(subject);
        TagTokenValidator.validateToken(token);
        TagTokenValidator.validateDescription(description);
        UUID scopeId = requireWritableScope(subject, scopeSlug);

        Optional<Tag> existing = findInScope(scopeId, token);
        if (existing.isPresent()) {
            throw new TagAlreadyDeclaredException(token, existing.get().status);
        }

        Tag t = new Tag();
        // tenantId auto-populated by Hibernate from the @TenantId column.
        t.scopeId = scopeId;
        t.token = token;
        t.description = description;
        t.status = TagStatus.DECLARED;
        t.createdBy = subject;
        persistAndFlush(t);
        return TagEntry.of(t);
    }

    /** Every tag of a visible scope, declared and withdrawn, sorted by token. */
    @Transactional
    public List<TagEntry> list(String subject, String scopeSlug) {
        requireSubject(subject);
        UUID scopeId = requireVisibleScope(subject, scopeSlug).scopeId();
        // Sorted here, by code unit, rather than by ORDER BY: the database's
        // collation may weigh ':' and '-' differently from one installation to
        // the next, and the order of a vocabulary listing should not.
        return find("scopeId = ?1", scopeId).stream()
            .map(TagEntry::of)
            .sorted(Comparator.comparing(TagEntry::token))
            .toList();
    }

    /**
     * Withdraws {@code token}: status {@code withdrawn}, {@code withdrawn_at}
     * set, the row kept. Needs the write right. Withdrawing a tag that is
     * already withdrawn succeeds and changes nothing.
     */
    @Transactional
    public TagEntry withdraw(String subject, String scopeSlug, String token) {
        requireSubject(subject);
        TagTokenValidator.validateToken(token);
        UUID scopeId = requireWritableScope(subject, scopeSlug);

        Tag t = findInScope(scopeId, token).orElseThrow(() ->
            new TagNotFoundException("tag not found: " + token + " in scope " + scopeSlug));
        if (t.status != TagStatus.WITHDRAWN) {
            t.status = TagStatus.WITHDRAWN;
            t.withdrawnAt = Instant.now();
            flush();
        }
        return TagEntry.of(t);
    }

    // ---- scope_access -------------------------------------------------------

    private record ScopeAnswer(UUID scopeId, boolean canWrite) {}

    private ScopeAnswer requireVisibleScope(String subject, String scopeSlug) {
        em.createNativeQuery("SELECT set_config('app.subject', ?1, true)")
            .setParameter(1, subject)
            .getSingleResult();
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                "SELECT scope_id, can_write FROM platform.scope_access WHERE slug = ?1")
            .setParameter(1, scopeSlug)
            .getResultList();
        if (rows.isEmpty()) {
            throw new ScopeRepository.ScopeNotFoundException("scope not found: " + scopeSlug);
        }
        Object[] row = rows.get(0);
        return new ScopeAnswer((UUID) row[0], Boolean.TRUE.equals(row[1]));
    }

    private UUID requireWritableScope(String subject, String scopeSlug) {
        ScopeAnswer scope = requireVisibleScope(subject, scopeSlug);
        if (!scope.canWrite()) {
            throw new TagWriteDeniedException(scopeSlug,
                "no write right in scope '" + scopeSlug + "': the scope is locked, "
                + "or shared-scope writes are suspended for this member");
        }
        return scope.scopeId();
    }

    private Optional<Tag> findInScope(UUID scopeId, String token) {
        return find("scopeId = ?1 and token = ?2", scopeId, token).firstResultOptional();
    }

    private static void requireSubject(String subject) {
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("subject is required");
        }
    }

    // ---- refusals -----------------------------------------------------------

    /** The token exists in the scope already; {@link #status()} says in which state. */
    public static class TagAlreadyDeclaredException extends RuntimeException {
        private final TagStatus status;
        public TagAlreadyDeclaredException(String token, TagStatus status) {
            super("tag '" + token + "' already exists in this scope with status "
                + status.dbValue()
                + (status == TagStatus.WITHDRAWN
                    ? " — a withdrawn tag cannot be declared again"
                    : ""));
            this.status = status;
        }
        public TagStatus status() { return status; }
    }

    public static class TagNotFoundException extends RuntimeException {
        public TagNotFoundException(String m) { super(m); }
    }

    /** The caller sees the scope but {@code scope_access.can_write} is false. */
    public static class TagWriteDeniedException extends RuntimeException {
        private final String scope;
        public TagWriteDeniedException(String scope, String m) {
            super(m);
            this.scope = scope;
        }
        public String scope() { return scope; }
    }
}
