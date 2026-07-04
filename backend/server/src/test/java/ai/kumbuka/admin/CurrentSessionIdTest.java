package ai.kumbuka.admin;

import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.test.junit.QuarkusTest;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.Test;

import java.security.Principal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Test for {@link CurrentSessionId} (F-0082) — the bean is normally
 * {@code @InjectMock}ed in {@link SessionsResourceTest}, so its own branch logic
 * is exercised here directly: the {@code sid} claim is read from a JWT principal,
 * and every non-JWT / missing-claim path returns null (marking no session as
 * current rather than guessing).
 *
 * <p>{@code @QuarkusTest} so the quarkus-jacoco extension records the coverage —
 * the module's Sonar report reads {@code jacoco-quarkus.exec} only (see the pom
 * jacoco comment), so a plain-JUnit test would not count.
 */
@QuarkusTest
class CurrentSessionIdTest {

    private static CurrentSessionId withPrincipal(Principal p) {
        SecurityIdentity identity = mock(SecurityIdentity.class);
        when(identity.getPrincipal()).thenReturn(p);
        CurrentSessionId bean = new CurrentSessionId();
        bean.identity = identity;
        return bean;
    }

    @Test
    void returnsSidClaimFromJwtPrincipal() {
        JsonWebToken jwt = mock(JsonWebToken.class);
        when(jwt.getClaim("sid")).thenReturn("sess-123");
        assertThat(withPrincipal(jwt).get()).isEqualTo("sess-123");
    }

    @Test
    void returnsNullWhenSidClaimAbsent() {
        JsonWebToken jwt = mock(JsonWebToken.class);
        when(jwt.getClaim("sid")).thenReturn(null);
        assertThat(withPrincipal(jwt).get()).isNull();
    }

    @Test
    void returnsNullWhenPrincipalIsNotJwt() {
        // A plain (non-JWT) principal — e.g. a @TestSecurity identity — must not
        // be treated as carrying a session id.
        assertThat(withPrincipal(mock(Principal.class)).get()).isNull();
    }
}
