package ai.kumbuka.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Branch coverage for the {@code @PrePersist} lifecycle guard. {@code source}
 * is authoritative (MCP vs CONSOLE) and must be set explicitly before persist;
 * timestamps default to now only when unset. Same-package so the lifecycle
 * hook can be driven directly without a database.
 */
class MemoryTest {

    private static Memory minimal() {
        Memory m = new Memory();
        m.type = MemoryType.DECISION;
        m.content = "x";
        return m;
    }

    @Test
    void onCreate_withoutSource_throws() {
        Memory m = minimal();
        m.source = null;
        assertThatThrownBy(m::onCreate)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("source");
    }

    @Test
    void onCreate_defaultsTimestampsWhenUnset() {
        Memory m = minimal();
        m.source = SourceChannel.MCP;
        m.createdAt = null;
        m.updatedAt = null;

        m.onCreate();

        assertThat(m.createdAt).isNotNull();
        assertThat(m.updatedAt).isNotNull();
    }

    @Test
    void onCreate_preservesTimestampsWhenAlreadySet() {
        Instant created = Instant.parse("2026-01-01T00:00:00Z");
        Instant updated = Instant.parse("2026-02-02T00:00:00Z");
        Memory m = minimal();
        m.source = SourceChannel.CONSOLE;
        m.createdAt = created;
        m.updatedAt = updated;

        m.onCreate();

        assertThat(m.createdAt).isEqualTo(created);
        assertThat(m.updatedAt).isEqualTo(updated);
    }

    @Test
    void onUpdate_refreshesUpdatedAt() {
        Memory m = minimal();
        m.source = SourceChannel.MCP;
        m.updatedAt = Instant.parse("2026-01-01T00:00:00Z");

        m.onUpdate();

        assertThat(m.updatedAt).isAfter(Instant.parse("2026-01-01T00:00:00Z"));
    }
}
