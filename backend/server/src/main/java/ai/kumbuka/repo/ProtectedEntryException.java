package ai.kumbuka.repo;

/**
 * Thrown by {@link MemoryRepository} when a non-system caller's write or
 * delete would touch a protected (D-CORE-11) row.
 *
 * <p>Two cases:
 * <ul>
 *   <li>{@link Reason#UPSERT_BLOCKED} — caller's write targets a key that
 *       already has a protected row in the same (tenant, scope). Pre-check;
 *       no row is created or modified.</li>
 *   <li>{@link Reason#UPDATE_BLOCKED} — caller's update by id targets a
 *       protected row (e.g. the admin PATCH/console editor). Pre-check;
 *       the row is left untouched.</li>
 *   <li>{@link Reason#DELETE_BLOCKED} — caller's delete reached the
 *       structural {@code memory_protected_delete_block} trigger and was
 *       raised as PSQL SQLSTATE P0001. Surfaced as a typed error instead
 *       of a raw 500.</li>
 * </ul>
 *
 * Translated to HTTP 409 by the MCP / admin error mappers.
 */
public class ProtectedEntryException extends RuntimeException {

    public enum Reason { UPSERT_BLOCKED, UPDATE_BLOCKED, DELETE_BLOCKED }

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
