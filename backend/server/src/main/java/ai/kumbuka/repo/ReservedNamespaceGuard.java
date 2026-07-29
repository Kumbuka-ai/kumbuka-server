package ai.kumbuka.repo;

import ai.kumbuka.overlay.GuidanceOverlay;
import ai.kumbuka.util.SystemKeyNamespace;

import java.util.UUID;

/**
 * The reserved-namespace guard for the DELETE paths — the delete-side twin of the
 * write-side {@code MemoryRepository.assertKeyNamespaceAllowed}. The rule is
 * formulated once, here, and called by every caller-facing delete seam
 * ({@link MemoryRepository#forget}, {@link SharedMemoryRepository#deleteShared}),
 * never re-implemented per path and never in a protocol adapter.
 *
 * <p>A non-system caller may not delete a key in the reserved {@code system}
 * namespace ({@link SystemKeyNamespace}). It is <b>row-independent</b>: it holds
 * whether or not a row exists under the key, exactly as the write guard does — so
 * a delete of a reserved key is refused even when there is nothing to delete. The
 * empty reserved key is the common case, not an edge one: the built-in guidance an
 * assistant sees is served by the {@link GuidanceOverlay}, never as a table row,
 * so on the write side the guard is the only place the rule is stated and the
 * delete side must say it too — otherwise a {@code memory_forget} on a reserved key
 * returns {@code deleted: 0}, indistinguishable from a key that never existed, and
 * the reservation is never learnable from the delete surface.
 *
 * <p>A delete addresses a reserved key when:
 * <ul>
 *   <li>it names the key directly ({@code forget} by key), or</li>
 *   <li>its {@code logicalId} resolves to a built-in guidance entry whose key is
 *       reserved — the synthetic ids the overlay stamps into every recall result,
 *       which an assistant has directly in front of it (the likelier delete
 *       address than a hand-typed key). A row that actually carries a reserved key
 *       (only reachable below the write seam) is caught the same way once the
 *       caller has resolved it — {@code deleteShared} passes the loaded row's key.</li>
 * </ul>
 * A {@code logicalId} that resolves to nothing — no row and no overlay entry —
 * addresses no reserved key and is deliberately left to return {@code deleted: 0}:
 * it names nothing, not a reserved key.
 *
 * <p>These seams have no {@code source} parameter and are never the server-derived
 * system channel (the console single-delete carries no channel; {@code forget} is
 * only ever a caller-facing MCP delete), so the guard is <b>unconditional</b> —
 * there is no exempt channel to check, unlike the write guard.
 *
 * <p>Tenant-teardown and member-erasure delete AROUND these seams (their own
 * {@code Memory.deleteAll} / {@code Memory.delete} bulk statements), so this guard
 * never fires in a purge or an erasure — the
 * unlock-then-delete exemption for a dying tenant is preserved by construction.
 */
final class ReservedNamespaceGuard {

    private ReservedNamespaceGuard() {}

    /**
     * Reject a delete that addresses a key in the reserved namespace — by key, or
     * by logical id resolved through the guidance overlay. A no-op otherwise.
     *
     * @param key       the addressed key, or null when the delete addresses by id
     * @param logicalId the addressed logical id, or null when it addresses by key
     * @param guidance  the built-in guidance overlay, to resolve a synthetic id
     */
    static void assertDeleteAllowed(String key, UUID logicalId, GuidanceOverlay guidance) {
        String reservedKey = reservedKeyAddressed(key, logicalId, guidance);
        if (reservedKey != null) {
            throw new ProtectedEntryException(
                ProtectedEntryException.Reason.RESERVED_NAMESPACE, reservedKey,
                "key '" + reservedKey + "' is in the reserved '" + SystemKeyNamespace.ROOT
                + "' namespace — its entries are built-in guidance, owned by the platform "
                + "and not by the tenant, so they cannot be deleted.");
        }
    }

    /** The reserved key this delete addresses, or null if it addresses none. */
    private static String reservedKeyAddressed(String key, UUID logicalId, GuidanceOverlay guidance) {
        if (key != null && SystemKeyNamespace.isReserved(key)) {
            return key;
        }
        if (logicalId != null) {
            return guidance.byId(logicalId)
                .map(m -> m.key)
                .filter(SystemKeyNamespace::isReserved)
                .orElse(null);
        }
        return null;
    }
}
