package ai.kumbuka.admin;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AdminConnectorResource#resolveMcpUrl(String, String)} —
 * the pure URL resolution, exercised without CDI or a database.
 *
 * <p>The CE case used to assert a URL built from this service's own base. It
 * asserts the absence of one now: the core stopped serving a connector surface
 * when the memory engine left, and a card naming an address that answers 404
 * sends a team off to debug their client for a fault that is ours.
 */
class McpUrlResolutionTest {

    private static final String BASE = "https://dev.kumbuka.ai";

    @Test
    void ce_namesNoEndpoint_whenTemplateBlank() {
        assertThat(AdminConnectorResource.resolveMcpUrl("", BASE))
            .as("no template configured: this service has no endpoint of its own to name")
            .isNull();
        assertThat(AdminConnectorResource.resolveMcpUrl(null, BASE))
            .isNull();
    }

    @Test
    void ce_neverDerivesAnEndpointFromItsOwnBaseUrl() {
        // The guard against the old fallback creeping back in under a different
        // spelling: whatever CE returns, it must not be built out of BASE.
        // Stated over the value rather than with doesNotContain, which needs a
        // non-null actual — and null is the expected answer here.
        String ce = AdminConnectorResource.resolveMcpUrl("", BASE);
        assertThat(ce == null || !ce.contains(BASE))
            .as("CE returned <%s>, which is derived from this service's own base URL", ce)
            .isTrue();
    }

    @Test
    void saas_returnsGenericTemplateVerbatim() {
        // The SaaS template is the single generic endpoint with
        // no <alias> placeholder — returned verbatim, no substitution.
        assertThat(AdminConnectorResource.resolveMcpUrl(
                "https://mcp.kumbuka.ai/mcp", BASE))
            .isEqualTo("https://mcp.kumbuka.ai/mcp");
    }

    @Test
    void passesThrough_anyNonBlankTemplateVerbatim() {
        assertThat(AdminConnectorResource.resolveMcpUrl(
                "https://fixed.example/mcp", BASE))
            .isEqualTo("https://fixed.example/mcp");
    }
}
