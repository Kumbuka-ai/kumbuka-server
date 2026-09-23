package ai.kumbuka.config;

import ai.kumbuka.testsupport.RepositoryRoot;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the boot-guard across the guarded tenants — {@code admin} alone, since
 * the {@code mcp} bearer tenant left with the memory engine. Exercised as a
 * {@link QuarkusTest} so the branch coverage
 * lands in jacoco-quarkus.exec (a plain JUnit test would run outside the
 * Quarkus classloader and contribute zero coverage).
 *
 * Real invariants the test enforces, per tenant:
 *   • tenant disabled → guard is a no-op (data-layer test boot path)
 *   • token.principal-claim=sub → passes (the corrected, effective pin)
 *   • legacy un-segmented key present → aborts (the regression tripwire)
 *   • correct key missing or != sub → aborts (pin not effective)
 *   • the real test-profile config boots the observer without throwing
 *   • the SHIPPED application.properties pins token.principal-claim=sub for
 *     every guarded tenant and carries no un-segmented key — the one case that
 *     reads the file this service ships rather than one the test wrote
 */
@QuarkusTest
class OidcPrincipalClaimGuardTest {

    @Inject
    OidcPrincipalClaimGuard guard;

    /** Mock with one tenant enabled and its keys at the given state. */
    private Config configFor(String tenant, String legacy, String correct) {
        Config c = mock(Config.class);
        when(c.getOptionalValue("quarkus.oidc." + tenant + ".tenant-enabled", Boolean.class))
            .thenReturn(Optional.of(true));
        when(c.getOptionalValue("quarkus.oidc." + tenant + ".principal-claim", String.class))
            .thenReturn(Optional.ofNullable(legacy));
        when(c.getOptionalValue("quarkus.oidc." + tenant + ".token.principal-claim", String.class))
            .thenReturn(Optional.ofNullable(correct));
        return c;
    }

    @Test
    void passesWhenCorrectKeyIsSub() {
        assertThatCode(() -> OidcPrincipalClaimGuard.verifyTenant(configFor("admin", null, "sub"), "admin"))
            .doesNotThrowAnyException();
    }

    @Test
    void tripwireFiresOnLegacyMisPath() {
        // Even with the correct key also present, a stray legacy key is a defect signal.
        assertThatThrownBy(() -> OidcPrincipalClaimGuard.verifyTenant(configFor("admin", "sub", "sub"), "admin"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("quarkus.oidc.admin.token.principal-claim");
    }

    @Test
    void abortsWhenCorrectKeyMissing() {
        assertThatThrownBy(() -> OidcPrincipalClaimGuard.verifyTenant(configFor("admin", null, null), "admin"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("ADR-0008");
    }

    @Test
    void abortsWhenCorrectKeyNotSub() {
        assertThatThrownBy(() ->
            OidcPrincipalClaimGuard.verifyTenant(configFor("admin", null, "preferred_username"), "admin"))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void noOpWhenTenantDisabled() {
        Config c = mock(Config.class);
        when(c.getOptionalValue("quarkus.oidc.admin.tenant-enabled", Boolean.class))
            .thenReturn(Optional.of(false));
        // Legacy key present + correct missing, but tenant off → no enforcement.
        when(c.getOptionalValue("quarkus.oidc.admin.principal-claim", String.class))
            .thenReturn(Optional.of("sub"));
        assertThatCode(() -> OidcPrincipalClaimGuard.verifyTenant(c, "admin")).doesNotThrowAnyException();
    }

    @Test
    void guardedTenantsAreTheAdminTenantAlone() {
        // The `mcp` bearer tenant was the second guarded tenant until the memory
        // engine left the core. Pinned here so re-adding a tenant is a decision
        // taken in the open rather than a config line nobody guards.
        assertThat(OidcPrincipalClaimGuard.GUARDED_TENANTS).containsExactly("admin");
    }

    @Test
    void observerRunsAgainstRealConfigWithoutThrowing() {
        // The test profile disables both guarded tenants, so onStart exercises
        // the observer wiring + the disabled-branch no-op against the real config.
        assertThatCode(() -> guard.onStart(null)).doesNotThrowAnyException();
    }

    /**
     * The one case that reads the file this service actually ships.
     *
     * <p>Every case above hands the guard a configuration the test itself
     * wrote, which pins the guard's behaviour and nothing else: they would all
     * stay green with the shipped pin deleted, mis-pathed, or set to the very
     * claim ADR-0008 forbids. {@code observerRunsAgainstRealConfigWithoutThrowing}
     * does touch the real config, but under the test profile, which disables the
     * {@code admin} tenant — so it exercises the disabled-branch no-op and
     * asserts nothing about the pin either.
     *
     * <p>The expectation {@code sub} is written out here as a literal, on the
     * authority of ADR-0008 and the sprint that commissioned this case. Reading
     * it from {@code application.properties} and comparing it to itself would
     * pass whatever the file said, and reading it from
     * {@code OidcPrincipalClaimGuard.EXPECTED} would only move the same
     * circularity one file along: the guard is the artifact under test.
     *
     * <p>The file is read from disk rather than the classpath because the test
     * classpath shadows it — {@code src/test/resources/application.properties}
     * comes first, and asserting against that would certify the test profile
     * instead of the shipped one.
     */
    @Test
    void shippedConfigPinsPrincipalClaimToSubForEveryGuardedTenant() {
        Properties shipped = shippedApplicationProperties();

        assertThat(OidcPrincipalClaimGuard.GUARDED_TENANTS)
            .as("a guarded tenant with no pin in the shipped file would make this case vacuous")
            .isNotEmpty();

        for (String tenant : OidcPrincipalClaimGuard.GUARDED_TENANTS) {
            String correctKey = "quarkus.oidc." + tenant + ".token.principal-claim";
            String legacyKey = "quarkus.oidc." + tenant + ".principal-claim";

            assertThat(shipped.getProperty(correctKey))
                .as("%s must be 'sub' in the shipped application.properties: authorship is "
                  + "the Keycloak sub (ADR-0008), and sub-keyed erasure matches on strict "
                  + "equality. Anything else stamps preferred_username as the acting subject.",
                  correctKey)
                .isEqualTo("sub");

            assertThat(shipped.getProperty(legacyKey))
                .as("%s must be absent: Quarkus ignores the un-segmented key for a named "
                  + "tenant, and the guard aborts startup when it is present", legacyKey)
                .isNull();

            assertProfileOverridesAlsoPinSub(shipped, correctKey);
        }
    }

    /**
     * A profile-scoped key (for example {@code %prod.} + the key) wins over the
     * unprofiled one in the profile it names, so a pin that reads correctly at
     * the top of the file can still ship unpinned for the profile that matters.
     */
    private void assertProfileOverridesAlsoPinSub(Properties shipped, String correctKey) {
        for (String key : shipped.stringPropertyNames()) {
            if (key.startsWith("%") && key.endsWith("." + correctKey)) {
                assertThat(shipped.getProperty(key))
                    .as("profile override %s must pin 'sub' as well", key)
                    .isEqualTo("sub");
            }
        }
    }

    /** The shipped file, located from the repository root the way the module is laid out. */
    private Properties shippedApplicationProperties() {
        Path file = RepositoryRoot.find()
            .resolve("backend/server/src/main/resources/application.properties");
        assertThat(file)
            .as("the shipped configuration must be where this case looks for it — "
              + "a moved file would make the case guard nothing")
            .exists();
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(r);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return p;
    }
}
