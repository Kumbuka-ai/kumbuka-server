package ai.kumbuka.admin;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AdminConnectorResource#resolveMcpUrl(String)} —
 * the pure URL resolution, exercised without CDI or a database.
 *
 * <p>The CE case used to assert a URL built from this service's own base. It
 * asserts the absence of one now: the core stopped serving a connector surface
 * when the memory engine left, and a card naming an address that answers 404
 * sends a team off to debug their client for a fault that is ours.
 */
class McpUrlResolutionTest {

    @Test
    void ce_namesNoEndpoint_whenTemplateBlank() {
        assertThat(AdminConnectorResource.resolveMcpUrl(""))
            .as("no template configured: this service has no endpoint of its own to name")
            .isNull();
        assertThat(AdminConnectorResource.resolveMcpUrl(null))
            .isNull();
    }

    @Test
    void ce_cannotDeriveAnEndpointFromThisServicesOwnBaseUrl() throws Exception {
        // The guard against the old fallback creeping back in under a different
        // spelling, and it is structural rather than behavioural: the resolver
        // is not handed the base URL at all, so there is nothing to append a
        // path to. Re-adding the parameter is what would have to happen first,
        // and that is what this assertion makes visible.
        assertThat(AdminConnectorResource.class
                .getDeclaredMethod("resolveMcpUrl", String.class).getParameterCount())
            .as("resolveMcpUrl takes the template alone — no base URL to build a route from")
            .isEqualTo(1);
    }

    @Test
    void saas_returnsGenericTemplateVerbatim() {
        // The SaaS template is the single generic endpoint with
        // no <alias> placeholder — returned verbatim, no substitution.
        assertThat(AdminConnectorResource.resolveMcpUrl("https://mcp.kumbuka.ai/mcp"))
            .isEqualTo("https://mcp.kumbuka.ai/mcp");
    }

    @Test
    void passesThrough_anyNonBlankTemplateVerbatim() {
        assertThat(AdminConnectorResource.resolveMcpUrl("https://fixed.example/mcp"))
            .isEqualTo("https://fixed.example/mcp");
    }
}
