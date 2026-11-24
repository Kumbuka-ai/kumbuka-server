// Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
// SPDX-License-Identifier: AGPL-3.0-only
// This file is part of Kumbuka and is licensed under the GNU Affero
// General Public License v3.0 only. See the LICENSE file in the
// repository root for the full licence text.

package ai.kumbuka.security.quarkus;

import ai.kumbuka.security.quarkus.testapp.EntryFacade;
import ai.kumbuka.security.quarkus.testapp.EntryResource;
import ai.kumbuka.security.quarkus.testapp.TestTokenIdentityProvider;
import ai.kumbuka.security.quarkus.testapp.TestTokenMechanism;
import ai.kumbuka.security.quarkus.testapp.TestTokenRequest;
import ai.kumbuka.security.quarkus.testapp.ThrowingCallFactsSource;
import io.quarkus.test.QuarkusUnitTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A facts source that fails is no facts source: the call is refused and
 * reaches no declared method. The application below is the test service with
 * {@link ThrowingCallFactsSource} in place of the fixed table; {@code reader}
 * would be admitted to both routes by the fixed table. Neither a scope-addressing
 * nor a scopeless method may answer, and neither may run: the answer is no
 * success, and the facade's counter stays at zero. The status is left open on
 * purpose (observed 2026-11-23: 500, the failure of the authentication step);
 * what this test holds is that a failure of the source never lets a call
 * through.
 *
 * <p>RED STATE, observed 2026-11-23 against a mutation of
 * {@code CallFactsAugmentor} that let a failing source through (the exception
 * caught and a permission checker admitting everything bound instead,
 * reverted before commit): both tests failed on the status, 200, with the
 * bodies "entry of alpha" and "list".
 */
class ThrowingFactsSourceModuleTest {

    @RegisterExtension
    static final QuarkusUnitTest APP = new QuarkusUnitTest()
            .withApplicationRoot(jar -> jar.addPackage(CallFactsAugmentor.class.getPackage()).addClasses(EntryFacade.class, EntryResource.class,
                    ThrowingCallFactsSource.class, TestTokenMechanism.class, TestTokenIdentityProvider.class,
                    TestTokenRequest.class));

    @BeforeEach
    void reset() {
        EntryFacade.EXECUTED.set(0);
    }

    @Test
    void a_scope_addressing_method_is_refused_and_not_executed_when_the_facts_source_fails() {
        Response response = given().auth().oauth2("reader").get("/api/alpha/entry");

        assertRefused(response);
        assertThat(EntryFacade.EXECUTED.get()).as("calls that reached the facade").isZero();
    }

    @Test
    void a_scopeless_method_is_refused_and_not_executed_when_the_facts_source_fails() {
        Response response = given().auth().oauth2("reader").get("/api/list");

        assertRefused(response);
        assertThat(EntryFacade.EXECUTED.get()).as("calls that reached the facade").isZero();
    }

    private static void assertRefused(Response response) {
        assertThat(response.statusCode()).as("status, body: %s", response.asString()).isGreaterThanOrEqualTo(400);
        assertThat(response.asString()).doesNotContain("entry of alpha").isNotEqualTo("list");
    }
}
