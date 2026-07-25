package ai.kumbuka.overlay;

import ai.kumbuka.domain.Memory;
import ai.kumbuka.domain.MemoryLock;
import ai.kumbuka.domain.MemoryType;
import ai.kumbuka.domain.Scope;
import ai.kumbuka.domain.ScopeKind;
import ai.kumbuka.domain.SourceChannel;
import ai.kumbuka.domain.SystemSubject;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Serves the built-in guidance entries: a small, fixed set of public, global
 * convention entries bundled with the server that explain how to use the memory
 * surface. They are read-only content, held in memory (never persisted), and
 * merged into the read paths on top of the rows a tenant already has.
 *
 * <p>The resource {@code /guidance/built-in-guidance.json} is parsed once at
 * startup into transient {@link Memory} objects — one per entry — with a stable
 * synthetic identity and stable timestamps, so the same entry looks identical
 * across requests and deploys. Nothing here writes to the database; the two
 * merge methods add the applicable entries to a read result and re-sort.
 *
 * <p><b>Coexistence.</b> A tenant may already carry a real row under the same
 * global key (an ordinary or bundled-content row). In that case the real row
 * wins and the overlay entry is suppressed for that key, so the merge never
 * doubles an entry. A tenant that has no such row sees the overlay entry
 * directly. Because the overlay content mirrors the bundled content of those
 * rows verbatim, the two are indistinguishable on any filter.
 */
@ApplicationScoped
public class GuidanceOverlay {

    private static final String RESOURCE = "/guidance/built-in-guidance.json";

    /** The slug of the single global scope every built-in entry belongs to. */
    private static final String GLOBAL_SLUG = "global";

    /**
     * Fixed namespace for the deterministic (name-based, version 5) identity of
     * each built-in entry. The name hashed under this namespace is the entry's
     * rename-invariant {@code logicalName} (the key without its leading
     * {@code convention.} namespace), so an entry keeps the same id even if its
     * fully-qualified key is later changed.
     */
    private static final UUID NAMESPACE =
        UUID.fromString("b1e6a9d4-3c2f-4a8e-9f17-2d6c8b0a5e34");

    /** Newest-first by last-update instant, matching the read paths' SQL order. */
    private static final Comparator<Memory> BY_UPDATED_DESC =
        Comparator.comparing((Memory m) -> m.updatedAt).reversed();

    /** The entry version string, for diagnostics. */
    private final String version;

    /** Pre-built, transient entries in resource order. Never persisted. */
    private final List<Memory> entries;

    /** Fast lookup by synthetic id, for future addressing. */
    private final Map<UUID, Memory> byId;

    public GuidanceOverlay() {
        Parsed parsed = load();
        this.version = parsed.version;
        Instant stamp = Instant.parse(parsed.versionDate);
        // One shared synthetic scope: the read DTOs only ever read slug + kind.
        Scope global = new Scope();
        global.slug = GLOBAL_SLUG;
        global.kind = ScopeKind.GLOBAL;

        List<Memory> built = new ArrayList<>(parsed.entries().size());
        Map<UUID, Memory> index = new LinkedHashMap<>();
        for (Entry e : parsed.entries()) {
            Memory m = toMemory(e, global, stamp);
            built.add(m);
            index.put(m.logicalId, m);
        }
        this.entries = Collections.unmodifiableList(built);
        this.byId = Collections.unmodifiableMap(index);
    }

    private static Memory toMemory(Entry e, Scope global, Instant stamp) {
        Memory m = new Memory();
        m.logicalId = uuidV5(NAMESPACE, e.logicalName());
        m.scope = global;
        m.type = MemoryType.fromDb(e.type());
        m.key = e.key();
        m.content = e.content();
        m.ownerSubject = SystemSubject.SENTINEL;
        m.source = SourceChannel.SYSTEM;
        m.lock = MemoryLock.SYSTEM;
        m.isPrivate = false;
        m.createdAt = stamp;
        m.updatedAt = stamp;
        return m;
    }

    // ---------------------------------------------------------------------
    // Accessors
    // ---------------------------------------------------------------------

    /** The built-in entries, in resource order. Unmodifiable. */
    public List<Memory> entries() {
        return entries;
    }

    /** Resolve a built-in entry by its synthetic id (empty if none matches). */
    public Optional<Memory> byId(UUID id) {
        return Optional.ofNullable(byId.get(id));
    }

    /** The entry version string. */
    public String version() {
        return version;
    }

    // ---------------------------------------------------------------------
    // Merge into the two read chokepoints
    // ---------------------------------------------------------------------

    /**
     * Merge the applicable built-in entries into a recall result. An entry
     * applies iff its type, the query substring and its global scope membership
     * all match the same filters the recall SQL applied to the real rows — an
     * entry is never blindly appended. An applicable entry is suppressed when a
     * real global row already carries its key (the real row wins). The returned
     * list is re-sorted newest-first to match the SQL order.
     */
    public List<Memory> mergeIntoRecall(List<Memory> rows, String scopeSlug,
                                        MemoryType type, String query,
                                        boolean includeGlobal) {
        List<Memory> survivors = new ArrayList<>();
        for (Memory e : entries) {
            if (appliesToRecall(e, scopeSlug, type, query, includeGlobal)
                && !shadowed(rows, e)) {
                survivors.add(e);
            }
        }
        return combineSorted(rows, survivors);
    }

    /**
     * Merge the applicable built-in entries into a shared-listing result.
     * listShared has no query and never reaches a private scope, so an entry
     * applies iff its type matches and the listing is unscoped or scoped to the
     * global scope. Suppression and ordering are as in {@link #mergeIntoRecall}.
     */
    public List<Memory> mergeIntoShared(List<Memory> rows, String scopeSlug, MemoryType type) {
        List<Memory> survivors = new ArrayList<>();
        for (Memory e : entries) {
            if (typeMatches(type) && scopeMatchesShared(scopeSlug) && !shadowed(rows, e)) {
                survivors.add(e);
            }
        }
        return combineSorted(rows, survivors);
    }

    private boolean appliesToRecall(Memory e, String scopeSlug, MemoryType type,
                                    String query, boolean includeGlobal) {
        return typeMatches(type)
            && queryMatches(e, query)
            && scopeMatchesRecall(scopeSlug, includeGlobal);
    }

    /** Built-in entries are all {@code convention}: a type filter admits them
     *  only when it is absent or asks for {@code convention}. */
    private static boolean typeMatches(MemoryType type) {
        return type == null || type == MemoryType.CONVENTION;
    }

    /** Case-insensitive substring match on the content, mirroring the SQL
     *  {@code lower(content) like %query%}. A null/blank query matches all. */
    private static boolean queryMatches(Memory e, String query) {
        if (query == null || query.isBlank()) {
            return true;
        }
        return e.content.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT));
    }

    /**
     * Recall scope semantics, mirroring the recall SQL. An unscoped read always
     * includes the global scope, so a global entry is in view. A scoped read
     * includes the global entry only when the global scope itself was asked for,
     * or when the caller pulled the global scope in with include_global.
     */
    private static boolean scopeMatchesRecall(String scopeSlug, boolean includeGlobal) {
        if (scopeSlug == null) {
            return true;
        }
        return GLOBAL_SLUG.equals(scopeSlug) || includeGlobal;
    }

    /** Shared-listing scope semantics: unscoped, or scoped to the global scope. */
    private static boolean scopeMatchesShared(String scopeSlug) {
        return scopeSlug == null || GLOBAL_SLUG.equals(scopeSlug);
    }

    /** A built-in entry is suppressed when a real global row already holds its
     *  key — the real row wins, so the merge never doubles a key. */
    private static boolean shadowed(List<Memory> rows, Memory overlayEntry) {
        for (Memory r : rows) {
            if (GLOBAL_SLUG.equals(r.scope.slug) && overlayEntry.key.equals(r.key)) {
                return true;
            }
        }
        return false;
    }

    /** Append the surviving entries to the rows and re-sort newest-first. When
     *  nothing survives (e.g. every key is already present) the rows are
     *  returned untouched — the read is then byte-for-byte the pre-overlay one. */
    private static List<Memory> combineSorted(List<Memory> rows, List<Memory> survivors) {
        if (survivors.isEmpty()) {
            return rows;
        }
        List<Memory> merged = new ArrayList<>(rows.size() + survivors.size());
        merged.addAll(rows);
        merged.addAll(survivors);
        merged.sort(BY_UPDATED_DESC);
        return merged;
    }

    // ---------------------------------------------------------------------
    // Synthetic identity (name-based UUID, version 5 per RFC 4122)
    // ---------------------------------------------------------------------

    /**
     * Deterministic name-based UUID (version 5). RFC 4122 §4.3 mandates SHA-1
     * for version 5; the digest is used purely to derive a stable identifier
     * from a name, not for any security or integrity purpose. The JDK's
     * {@code UUID.nameUUIDFromBytes} is version 3 (MD5) and cannot be used.
     */
    @SuppressWarnings("java:S4790") // SHA-1 is required by RFC 4122 for version-5 UUIDs; not a hashing-for-security use.
    static UUID uuidV5(UUID namespace, String name) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            md.update(toBytes(namespace));
            md.update(name.getBytes(StandardCharsets.UTF_8));
            byte[] hash = md.digest();
            hash[6] = (byte) ((hash[6] & 0x0f) | 0x50); // version 5
            hash[8] = (byte) ((hash[8] & 0x3f) | 0x80); // RFC 4122 variant
            return fromBytes(hash);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-1 is unavailable; cannot derive a version-5 UUID", ex);
        }
    }

    private static byte[] toBytes(UUID u) {
        ByteBuffer bb = ByteBuffer.allocate(16);
        bb.putLong(u.getMostSignificantBits());
        bb.putLong(u.getLeastSignificantBits());
        return bb.array();
    }

    private static UUID fromBytes(byte[] hash) {
        ByteBuffer bb = ByteBuffer.wrap(hash, 0, 16);
        return new UUID(bb.getLong(), bb.getLong());
    }

    // ---------------------------------------------------------------------
    // Resource parsing
    // ---------------------------------------------------------------------

    private static Parsed load() {
        try (InputStream is = GuidanceOverlay.class.getResourceAsStream(RESOURCE)) {
            if (is == null) {
                throw new IllegalStateException("missing built-in guidance resource: " + RESOURCE);
            }
            ObjectMapper mapper = new ObjectMapper()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
            Parsed parsed = mapper.readValue(is, Parsed.class);
            Objects.requireNonNull(parsed.version(), "guidance.version");
            Objects.requireNonNull(parsed.versionDate(), "guidance.versionDate");
            if (parsed.entries() == null || parsed.entries().isEmpty()) {
                throw new IllegalStateException("built-in guidance resource has no entries: " + RESOURCE);
            }
            return parsed;
        } catch (IOException e) {
            throw new IllegalStateException("failed to load built-in guidance resource " + RESOURCE, e);
        }
    }

    /** Wire shape of the resource. Unknown fields (e.g. the leading comment)
     *  are ignored by the mapper configuration. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Parsed(String version, String versionDate, List<Entry> entries) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Entry(String logicalName, String key, String type, String content) {}
}
