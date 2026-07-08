package ai.kumbuka.wellknown;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

import java.util.List;

/**
 * Typed configuration for the OAuth 2.0 Protected Resource Metadata document
 * (RFC 9728) served by {@link ProtectedResourceMetadataResource}.
 *
 * <p>Both values were previously hardcoded in the resource. They are
 * externalised here so the advertised discovery contract can be curated per
 * deployment without a code change. The split was motivated by <b>F-0119</b>:
 * the advertised {@code scopes_supported} set was too narrow and omitted
 * {@code offline_access}, which caused strict MCP clients to abort before the
 * authorization request (see {@link #scopesSupported()}).
 *
 * @see ProtectedResourceMetadataResource
 */
@ConfigMapping(prefix = "kumbuka.connector-metadata")
public interface ConnectorMetadataConfig {

    /**
     * Scopes advertised in the metadata document's {@code scopes_supported}.
     *
     * <p>This list is a <b>deliberately narrow curation, not an oversight</b>:
     * it advertises exactly the scopes the connector's consent requires and no
     * more, to keep the consent screen from bloating. Do NOT widen it with
     * {@code address}/{@code phone}/{@code roles}.
     *
     * <p>It MUST include {@code offline_access}: the connector requests a
     * refresh token (offline access), and a strict MCP client (Claude,
     * ChatGPT) that finds a requested scope missing from the resource server's
     * advertised {@code scopes_supported} aborts <em>before</em> issuing the
     * authorization request — no request ever reaches Keycloak or the resource
     * server, and the client only reports a generic authorization failure.
     * That is the F-0119 failure.
     */
    @WithName("scopes-supported")
    @WithDefault("openid,profile,email,offline_access")
    List<String> scopesSupported();

    /**
     * Algorithms advertised in {@code resource_signing_alg_values_supported}.
     *
     * <p><b>This is NOT a free tuning knob.</b> The advertised algorithm MUST
     * match the algorithm the authorization server (the Keycloak realm)
     * actually signs its tokens with — currently RS256. Advertising an
     * algorithm the AS does not use lies about the resource server's contract
     * and breaks strict clients: the same failure class as F-0119, inverted.
     * Change this only in lock-step with the realm's token signing algorithm.
     */
    @WithName("resource-signing-alg-values")
    @WithDefault("RS256")
    List<String> resourceSigningAlgValues();
}
