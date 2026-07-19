package ai.kumbuka.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wire-level end-to-end test of the write-rate limiter on the MCP surface:
 * a real Keycloak issues a real bearer, and the full Streamable-HTTP
 * JSON-RPC dance runs against {@code /mcp} — initialize, initialized
 * notification, then repeated {@code tools/call memory_remember} under a
 * tiny write band (burst 3, refill 1/60s).
 *
 * <p>What this proves beyond {@code McpWriteRateLimitIT} (which drives the
 * tool bean in-JVM): the enforcement interceptor sees the REAL request
 * identity on the MCP dispatch path, and — the client-facing contract —
 * a throttled call surfaces as a clean tool RESULT with
 * {@code isError: true} and a human-readable message carrying a retry
 * hint, never a bare JSON-RPC internal error. That message is what an
 * assistant shows its user, so its shape is asserted here on the wire.
 */
@QuarkusTest
@QuarkusTestResource(value = KeycloakTestResource.class, restrictToAnnotatedClass = true)
@TestProfile(McpWireRateLimitIT.TinyBandOidcProfile.class)
@Tag("integration")
class McpWireRateLimitIT {

    /** {@link OidcEnabledProfile} plus a tiny default write band. */
    public static class TinyBandOidcProfile extends OidcEnabledProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> overrides = new HashMap<>(super.getConfigOverrides());
            overrides.put("kumbuka.rate-limit.default-burst-capacity", "3");
            overrides.put("kumbuka.rate-limit.default-refill-tokens", "1");
            overrides.put("kumbuka.rate-limit.default-refill-period-seconds", "60");
            return overrides;
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private String issuer() {
        return ConfigProvider.getConfig().getValue("test.keycloak.issuer", String.class);
    }

    @Test
    void wire_writesPassWithinBurst_thenToolErrorWithRetryHint() throws Exception {
        String token = passwordGrant("member@local", "member");
        assertThat(token).isNotBlank();

        // ---- MCP handshake -------------------------------------------------
        Response init = mcpPost(token, null, """
            {"jsonrpc":"2.0","id":1,"method":"initialize",
             "params":{"protocolVersion":"2024-11-05",
                       "clientInfo":{"name":"rate-limit-it","version":"1.0"},
                       "capabilities":{}}}
            """);
        assertThat(init.statusCode())
            .as("initialize must pass auth and succeed (body: %s)", init.body().asString())
            .isEqualTo(200);
        String session = init.getHeader("Mcp-Session-Id");

        Response initialized = mcpPost(token, session,
            "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        assertThat(initialized.statusCode())
            .as("initialized notification accepted (body: %s)", initialized.body().asString())
            .isIn(200, 202);

        // ---- three writes inside the burst pass as normal tool results ----
        for (int i = 0; i < 3; i++) {
            JsonNode result = toolCallResult(token, session, 10 + i, "wire write " + i);
            assertThat(result.path("isError").asBoolean(false))
                .as("write %d is within the burst and must not error: %s", i + 1, result)
                .isFalse();
        }

        // ---- the fourth is throttled: a tool RESULT, isError, retry hint ---
        JsonNode throttled = toolCallResult(token, session, 20, "wire write past band");
        assertThat(throttled.path("isError").asBoolean(false))
            .as("the over-burst write must surface as a tool error result: %s", throttled)
            .isTrue();
        String text = throttled.path("content").path(0).path("text").asText("");
        assertThat(text)
            .as("the tool error text is the client-facing message an assistant shows its user")
            .contains("write rate limit exceeded")
            .contains("retry in");
    }

    // ---- helpers -----------------------------------------------------------

    /**
     * POST a JSON-RPC body to /mcp with the Streamable-HTTP Accept pair and,
     * when the server issued one, the session header.
     */
    private Response mcpPost(String token, String session, String body) {
        var spec = given()
            .header("Authorization", "Bearer " + token)
            .header("Accept", "application/json, text/event-stream")
            .contentType(ContentType.JSON)
            .body(body);
        if (session != null) {
            spec = spec.header("Mcp-Session-Id", session);
        }
        return spec.when().post("/mcp");
    }

    /** Issue a memory_remember tools/call and return the JSON-RPC {@code result} node. */
    private JsonNode toolCallResult(String token, String session, int id, String content)
            throws Exception {
        Response r = mcpPost(token, session, """
            {"jsonrpc":"2.0","id":%d,"method":"tools/call",
             "params":{"name":"memory_remember",
                       "arguments":{"content":"%s","type":"status","scope":"global"}}}
            """.formatted(id, content));
        assertThat(r.statusCode())
            .as("tools/call must be dispatched, not rejected at the HTTP layer (body: %s)",
                r.body().asString())
            .isEqualTo(200);
        JsonNode rpc = parseJsonRpc(r);
        assertThat(rpc.has("error"))
            .as("a throttled write must be a tool RESULT, never a protocol-level error: %s", rpc)
            .isFalse();
        return rpc.path("result");
    }

    /**
     * The Streamable-HTTP response is either plain JSON or an SSE frame
     * ({@code data: <json>} lines); normalise both to the JSON-RPC envelope.
     */
    private static JsonNode parseJsonRpc(Response r) throws Exception {
        String body = r.body().asString().trim();
        if (body.startsWith("{")) {
            return MAPPER.readTree(body);
        }
        StringBuilder data = new StringBuilder();
        for (String line : body.split("\n")) {
            if (line.startsWith("data:")) {
                data.append(line.substring(5).trim());
            }
        }
        assertThat(data)
            .as("response carried neither JSON nor SSE data lines: %s", body)
            .isNotEmpty();
        return MAPPER.readTree(data.toString());
    }

    private String passwordGrant(String user, String password) {
        return given()
            .baseUri(issuer())
            .contentType(ContentType.URLENC)
            .formParam("grant_type", "password")
            .formParam("client_id", "kumbuka-connector")
            .formParam("client_secret", "change-me-kumbuka-connector-secret")
            .formParam("username", user)
            .formParam("password", password)
            .formParam("scope", "openid")
            .when().post("/protocol/openid-connect/token")
            .then().statusCode(200)
            .extract().jsonPath().getString("access_token");
    }
}
