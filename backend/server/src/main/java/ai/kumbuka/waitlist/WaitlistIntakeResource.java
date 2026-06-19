package ai.kumbuka.waitlist;

import io.agroal.api.AgroalDataSource;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * PUBLIC, unauthenticated beta waitlist intake (PROV-1a / ADR-0015, Session #23).
 *
 * <h3>Why this lives in kumbuka-server</h3>
 *
 * The two-backend split puts the waitlist <em>table</em> + approve flow in the
 * ops-console backend, but the public network path (console.kumbuka.ai/api/* →
 * kumbuka-backend) only exists here. So kumbuka-server owns ONLY this INSERT-only
 * intake endpoint. The Caddy edge routes {@code /api/public/*} to this backend
 * and rate-limits it.
 *
 * <h3>Security model</h3>
 *
 * Intentionally {@link PermitAll} and open — there is no bearer secret (contrast
 * {@code SeedTenantResource}, which is server-to-server). Abuse is contained by
 * the Caddy edge rate limit, not by this code. NOT {@code @TenantBound}: a
 * waitlist row is PRE-TENANT control-plane data (no {@code app.tenant_id}), so
 * the per-tenant RLS seam does not apply.
 *
 * <h3>Table</h3>
 *
 * Writes to {@code ops.waitlist_entry}, owned by the ops-console Flyway
 * (V4__waitlist_intake.sql is the source of truth). kumbuka-server's Flyway does
 * NOT manage it; the app role gets a narrow INSERT grant via the ops-console
 * deploy bootstrap (09-waitlist-grants.sql). This endpoint only ever INSERTs;
 * {@code status}/{@code id}/timestamps default in the DB.
 */
@Path("/api/public/waitlist-intake")
@PermitAll
public class WaitlistIntakeResource {

    private static final Logger LOG = Logger.getLogger(WaitlistIntakeResource.class);

    private static final String KEY_ERROR = "error";
    private static final String KEY_MESSAGE = "message";

    /** SQLState raised by the partial-unique index on a duplicate active email. */
    private static final String SQLSTATE_UNIQUE_VIOLATION = "23505";

    /**
     * Conservative email shape: a single {@code @}, a dot in the domain, and no
     * whitespace anywhere. Deliberately permissive on the local part — this is
     * an intake gate, not RFC 5322 validation; real deliverability is checked
     * downstream (the operator approve flow + actual email send).
     *
     * <p>The domain's first label excludes dots ({@code [^@\s.]+}) so the split
     * at the required {@code \.} is unambiguous — this keeps the match linear and
     * backtracking-free (Sonar java:S8786), unlike {@code [^@\s]+\.[^@\s]+} where
     * the dot could be placed multiple ways.
     */
    private static final Pattern EMAIL =
        Pattern.compile("^[^@\\s]+@[^@\\s.]+\\.[^@\\s]+$");

    private static final String INSERT_SQL =
        "INSERT INTO ops.waitlist_entry (email, team_name, contact, message) "
      + "VALUES (?, ?, ?, ?) RETURNING id";

    @Inject AgroalDataSource dataSource;

    /** Request body. {@code contact}/{@code message} are optional (nullable). */
    public record IntakeRequest(
        String email,
        String teamName,
        String contact,
        String message
    ) {}

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response intake(IntakeRequest req) {
        if (req == null) {
            return badRequest("missing request body");
        }

        final String email = trimToNull(req.email());
        final String teamName = trimToNull(req.teamName());
        final String contact = trimToNull(req.contact());
        final String message = trimToNull(req.message());

        if (email == null || !EMAIL.matcher(email).matches()) {
            return badRequest("a valid email address is required");
        }
        if (teamName == null) {
            return badRequest("teamName must not be blank");
        }

        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(INSERT_SQL)) {
            ps.setString(1, email);
            ps.setString(2, teamName);
            ps.setString(3, contact); // nullable — null maps to SQL NULL
            ps.setString(4, message); // nullable — null maps to SQL NULL
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                String id = rs.getString(1);
                return Response.ok(Map.of("success", true, "id", id)).build();
            }
        } catch (SQLException e) {
            if (SQLSTATE_UNIQUE_VIOLATION.equals(e.getSQLState())) {
                // Duplicate active (pending/approved) email — the partial-unique
                // index excludes 'rejected', so a re-application after rejection
                // is still allowed.
                return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of(
                        KEY_ERROR, "already_registered",
                        KEY_MESSAGE, "this email is already on the waitlist"))
                    .build();
            }
            // Do not echo the DB error to an unauthenticated caller; log it.
            LOG.error("waitlist-intake insert failed", e);
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(Map.of(
                    KEY_ERROR, "internal_error",
                    KEY_MESSAGE, "could not record the request"))
                .build();
        }
    }

    private static Response badRequest(String message) {
        return Response.status(Response.Status.BAD_REQUEST)
            .entity(Map.of(KEY_ERROR, "invalid_request", KEY_MESSAGE, message))
            .build();
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
