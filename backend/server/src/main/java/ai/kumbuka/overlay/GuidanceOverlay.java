package ai.kumbuka.overlay;

import ai.kumbuka.domain.Memory;
import ai.kumbuka.domain.MemoryLock;
import ai.kumbuka.domain.MemoryType;
import ai.kumbuka.domain.Scope;
import ai.kumbuka.domain.ScopeKind;
import ai.kumbuka.domain.SourceChannel;
import ai.kumbuka.domain.SystemSubject;
import ai.kumbuka.config.MemoryConfig;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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
import java.util.Optional;
import java.util.UUID;

/**
 * Serves the built-in system-guidance entries: public, global entries that
 * explain how to use the memory surface. They are read-only content, held in
 * memory (never persisted), and merged into the read paths on top of the rows a
 * tenant already has.
 *
 * <p><b>Source (operator-editable, two levels, never merged).</b> The active
 * document comes from exactly one source, resolved once at startup by
 * {@link GuidanceLoader}: the operator's external file if it exists and is
 * readable, otherwise the bundled default {@link #BUNDLED_RESOURCE}. The
 * external path is a runtime configuration property so it is settable in a
 * container without a rebuild. Any number of entries and any entry type are
 * supported; the loader validates the document and aborts the boot on any
 * problem (see {@link GuidanceLoader} and {@link GuidanceLoadException}).
 *
 * <p><b>Read once, at startup.</b> The {@link #onStart} observer forces this
 * {@link ApplicationScoped} bean to be built eagerly at application start, so a
 * malformed operator file fails the boot rather than the first read that
 * touches guidance. There is deliberately NO reload: changing the file requires
 * a container restart.
 *
 * <p><b>Coexistence.</b> A tenant may already carry a real row under the same
 * global key. In that case the real row wins and the overlay entry is
 * suppressed for that key, so the merge never doubles an entry.
 */
@ApplicationScoped
public class GuidanceOverlay {

    private static final Logger LOG = Logger.getLogger(GuidanceOverlay.class);

    /** Classpath path of the bundled default document (the fallback source). */
    static final String BUNDLED_RESOURCE = "/guidance/built-in-guidance.json";

    /** The slug of the single global scope every built-in entry belongs to. */
    private static final String GLOBAL_SLUG = "global";

    /**
     * Fixed namespace for the deterministic (name-based, version 5) identity of
     * each built-in entry. The name hashed under this namespace is the entry's
     * rename-invariant {@code logicalName}, so an entry keeps the same id even
     * if its fully-qualified key is later changed.
     */
    private static final UUID NAMESPACE =
        UUID.fromString("b1e6a9d4-3c2f-4a8e-9f17-2d6c8b0a5e34");

    /** Newest-first by last-update instant, matching the read paths' SQL order. */
    private static final Comparator<Memory> BY_UPDATED_DESC =
        Comparator.comparing((Memory m) -> m.updatedAt).reversed();

    /** Which source is active, and its resolved location — for the startup log. */
    private final GuidanceLoader.Source source;
    private final String resolvedPath;

    /** The document version string, for diagnostics. */
    private final String version;

    /** Pre-built, transient entries in document order. Never persisted. */
    private final List<Memory> entries;

    /** Fast lookup by synthetic id, for future addressing. */
    private final Map<UUID, Memory> byId;

    /**
     * CDI constructor. The external file path is a RUNTIME configuration
     * property ({@link MemoryConfig#systemGuidancePath()}) so it is settable in
     * a container without a rebuild; the default target is
     * {@code /etc/kumbuka/system-conventions.json}, overridable by the
     * {@code KUMBUKA_SYSTEM_GUIDANCE_PATH} environment variable. The default
     * lives once, on the config mapping (which travels with the jar so a
     * downstream runtime that consumes this module inherits it), and is not
     * hardcoded at any read site.
     */
    @Inject
    public GuidanceOverlay(MemoryConfig config) {
        this(GuidanceLoader.load(externalPathOf(config.systemGuidancePath()), BUNDLED_RESOURCE));
    }

    /** The configured path, or {@code null} when blank (force the bundled default). */
    private static Path externalPathOf(String configured) {
        return configured == null || configured.isBlank() ? null : Path.of(configured);
    }

    /**
     * Build from an already-resolved document. Package-private for unit tests,
     * which point the overlay at an arbitrary fixture (a different entry count
     * and different types) without a CDI container.
     */
    GuidanceOverlay(GuidanceLoader.Loaded loaded) {
        this.source = loaded.source();
        this.resolvedPath = loaded.resolvedPath();
        this.version = loaded.version();
        Instant stamp = loaded.versionDate();
        // One shared synthetic scope: the read DTOs only ever read slug + kind.
        Scope global = new Scope();
        global.slug = GLOBAL_SLUG;
        global.kind = ScopeKind.GLOBAL;

        List<Memory> built = new ArrayList<>(loaded.entries().size());
        Map<UUID, Memory> index = new LinkedHashMap<>();
        for (GuidanceLoader.Entry e : loaded.entries()) {
            Memory m = toMemory(e, global, stamp);
            built.add(m);
            index.put(m.logicalId, m);
        }
        this.entries = Collections.unmodifiableList(built);
        this.byId = Collections.unmodifiableMap(index);
    }

    /**
     * Eager startup load + the always-emitted diagnostic line. This observer
     * forces the bean to be built at application start (so a bad file aborts the
     * boot), and reports which source is active, the resolved path, the document
     * version and the entry count — enough for an operator to answer "is my file
     * being used?" from the log alone.
     */
    void onStart(@Observes StartupEvent ev) {
        LOG.infof(
            "system-guidance overlay ready: source=%s path=%s version=%s entries=%d "
            + "(read once at startup; editing the file requires a restart)",
            source, resolvedPath, version, entries.size());
    }

    private static Memory toMemory(GuidanceLoader.Entry e, Scope global, Instant stamp) {
        Memory m = new Memory();
        m.logicalId = uuidV5(NAMESPACE, e.logicalName());
        m.scope = global;
        m.type = MemoryType.fromDb(e.type());
        m.key = e.key();
        m.content = e.content();
        // Scope, privacy, source channel and lock are FIXED IN CODE and never
        // read from the document — this is a SECURITY property, not a default.
        // The overlay appends entries to the read result AFTER the SQL has run,
        // so none of the tenant/private predicates in the query apply to them.
        // That is safe only because every overlay entry is global, public and
        // system-locked by construction. An externally supplied scope (or
        // private flag, source channel or lock) would turn this file into a way
        // to inject content into arbitrary scopes past tenant isolation. Strict
        // parsing already rejects such a field outright; keep these hardcoded so
        // nobody later re-adds one as a "helpful" feature.
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

    /** The built-in entries, in document order. Unmodifiable. */
    public List<Memory> entries() {
        return entries;
    }

    /** Resolve a built-in entry by its synthetic id (empty if none matches). */
    public Optional<Memory> byId(UUID id) {
        return Optional.ofNullable(byId.get(id));
    }

    /** The document version string. */
    public String version() {
        return version;
    }

    /** Which of the two sources is active (external file vs bundled default).
     *  Package-private: for the startup log and the overlay's own tests. */
    GuidanceLoader.Source activeSource() {
        return source;
    }

    /** The resolved location of the active source. Package-private (tests). */
    String activePath() {
        return resolvedPath;
    }

    // ---------------------------------------------------------------------
    // Merge into the two read chokepoints
    // ---------------------------------------------------------------------

    /**
     * Merge the applicable built-in entries into a recall result. An entry
     * applies iff its OWN type, the query substring and its global scope
     * membership all match the same filters the recall SQL applied to the real
     * rows — an entry is never blindly appended. An applicable entry is
     * suppressed when a real global row already carries its key (the real row
     * wins). The returned list is re-sorted newest-first to match the SQL order.
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
     * applies iff its OWN type matches the type filter and the listing is
     * unscoped or scoped to the global scope. Suppression and ordering are as in
     * {@link #mergeIntoRecall}.
     */
    public List<Memory> mergeIntoShared(List<Memory> rows, String scopeSlug, MemoryType type) {
        List<Memory> survivors = new ArrayList<>();
        for (Memory e : entries) {
            if (typeMatches(type, e) && scopeMatchesShared(scopeSlug) && !shadowed(rows, e)) {
                survivors.add(e);
            }
        }
        return combineSorted(rows, survivors);
    }

    private boolean appliesToRecall(Memory e, String scopeSlug, MemoryType type,
                                    String query, boolean includeGlobal) {
        return typeMatches(type, e)
            && queryMatches(e, query)
            && scopeMatchesRecall(scopeSlug, includeGlobal);
    }

    /**
     * A type filter admits an entry when it is absent or equals THIS entry's own
     * type. Evaluated per entry, not against a constant: with a document that
     * may carry a glossary, a decision or any other type, an entry is served for
     * its own type and never under a different one. (Before extensibility this
     * compared against the constant {@code convention}, which both hid a
     * non-convention entry under its own type and wrongly served it under
     * {@code convention}.)
     */
    private static boolean typeMatches(MemoryType requested, Memory entry) {
        return requested == null || requested == entry.type;
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
}
