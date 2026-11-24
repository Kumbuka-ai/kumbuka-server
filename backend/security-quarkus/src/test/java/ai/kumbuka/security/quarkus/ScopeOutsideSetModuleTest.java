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
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A scope outside the set is answered as a scope that does not exist. The
 * caller {@code reader} may address {@code alpha} and {@code ghost}; the test
 * service has {@code alpha} and {@code beta}. So {@code beta} exists and lies
 * outside the set (the check refuses it), and {@code ghost} lies inside the
 * set and does not exist (the service itself answers it). The two answers must
 * not differ in anything a caller can read, and the reason of the first stands
 * in one log line with permission, scope slug and reason, and nowhere else.
 */
class ScopeOutsideSetModuleTest {

    @RegisterExtension
    static final QuarkusUnitTest APP = new QuarkusUnitTest()
            .withApplicationRoot(jar -> jar.addPackage(CallFactsAugmentor.class.getPackage()).addClasses(EntryFacade.class,
                    EntryResource.class, FixedCallFactsSource.class, TestTokenMechanism.class,
                    TestTokenIdentityProvider.class, TestTokenRequest.class));

    @Test
    void a_scope_outside_the_set_is_answered_as_a_scope_that_does_not_exist_and_logged_once() {
        Capture capture = new Capture();
        Logger logger = Logger.getLogger("ai.kumbuka.security");
        logger.addHandler(capture);
        Response outside;
        Response absent;
        try {
            outside = given().auth().oauth2("reader").get("/api/beta/entry");
            absent = given().auth().oauth2("reader").get("/api/ghost/entry");
        } finally {
            logger.removeHandler(capture);
        }

        assertThat(absent.statusCode()).as("the service's own answer to a scope it does not have").isEqualTo(404);
        assertThat(outside.statusCode()).isEqualTo(absent.statusCode());
        assertThat(outside.asString()).isEqualTo(absent.asString());
        assertThat(outside.contentType()).isEqualTo(absent.contentType());
        assertThat(headers(outside)).containsExactlyInAnyOrderElementsOf(headers(absent));

        List<String> lines = capture.records.stream()
                .filter(r -> r.getLevel().intValue() >= Level.INFO.intValue())
                .map(ScopeOutsideSetModuleTest::message)
                .toList();
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0))
                .contains("permission test.entry.read")
                .contains("scope slug beta")
                .contains("reason scope-outside-set")
                .doesNotContain("reader");
    }

    private static List<String> headers(Response response) {
        return response.headers().asList().stream().map(h -> h.getName() + ": " + h.getValue()).toList();
    }

    private static String message(LogRecord record) {
        Object[] parameters = record.getParameters();
        return parameters == null || parameters.length == 0
                ? record.getMessage()
                : String.format(record.getMessage(), parameters);
    }

    private static final class Capture extends Handler {

        private final List<LogRecord> records = new CopyOnWriteArrayList<>();

        @Override
        public void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
            // nothing buffered
        }

        @Override
        public void close() {
            // nothing held
        }
    }
}
