package ai.kumbuka.overlay;

/**
 * Thrown when the system-guidance source cannot be honoured: the external file
 * is present but unreadable, malformed, carries an unknown field, or fails
 * per-entry / per-document validation — or the bundled default itself fails
 * validation. It is raised during eager startup loading, so it aborts the boot
 * rather than surfacing on a later read (see {@link GuidanceOverlay}).
 *
 * <p>Fail-loud by design: a file that exists but cannot be honoured is NEVER
 * silently downgraded to the bundled default. That would hide an operator's
 * edit mistake behind working behaviour — the worst of both outcomes. The only
 * silent path is a genuinely absent external file, which is a deliberate
 * "use the bundled default" signal, not an error.
 */
public class GuidanceLoadException extends RuntimeException {

    public GuidanceLoadException(String message) {
        super(message);
    }

    public GuidanceLoadException(String message, Throwable cause) {
        super(message, cause);
    }
}
