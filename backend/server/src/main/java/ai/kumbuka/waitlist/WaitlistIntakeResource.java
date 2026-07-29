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
 * the internal server-to-server endpoints such as {@code EraseSubjectResource},
 * which require one). Abuse is contained by
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

    // UTM attribution field caps (D-OPS-32 (a)). Sanitizing lengths only — the DB
    // columns are plain TEXT (ops-console V7); the public intake truncates
    // defensively because it must not trust upstream (the n8n router also caps).
    private static final int MAX_UTM_SOURCE = 64;
    private static final int MAX_UTM_MEDIUM = 64;
    private static final int MAX_UTM_CAMPAIGN = 128;
    private static final int MAX_UTM_CONTENT = 128;
    private static final int MAX_REFERRER = 256;

    // Core-field caps: the endpoint is public, so without a server-side cap a
    // single request could park megabytes in a TEXT column. Same truncation
    // stance as the attribution fields — never reject over length, just cap.
    // Email is the exception: a truncated address is a wrong address, so
    // over-long emails fail the validity gate (400) instead of being cut.
    private static final int MAX_EMAIL = 254; // practical SMTP address limit
    private static final int MAX_TEAM_NAME = 256;
    private static final int MAX_CONTACT = 256;
    private static final int MAX_MESSAGE = 2000;

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
        "INSERT INTO ops.waitlist_entry "
      + "(email, team_name, contact, message, language, "
      + "utm_source, utm_medium, utm_campaign, utm_content, referrer) "
      + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id";

    @Inject AgroalDataSource dataSource;

    /**
     * Request body. Everything past {@code teamName} is optional (nullable).
     *
     * <p>The five {@code utm*}/{@code referrer} fields are campaign attribution
     * (D-OPS-32 (a)): camelCase on the wire, stored snake_case. Absent -> null;
     * no client- or server-side "required" validation, so organic no-UTM traffic
     * stays valid. {@code referrer} is already trimmed to origin (scheme+host) by
     * the web client — the intake stores and caps it, it does NOT re-derive it.
     * Unknown extra JSON fields are ignored (never a hard reject).
     *
     * <p>{@code language} is the visitor's site language at submit; it lets a
     * downstream consumer localize the invitation email. Unlike the tolerant
     * UTM pass-through it is normalized to {@code de}/{@code en} — anything
     * else is stored as SQL NULL so the default locale applies downstream.
     */
    public record IntakeRequest(
        String email,
        String teamName,
        String contact,
        String message,
        String language,
        String utmSource,
        String utmMedium,
        String utmCampaign,
        String utmContent,
        String referrer
    ) {}

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response intake(IntakeRequest req) {
        if (req == null) {
            return badRequest("missing request body");
        }

        final String email = trimToNull(req.email());
        final String teamName = sanitize(req.teamName(), MAX_TEAM_NAME);
        final String contact = sanitize(req.contact(), MAX_CONTACT);
        final String message = sanitize(req.message(), MAX_MESSAGE);
        final String language = normalizeLanguage(req.language());

        // Attribution (D-OPS-32 (c),(e)): sanitize defensively — blank -> null,
        // truncate to cap. No enum/format validation, no hard reject on unknown
        // values (forensic visibility of mis-values beats enforced hygiene).
        final String utmSource = sanitize(req.utmSource(), MAX_UTM_SOURCE);
        final String utmMedium = sanitize(req.utmMedium(), MAX_UTM_MEDIUM);
        final String utmCampaign = sanitize(req.utmCampaign(), MAX_UTM_CAMPAIGN);
        final String utmContent = sanitize(req.utmContent(), MAX_UTM_CONTENT);
        final String referrer = sanitize(req.referrer(), MAX_REFERRER);

        if (email == null || email.length() > MAX_EMAIL || !EMAIL.matcher(email).matches()) {
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
            ps.setString(5, language);    // site language — nullable, normalized
            ps.setString(6, utmSource);   // attribution — nullable
            ps.setString(7, utmMedium);   // attribution — nullable
            ps.setString(8, utmCampaign); // attribution — nullable
            ps.setString(9, utmContent);  // attribution — nullable
            ps.setString(10, referrer);   // attribution — nullable
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

    /**
     * Defensive attribution sanitizing (D-OPS-32 (c)): blank/empty -> null, then
     * truncate to {@code maxLen}. No enum or format validation — this endpoint is
     * public and must tolerate any value without rejecting the whole request.
     */
    private static String sanitize(String raw, int maxLen) {
        String t = trimToNull(raw);
        if (t == null) {
            return null;
        }
        return t.length() > maxLen ? t.substring(0, maxLen) : t;
    }

    /**
     * Normalize the visitor's site language to the closed set {@code de}/{@code en}.
     * Deliberately stricter than the UTM pass-through: this value drives the
     * invitation email locale downstream, so a garbage value would break locale
     * selection — anything outside the set is stored as SQL NULL (the downstream
     * falls back to its default locale). The request itself is never rejected.
     */
    private static String normalizeLanguage(String raw) {
        String t = trimToNull(raw);
        if (t == null) {
            return null;
        }
        String lc = t.toLowerCase(java.util.Locale.ROOT);
        return ("de".equals(lc) || "en".equals(lc)) ? lc : null;
    }
}
