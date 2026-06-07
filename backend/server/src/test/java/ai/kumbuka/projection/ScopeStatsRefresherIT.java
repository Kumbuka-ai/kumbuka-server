package ai.kumbuka.projection;

import ai.kumbuka.domain.MemoryType;
import ai.kumbuka.domain.SourceChannel;
import ai.kumbuka.repo.MemoryRepository;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the two contracts of V6 / ADR-0014 at runtime:
 *
 * <ol>
 *   <li><b>The refresher excludes private rows.</b> Plant entries in
 *       both {@code global} and {@code private} scopes, run the
 *       refresher, assert {@code scope_stats} contains only the global
 *       rows and never the private one.</li>
 *   <li><b>The CHECK on {@code scope_kind} bars a private row from
 *       landing in the table even if some buggy direct INSERT tried.
 *       </b></li>
 * </ol>
 *
 * Tagged {@code integration} so failsafe picks it up alongside
 * {@code CrossTenantIsolationIT}.
 */
@QuarkusTest
@Tag("integration")
class ScopeStatsRefresherIT {

    @Inject MemoryRepository memories;
    @Inject ScopeStatsRefresher refresher;
    @Inject EntityManager em;

    /** ITs share the DevServices Postgres — clean up so subsequent
     *  isolation tests (CrossTenantIsolationIT etc.) see a fresh state. */
    @AfterEach
    @Transactional
    void cleanup() {
        em.createNativeQuery("DELETE FROM scope_stats").executeUpdate();
        em.createNativeQuery("DELETE FROM memory").executeUpdate();
    }

    @Test
    @Transactional
    void refresher_excludes_private_rows() {
        // Plant: 2 shared + 1 private
        memories.remember("user-x", "global", MemoryType.DECISION,
            "shared.a", "shared a", SourceChannel.MCP);
        memories.remember("user-x", "global", MemoryType.CONVENTION,
            "shared.b", "shared b", SourceChannel.MCP);
        memories.remember("user-x", "private", MemoryType.DECISION,
            "priv.a", "private a — must never appear", SourceChannel.MCP);

        refresher.refresh();

        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery("""
            SELECT scope_slug, type, entry_count
            FROM scope_stats
            ORDER BY scope_slug, type
            """).getResultList();

        // No private rows ever in the projection.
        assertThat(rows).noneMatch(r -> "private".equals(r[0]));

        // Two shared rows (one per type).
        var summary = rows.stream()
            .map(r -> Map.entry(r[0] + "/" + r[1], ((Number) r[2]).longValue()))
            .toList();
        assertThat(summary)
            .contains(Map.entry("global/decision", 1L))
            .contains(Map.entry("global/convention", 1L));
    }

    @Test
    void scope_kind_check_rejects_private_directly() {
        // Even a hand-rolled INSERT pretending the scope is private has
        // to fail at the table CHECK — defence-in-depth around the
        // refresher's WHERE filter.
        org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () -> {
            jakarta.transaction.TransactionManager tm =
                io.quarkus.arc.Arc.container()
                    .instance(jakarta.transaction.TransactionManager.class).get();
            tm.begin();
            try {
                em.createNativeQuery("""
                    INSERT INTO scope_stats
                        (tenant_id, scope_id, scope_slug, scope_kind, type,
                         entry_count, last_updated_at)
                    VALUES (
                        '00000000-0000-0000-0000-000000000001',
                        (SELECT id FROM scope WHERE kind='private' LIMIT 1),
                        'private', 'private', 'decision',
                        1, now())
                    """).executeUpdate();
                tm.commit();
            } catch (Exception e) {
                tm.rollback();
                throw e;
            }
        });
    }
}
