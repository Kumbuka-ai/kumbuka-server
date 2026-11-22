// Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
// SPDX-License-Identifier: AGPL-3.0-only
// This file is part of Kumbuka and is licensed under the GNU Affero
// General Public License v3.0 only. See the LICENSE file in the
// repository root for the full licence text.

package ai.kumbuka.security.quarkus;

import ai.kumbuka.security.quarkus.testapp.EntryFacade;
import ai.kumbuka.security.quarkus.testapp.EntryResource;
import ai.kumbuka.security.quarkus.testapp.FixedCallFactsSource;
import ai.kumbuka.security.quarkus.testapp.TestTokenIdentityProvider;
import ai.kumbuka.security.quarkus.testapp.TestTokenMechanism;
import ai.kumbuka.security.quarkus.testapp.TestTokenRequest;
import io.quarkus.test.QuarkusUnitTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * Health and metrics answer without a token; every other path refuses a call
 * without one, including a path that reaches no facade method and a path that
 * only begins like a health path.
 */
class TokenFreePathsModuleTest {

    @RegisterExtension
    static final QuarkusUnitTest APP = new QuarkusUnitTest()
            .withApplicationRoot(jar -> jar.addPackage(CallFactsAugmentor.class.getPackage()).addClasses(EntryFacade.class, EntryResource.class,
                    FixedCallFactsSource.class, TestTokenMechanism.class, TestTokenIdentityProvider.class,
                    TestTokenRequest.class));

    @Test
    void a_path_outside_the_list_is_refused_without_a_token() {
        given().get("/api/open").then().statusCode(401);
        given().get("/api/alpha/entry").then().statusCode(401);
        given().get("/q/healthz").then().statusCode(401);
    }

    @Test
    void the_same_path_answers_with_a_token() {
        given().auth().oauth2("reader").get("/api/open").then().statusCode(200).body(equalTo("open"));
    }

    @Test
    void health_answers_without_a_token() {
        given().get("/q/health").then().statusCode(200);
        given().get("/q/health/live").then().statusCode(200);
        given().get("/q/health/ready").then().statusCode(200);
    }

    @Test
    void metrics_answer_without_a_token() {
        given().get("/q/metrics").then().statusCode(200);
    }
}
