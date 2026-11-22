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
import io.quarkus.test.QuarkusUnitTest;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A service that embeds the binding without an implementation of the facts
 * interface does not build: the Quarkus build fails on the unsatisfied
 * dependency, before anything runs. The application below is the test
 * service without {@code FixedCallFactsSource}.
 */
class MissingFactsSourceModuleTest {

    @RegisterExtension
    static final QuarkusUnitTest APP = new QuarkusUnitTest()
            .withApplicationRoot(jar -> jar.addPackage(CallFactsAugmentor.class.getPackage()).addClasses(EntryFacade.class, EntryResource.class,
                    TestTokenMechanism.class, TestTokenIdentityProvider.class, TestTokenRequest.class))
            .assertException(MissingFactsSourceModuleTest::the_build_names_the_unsatisfied_facts_interface);

    @Test
    void the_build_fails() {
        Assertions.fail("the application must not start without an implementation of CallFactsSource");
    }

    private static void the_build_names_the_unsatisfied_facts_interface(Throwable failure) {
        List<String> messages = new ArrayList<>();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            messages.add(t.getClass().getName() + ": " + t.getMessage());
            for (Throwable suppressed : t.getSuppressed()) {
                messages.add(suppressed.getClass().getName() + ": " + suppressed.getMessage());
            }
        }
        assertThat(String.join("\n", messages))
                .contains("Unsatisfied dependency")
                .contains("ai.kumbuka.security.contract.CallFactsSource");
    }
}
