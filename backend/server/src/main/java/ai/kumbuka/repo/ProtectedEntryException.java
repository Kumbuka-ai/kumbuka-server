package ai.kumbuka.repo;

/**
 * Thrown when a non-system caller's write or delete would touch a protected
 * (D-CORE-11) row, or address a key in the reserved namespace.
 *
 * <p>Cases:
 * <ul>
 *   <li>{@link Reason#UPSERT_BLOCKED} — caller's write targets a key that
 *       already has a protected row in the same (tenant, scope). Pre-check;
 *       no row is created or modified.</li>
 *   <li>{@link Reason#UPDATE_BLOCKED} — caller's update, or the console
 *       single-delete, targets a locked row. This is the load-bearing
 *       application guard: there is no update or delete trigger below it (the
 *       structural delete-block was dropped in V20). Pre-check; the row is
 *       left untouched.</li>
 *   <li>{@link Reason#RESERVED_NAMESPACE} — a non-system caller's write OR
 *       delete addresses a key in the reserved {@code system} namespace
 *       ({@link ai.kumbuka.util.SystemKeyNamespace}). Row-independent: it fires
 *       whether or not a built-in entry currently exists under the key. Pre-
 *       check; no row is created, modified or deleted.</li>
 * </ul>
 *
 * Translated to HTTP 409 by the MCP / admin error mappers, whose code is derived
 * generically from the reason name — a new reason needs no mapper change.
 */
public class ProtectedEntryException extends RuntimeException {

    public enum Reason { UPSERT_BLOCKED, UPDATE_BLOCKED, RESERVED_NAMESPACE }

    private final Reason reason;
    private final String key;

    public ProtectedEntryException(Reason reason, String key, String message) {
        super(message);
        this.reason = reason;
        this.key = key;
    }

    public Reason reason() { return reason; }
    public String key()    { return key; }
}
