package ai.kumbuka.repo;

import ai.kumbuka.domain.Memory;
import ai.kumbuka.domain.MemoryType;
import ai.kumbuka.domain.Scope;
import ai.kumbuka.domain.SourceChannel;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Channel handling against a real Postgres instance: the 'import' channel
 * round-trips, the UNKNOWN sentinel is rejected on the write side, an
 * unrecognised stored channel does not break the entity load paths, the
 * keyed upsert keeps its first-write provenance, and the column CHECK
 * accepts exactly the recognised values.
 */
@QuarkusTest
class ChannelCompatibilityTest {

    static final String MEMBER = "33333333-3333-3333-3333-333333333333";
    static final String OTHER  = "55555555-5555-5555-5555-555555555555";

    @Inject MemoryRepository memories;
    @Inject SharedMemoryRepository sharedMemories;
    @Inject ScopeRepository scopes;
    @Inject EntityManager em;

    // ---------------------------------------------------------------------
    // import: clean round-trip through the real write and read path
    // ---------------------------------------------------------------------

    @Test
    @TestTransaction
    void importChannel_writesAndReadsRoundTrip() {
        Memory m = memories.remember(MEMBER, "global", MemoryType.STATUS,
            "channel.import.roundtrip", "ingested row", SourceChannel.IMPORT);
        UUID id = m.logicalId;
        em.flush();
        em.clear();

        Memory reloaded = Memory.findById(id);
        assertThat(reloaded).isNotNull();
        assertThat(reloaded.source).isEqualTo(SourceChannel.IMPORT);
    }

    // ---------------------------------------------------------------------
    // UNKNOWN is write-rejected at the persist-time guard
    // ---------------------------------------------------------------------

    @Test
    @TestTransaction
    void persistingTheSentinel_isRejectedByThePrePersistGuard() {
        Memory m = new Memory();
        m.ownerSubject = MEMBER;
        m.scope = scopes.requireBySlug("global");
        m.type = MemoryType.STATUS;
        m.content = "must never persist";
        m.source = SourceChannel.UNKNOWN;
        assertThatThrownBy(() -> memories.persist(m))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("read-side sentinel");
    }

    @Test
    @TestTransaction
    void writingTheSentinelValue_isRejectedStructurallyByTheColumnChecks() {
        // Second enforcement layer, below the application: 'unknown' (the
        // sentinel's stored form) is absent from both column CHECKs, so a
        // write that slipped past the persist-time guard dies at the database.
        Scope global = scopes.requireBySlug("global");
        assertThatThrownBy(() -> em.createNativeQuery("""
            INSERT INTO memory (tenant_id, owner_subject, scope_id, type, key,
                                content, source, logical_id, is_private, lock)
            VALUES (CAST(:tenant AS uuid), :owner, CAST(:scope AS uuid), 'status',
                    'channel.sentinel.native', 'must be rejected', 'unknown',
                    CAST(:logical AS uuid), false, 'none')
            """)
          .setParameter("tenant", global.tenantId)
          .setParameter("owner", MEMBER)
          .setParameter("scope", global.id.toString())
          .setParameter("logical", UUID.randomUUID().toString())
          .executeUpdate())
            .hasMessageContaining("memory_source_check");
    }

    // ---------------------------------------------------------------------
    // The core compatibility proof: a stored channel value this binary does
    // not know must not fail the entity load paths. The row is planted with
    // native SQL; the CHECK constraint is lifted inside this rolled-back
    // test transaction only, so the real entity load path (not a doctored
    // one) sees a value that is impossible to produce through the ORM.
    // ---------------------------------------------------------------------

    @Test
    @TestTransaction
    void unrecognisedStoredChannel_doesNotBreakTheEntityLoadPaths() {
        Scope global = scopes.requireBySlug("global");
        memories.remember(MEMBER, "global", MemoryType.STATUS,
            "channel.known.neighbour", "ordinary row", SourceChannel.MCP);
        em.flush();

        em.createNativeQuery("ALTER TABLE memory DROP CONSTRAINT memory_source_check")
          .executeUpdate();
        em.createNativeQuery("""
            INSERT INTO memory (tenant_id, owner_subject, scope_id, type, key,
                                content, source, logical_id, is_private, lock)
            VALUES (CAST(:tenant AS uuid), :owner, CAST(:scope AS uuid), 'status',
                    'channel.future.row', 'written by a newer binary',
                    'future-channel', CAST(:logical AS uuid), false, 'none')
            """)
          .setParameter("tenant", global.tenantId)
          .setParameter("owner", OTHER)
          .setParameter("scope", global.id.toString())
          .setParameter("logical", UUID.randomUUID().toString())
          .executeUpdate();
        em.clear();

        // MCP read path: the whole listing survives and carries the foreign row.
        List<Memory> recalled = memories.recall(MEMBER, "global", null, null, false);
        assertThat(recalled).extracting(r -> r.key)
            .contains("channel.future.row", "channel.known.neighbour");
        Memory foreign = recalled.stream()
            .filter(r -> "channel.future.row".equals(r.key)).findFirst().orElseThrow();
        assertThat(foreign.source).isEqualTo(SourceChannel.UNKNOWN);

        // Console read path: same guarantee through the shared listing.
        em.clear();
        List<Memory> listed = sharedMemories.listShared("global", null);
        assertThat(listed).extracting(r -> r.key).contains("channel.future.row");
    }

    // ---------------------------------------------------------------------
    // Keyed-upsert regression: last-edit provenance is stamped, first-write
    // provenance stays
    // ---------------------------------------------------------------------

    @Test
    @TestTransaction
    void keyedUpsert_stampsLastEditAndKeepsFirstWriteProvenance() {
        Memory first = memories.remember(MEMBER, "global", MemoryType.CONVENTION,
            "channel.upsert.regression", "first version", SourceChannel.MCP);
        UUID id = first.logicalId;

        Memory updated = memories.remember(OTHER, "global", MemoryType.CONVENTION,
            "channel.upsert.regression", "second version", SourceChannel.CONSOLE);

        assertThat(updated.logicalId).isEqualTo(id);
        assertThat(updated.content).isEqualTo("second version");
        assertThat(updated.ownerSubject).isEqualTo(MEMBER);
        assertThat(updated.source).isEqualTo(SourceChannel.MCP);
        assertThat(updated.updatedBy).isEqualTo(OTHER);
        assertThat(updated.updatedSource).isEqualTo(SourceChannel.CONSOLE);
    }

    // ---------------------------------------------------------------------
    // Column CHECK after the migration: 'import' passes, a fantasy value
    // is rejected by the constraint itself
    // ---------------------------------------------------------------------

    @Test
    @TestTransaction
    void sourceCheck_acceptsImportAndRejectsFantasyValues() {
        Scope global = scopes.requireBySlug("global");

        int inserted = em.createNativeQuery("""
            INSERT INTO memory (tenant_id, owner_subject, scope_id, type, key,
                                content, source, logical_id, is_private, lock)
            VALUES (CAST(:tenant AS uuid), :owner, CAST(:scope AS uuid), 'status',
                    'channel.check.import', 'native import row', 'import',
                    CAST(:logical AS uuid), false, 'none')
            """)
          .setParameter("tenant", global.tenantId)
          .setParameter("owner", MEMBER)
          .setParameter("scope", global.id.toString())
          .setParameter("logical", UUID.randomUUID().toString())
          .executeUpdate();
        assertThat(inserted).isEqualTo(1);

        assertThatThrownBy(() -> em.createNativeQuery("""
            INSERT INTO memory (tenant_id, owner_subject, scope_id, type, key,
                                content, source, logical_id, is_private, lock)
            VALUES (CAST(:tenant AS uuid), :owner, CAST(:scope AS uuid), 'status',
                    'channel.check.bogus', 'must be rejected', 'carrier-pigeon',
                    CAST(:logical AS uuid), false, 'none')
            """)
          .setParameter("tenant", global.tenantId)
          .setParameter("owner", MEMBER)
          .setParameter("scope", global.id.toString())
          .setParameter("logical", UUID.randomUUID().toString())
          .executeUpdate())
            .hasMessageContaining("memory_source_check");
    }
}
