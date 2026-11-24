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
 * The binding in a running Quarkus application, without a database: the
 * augmentor, the one check and the producer against the test implementation
 * of the facts interface ({@link FixedCallFactsSource}). The expectations
 * are the order's: a method without a declared permission is refused (the
 * guard reports the same method, {@code FacadeConformanceGuardTest}); an empty scope set is refused at a method that
 * addresses a scope; a missing permission and missing facts are refused; a
 * scopeless method declares the tenant or nothing, and refuses where it
 * declares neither.
 */
class DeclaredPermissionModuleTest {

    @RegisterExtension
    static final QuarkusUnitTest APP = new QuarkusUnitTest()
            .withApplicationRoot(jar -> jar.addPackage(CallFactsAugmentor.class.getPackage()).addClasses(EntryFacade.class, EntryResource.class,
                    FixedCallFactsSource.class, TestTokenMechanism.class, TestTokenIdentityProvider.class,
                    TestTokenRequest.class));

    @Test
    void a_call_holding_the_permission_for_a_scope_in_its_set_is_admitted() {
        given().auth().oauth2("reader").get("/api/alpha/entry")
                .then().statusCode(200).body(equalTo("entry of alpha"));
    }

    @Test
    void a_method_without_a_declared_permission_is_refused() {
        given().auth().oauth2("reader").get("/api/undeclared")
                .then().statusCode(403);
    }

    @Test
    void an_empty_scope_set_is_refused_at_a_method_that_addresses_a_scope() {
        given().auth().oauth2("scopeless").get("/api/alpha/entry")
                .then().statusCode(403);
    }

    @Test
    void an_empty_scope_set_does_not_concern_a_method_that_addresses_no_scope() {
        given().auth().oauth2("scopeless").get("/api/list")
                .then().statusCode(200).body(equalTo("list"));
    }

    @Test
    void a_missing_permission_is_refused() {
        given().auth().oauth2("powerless").get("/api/alpha/entry")
                .then().statusCode(403);
        given().auth().oauth2("powerless").get("/api/list")
                .then().statusCode(403);
    }

    @Test
    void a_call_without_facts_is_refused() {
        given().auth().oauth2("stranger").get("/api/alpha/entry")
                .then().statusCode(403);
        given().auth().oauth2("stranger").get("/api/list")
                .then().statusCode(403);
    }

    @Test
    void a_method_that_declares_the_tenant_is_admitted_and_reads_the_facts_through_the_producer() {
        given().auth().oauth2("reader").get("/api/tenant")
                .then().statusCode(200).body(equalTo("7a1c2b3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"));
    }

    @Test
    void a_scopeless_method_that_declares_neither_tenant_nor_nothing_is_refused() {
        given().auth().oauth2("reader").get("/api/unaddressed")
                .then().statusCode(403);
    }
}
