package ai.kumbuka.admin.dto;

import ai.kumbuka.admin.dto.AdminDtos.EntryView;
import ai.kumbuka.domain.Memory;
import ai.kumbuka.domain.MemoryType;
import ai.kumbuka.domain.SourceChannel;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stage F: the console read-only flag on {@link EntryView}. Built-in guidance
 * (the overlay entries and the bundled rows that shadow them) carries the system
 * channel and is not editable or deletable via the console, so {@code readOnly}
 * is true exactly for system-channel entries.
 */
class EntryViewTest {

    @Test
    void systemSourceEntry_isReadOnly() {
        assertThat(EntryView.from(entry(SourceChannel.SYSTEM)).readOnly()).isTrue();
    }

    @Test
    void consoleSourceEntry_isNotReadOnly() {
        assertThat(EntryView.from(entry(SourceChannel.CONSOLE)).readOnly()).isFalse();
    }

    @Test
    void mcpSourceEntry_isNotReadOnly() {
        assertThat(EntryView.from(entry(SourceChannel.MCP)).readOnly()).isFalse();
    }

    private static Memory entry(SourceChannel source) {
        Memory m = new Memory();
        m.logicalId = UUID.randomUUID();
        m.type = MemoryType.CONVENTION;
        m.key = "convention.example";
        m.content = "example content";
        m.ownerSubject = "subject";
        m.source = source;
        m.createdAt = Instant.now();
        m.updatedAt = m.createdAt;
        return m;
    }
}
