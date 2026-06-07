package ai.kumbuka.repo;

import ai.kumbuka.config.MemoryConfig;
import ai.kumbuka.tenancy.TenantBound;
import ai.kumbuka.domain.Scope;
import ai.kumbuka.domain.ScopeKind;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Scopes are addressed externally by their {@code slug} (kebab-case, the URL
 * identity per ADR-0007); the {@code id} is a surrogate UUID used only as
 * the FK target for {@code memory.scope_id}.
 *
 * Tools and the admin API both look up scopes by slug; only the data layer
 * sees the UUID.
 */
@Transactional
@TenantBound
@ApplicationScoped
public class ScopeRepository implements PanacheRepositoryBase<Scope, UUID> {

    @Inject MemoryConfig config;

    // ---- lookup by slug (URL identity, immutable) -------------------------
    // The tenant axis is enforced structurally — Hibernate's @TenantId
    // filter on Scope adds `tenant_id = :currentTenant` to every query
    // automatically, and Postgres RLS catches anything that misses
    // (ADR-0011). The queries below do not — and must not — repeat that
    // predicate by hand.

    public Optional<Scope> findBySlug(String slug) {
        return find("slug = ?1", slug).firstResultOptional();
    }

    public Scope requireBySlug(String slug) {
        return findBySlug(slug).orElseThrow(() ->
            new ScopeNotFoundException("scope not found: " + slug));
    }

    // ---- listings ---------------------------------------------------------

    public List<Scope> listAll() {
        return list("archived = false order by kind, slug");
    }

    public List<Scope> listShared() {
        return list(
            "archived = false and kind in (?1, ?2) order by kind, slug",
            ScopeKind.GLOBAL, ScopeKind.PROJECT);
    }

    // ---- mutations --------------------------------------------------------

    @Transactional
    public Scope createProject(String slug, String name, String description, String createdBy) {
        if (findBySlug(slug).isPresent()) {
            throw new ScopeAlreadyExistsException("scope already exists: " + slug);
        }
        Scope s = new Scope();
        // tenantId auto-populated by Hibernate from the @TenantId column.
        s.slug = slug;
        s.name = name;
        s.description = description;
        s.kind = ScopeKind.PROJECT;
        s.fixed = false;
        s.archived = false;
        s.createdBy = createdBy;
        persist(s);
        return s;
    }

    @Transactional
    public void rename(String slug, String newName, String newDescription) {
        Scope s = requireBySlug(slug);
        if (s.fixed) {
            throw new IllegalArgumentException("fixed scope cannot be renamed: " + slug);
        }
        if (newName != null) s.name = newName;
        if (newDescription != null) s.description = newDescription;
    }

    @Transactional
    public void archive(String slug) {
        Scope s = requireBySlug(slug);
        if (s.fixed) {
            throw new IllegalArgumentException("fixed scope cannot be archived: " + slug);
        }
        if (s.kind != ScopeKind.PROJECT) {
            throw new IllegalArgumentException("only project scopes can be archived: " + slug);
        }
        s.archived = true;
    }

    public static class ScopeNotFoundException extends RuntimeException {
        public ScopeNotFoundException(String m) { super(m); }
    }
    public static class ScopeAlreadyExistsException extends RuntimeException {
        public ScopeAlreadyExistsException(String m) { super(m); }
    }
}
