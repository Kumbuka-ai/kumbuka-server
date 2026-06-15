package ai.kumbuka.repo;

import org.postgresql.util.PSQLException;

/**
 * Shared helper for translating the {@code memory_protected_delete_block}
 * trigger's PSQL exception (D-CORE-11) into a typed
 * {@link ProtectedEntryException}. Used by both {@link MemoryRepository#forget}
 * and {@link SharedMemoryRepository#deleteShared} — the trigger fires below
 * either app-layer delete path.
 *
 * Single-method package-private utility so the decision logic exists
 * exactly once; unit-tested directly without standing up the DB.
 */
final class ProtectedDeleteBlockDetector {

    /** SQLSTATE raised by {@code RAISE EXCEPTION} without an explicit code. */
    private static final String RAISE_EXCEPTION_SQLSTATE = "P0001";

    /** Marker substring in the trigger's RAISE message. */
    private static final String TRIGGER_MESSAGE_MARKER = "memory row is protected";

    private ProtectedDeleteBlockDetector() {}

    /**
     * Walk the exception chain looking for the {@code memory_protected_delete_block}
     * trigger raise. Returns true when found.
     */
    static boolean isProtectedDeleteBlock(Throwable t) {
        while (t != null) {
            if (t instanceof PSQLException pe
                    && RAISE_EXCEPTION_SQLSTATE.equals(pe.getSQLState())
                    && pe.getMessage() != null
                    && pe.getMessage().contains(TRIGGER_MESSAGE_MARKER)) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }
}
