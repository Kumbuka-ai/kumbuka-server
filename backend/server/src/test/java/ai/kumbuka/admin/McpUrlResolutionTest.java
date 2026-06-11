package ai.kumbuka.admin;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AdminConnectorResource#resolveMcpUrl(String, String, String)} —
 * the pure D-CORE-4 URL resolution, exercised without CDI or a database.
 */
class McpUrlResolutionTest {

    private static final String BASE = "https://dev.kumbuka.ai";

    @Test
    void ce_default_whenTemplateBlank() {
        assertThat(AdminConnectorResource.resolveMcpUrl("", BASE, "default"))
            .isEqualTo("https://dev.kumbuka.ai/mcp");
        assertThat(AdminConnectorResource.resolveMcpUrl(null, BASE, "default"))
            .isEqualTo("https://dev.kumbuka.ai/mcp");
    }

    @Test
    void saas_substitutesAlias() {
        assertThat(AdminConnectorResource.resolveMcpUrl(
                "https://<alias>.kumbuka.ai/mcp", BASE, "acme"))
            .isEqualTo("https://acme.kumbuka.ai/mcp");
    }

    @Test
    void saas_fallsBackToBase_whenAliasMissing() {
        assertThat(AdminConnectorResource.resolveMcpUrl(
                "https://<alias>.kumbuka.ai/mcp", BASE, null))
            .isEqualTo("https://dev.kumbuka.ai/mcp");
        assertThat(AdminConnectorResource.resolveMcpUrl(
                "https://<alias>.kumbuka.ai/mcp", BASE, "  "))
            .isEqualTo("https://dev.kumbuka.ai/mcp");
    }

    @Test
    void passesThrough_templateWithoutPlaceholder() {
        assertThat(AdminConnectorResource.resolveMcpUrl(
                "https://fixed.example/mcp", BASE, "acme"))
            .isEqualTo("https://fixed.example/mcp");
    }
}
