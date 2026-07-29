package ai.kumbuka.admin;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AdminConnectorResource#resolveMcpUrl(String, String)} —
 * the pure URL resolution, exercised without CDI or a database.
 */
class McpUrlResolutionTest {

    private static final String BASE = "https://dev.kumbuka.ai";

    @Test
    void ce_default_whenTemplateBlank() {
        assertThat(AdminConnectorResource.resolveMcpUrl("", BASE))
            .isEqualTo("https://dev.kumbuka.ai/mcp");
        assertThat(AdminConnectorResource.resolveMcpUrl(null, BASE))
            .isEqualTo("https://dev.kumbuka.ai/mcp");
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
