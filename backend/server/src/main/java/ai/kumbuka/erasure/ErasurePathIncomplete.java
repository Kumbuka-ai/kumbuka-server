package ai.kumbuka.erasure;

import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.UUID;

/**
 * The refusal both internal erasure endpoints answer with while the erasure
 * path across the service boundary does not exist.
 *
 * <h3>What it is for</h3>
 *
 * <p>Since the memory engine left the core, neither endpoint can discharge
 * the erasure it is asked for. What each of them still holds is the core's
 * own share — the scope-provenance tombstone, and the administrative rows of
 * a purged tenant. Running that share alone is worse than refusing: it leaves
 * a tenant that is half erased, and no participant left holding a record that
 * says so. The provider reads a 2xx as "the content is gone" and moves on to
 * Keycloak and its own rows; the core has nowhere to write down that the rest
 * never happened. So the endpoints refuse, before they change anything, and
 * the request stays open until the path exists.
 *
 * <h3>Why 503</h3>
 *
 * <p>The refusal is bound to a missing component, not to the caller, and it
 * ends when that component is built. The ops-console treats any answer
 * outside 2xx as "not reachable" and aborts, which is the intended effect
 * here.
 *
 * <h3>How it is lifted</h3>
 *
 * <p>By deleting this class and the two calls to it — no configuration key,
 * no feature flag, no profile brings the old behaviour back. The commission
 * that builds the conductor of the erasure path is the one that removes it,
 * together with the endpoints' Javadoc. {@link MemberErasureService} and
 * {@link TenantDataPurgeService} stay untouched meanwhile: they are the
 * core's share, and the conductor is what will call them.
 */
final class ErasurePathIncomplete {

    private static final Logger LOG = Logger.getLogger(ErasurePathIncomplete.class);

    /** Error identifier of the refusal, shared by both endpoints. */
    static final String ERROR = "erasure_path_incomplete";

    static final String ERASE_MESSAGE =
        "member erasure is refused: the erasure path across the service boundary "
      + "is not built yet, and this service alone cannot erase a member's data";

    static final String PURGE_MESSAGE =
        "tenant purge is refused: the erasure path across the service boundary "
      + "is not built yet, and this service alone cannot purge a tenant's data";

    private ErasurePathIncomplete() {}

    /**
     * The refusal, in the envelope these endpoints already use. Carries no
     * count: a number here would be read as an erasure that happened.
     *
     * <p>Logs one WARN line per refusal — the endpoint and the tenant, so an
     * operator whose deletion request went nowhere can find it. Never the
     * subject and never content: this is the erasure path, and a log line
     * that named the member would outlive the data it is about.
     *
     * @param endpoint the path that refused, for the log line
     * @param tenant   the tenant the call named, for the log line
     * @param message  the endpoint's own refusal message
     */
    static Response refuse(String endpoint, UUID tenant, String message) {
        LOG.warnf(
            "%s refused: the erasure path across the service boundary is not built yet;"
            + " nothing was changed for tenant %s", endpoint, tenant);
        return Response.status(Response.Status.SERVICE_UNAVAILABLE)
            .entity(Map.of("error", ERROR, "message", message))
            .build();
    }
}
