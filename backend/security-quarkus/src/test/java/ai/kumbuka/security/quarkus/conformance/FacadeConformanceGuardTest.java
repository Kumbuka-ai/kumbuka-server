// Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
// SPDX-License-Identifier: AGPL-3.0-only
// This file is part of Kumbuka and is licensed under the GNU Affero
// General Public License v3.0 only. See the LICENSE file in the
// repository root for the full licence text.

package ai.kumbuka.security.quarkus.conformance;

import ai.kumbuka.security.quarkus.ScopePermission;
import ai.kumbuka.security.quarkus.ScopelessPermission;
import ai.kumbuka.security.quarkus.testapp.EntryFacade;
import io.quarkus.security.PermissionsAllowed;
import org.junit.jupiter.api.Test;

import java.util.List;

import static ai.kumbuka.security.quarkus.conformance.Offence.Kind.FOREIGN_DECLARATION;
import static ai.kumbuka.security.quarkus.conformance.Offence.Kind.NOT_IN_CATALOGUE;
import static ai.kumbuka.security.quarkus.conformance.Offence.Kind.ROW_WITHOUT_METHOD;
import static ai.kumbuka.security.quarkus.conformance.Offence.Kind.UNDECLARED;
import static ai.kumbuka.security.quarkus.conformance.Offence.Kind.WRONG_PERMISSION;
import static ai.kumbuka.security.quarkus.conformance.Offence.Kind.WRONG_SCOPE_BINDING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

/**
 * The facade conformance guard, held against facades whose deviations are
 * planted on purpose. The catalogues below are written here, by hand; the
 * guard's expectation never comes from a facade.
 *
 * <p>RED STATE: {@link #the_checks_report_each_offence_they_exist_for()} is the
 * guard's red run, kept in the tree and run on every build: each planted
 * deviation of {@link PlantedFacade} must be reported, by method and kind.
 */
class FacadeConformanceGuardTest {

    /** A facade with one deviation planted per method, and one method that conforms. */
    static class PlantedFacade {

        /** Conforms to its row. */
        @PermissionsAllowed(value = "planted.entry.read", permission = ScopePermission.class, params = "scope")
        public String read(String scope) {
            return scope;
        }

        /** Planted: declares nothing. */
        public String undeclared() {
            return "";
        }

        /** Planted: declares another permission than its row. */
        @PermissionsAllowed(value = "planted.entry.write", permission = ScopePermission.class, params = "scope")
        public String update(String scope) {
            return scope;
        }

        /** Planted: the row expects a scope through {@code scope}; the method declares no scope. */
        @PermissionsAllowed(value = "planted.entry.delete:none", permission = ScopelessPermission.class)
        public String delete(String scope) {
            return scope;
        }

        /** Planted: the row expects the scope through {@code scope}; the method binds {@code target}. */
        @PermissionsAllowed(value = "planted.entry.move", permission = ScopePermission.class, params = "target")
        public String move(String scope, String target) {
            return scope + target;
        }

        /** Planted: a scopeless declaration that says neither tenant nor nothing. */
        @PermissionsAllowed(value = "planted.scope.list", permission = ScopelessPermission.class)
        public String list() {
            return "";
        }

        /** Planted: declared through the framework's string permission, which the check never admits. */
        @PermissionsAllowed("planted.tenant.read")
        public String tenant() {
            return "";
        }

        /** Planted: declared, and missing from the catalogue. */
        @PermissionsAllowed(value = "planted.entry.hidden:none", permission = ScopelessPermission.class)
        public String hidden() {
            return "";
        }
    }

    private static final List<CatalogueEntry> PLANTED_CATALOGUE = List.of(
            new CatalogueEntry(PlantedFacade.class, "read", "planted.entry.read", ScopeBinding.scope("scope")),
            new CatalogueEntry(PlantedFacade.class, "undeclared", "planted.entry.undeclared", ScopeBinding.none()),
            new CatalogueEntry(PlantedFacade.class, "update", "planted.entry.update", ScopeBinding.scope("scope")),
            new CatalogueEntry(PlantedFacade.class, "delete", "planted.entry.delete", ScopeBinding.scope("scope")),
            new CatalogueEntry(PlantedFacade.class, "move", "planted.entry.move", ScopeBinding.scope("scope")),
            new CatalogueEntry(PlantedFacade.class, "list", "planted.scope.list", ScopeBinding.none()),
            new CatalogueEntry(PlantedFacade.class, "tenant", "planted.tenant.read", ScopeBinding.tenant()),
            new CatalogueEntry(PlantedFacade.class, "vanished", "planted.entry.vanished", ScopeBinding.none()));

    @Test
    void the_checks_report_each_offence_they_exist_for() {
        List<Offence> offences = FacadeConformanceGuard.offences(List.of(PlantedFacade.class), PLANTED_CATALOGUE);

        assertThat(offences)
                .extracting(Offence::method, Offence::kind)
                .containsExactlyInAnyOrder(
                        tuple("undeclared", UNDECLARED),
                        tuple("update", WRONG_PERMISSION),
                        tuple("delete", WRONG_SCOPE_BINDING),
                        tuple("move", WRONG_SCOPE_BINDING),
                        tuple("list", WRONG_SCOPE_BINDING),
                        tuple("tenant", FOREIGN_DECLARATION),
                        tuple("hidden", NOT_IN_CATALOGUE),
                        tuple("vanished", ROW_WITHOUT_METHOD));
    }

    @Test
    void the_assertion_fails_naming_every_offence() {
        assertThatThrownBy(() -> FacadeConformanceGuard.assertConforms(List.of(PlantedFacade.class), PLANTED_CATALOGUE))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("8 place(s)")
                .hasMessageContaining("PlantedFacade#undeclared: UNDECLARED")
                .hasMessageContaining("PlantedFacade#vanished: ROW_WITHOUT_METHOD");
    }

    /** A facade that conforms to its catalogue in every method. */
    static class ConformingFacade {

        @PermissionsAllowed(value = "conforming.entry.read", permission = ScopePermission.class, params = "scope")
        public String read(String scope, String key) {
            return scope + key;
        }

        @PermissionsAllowed(value = "conforming.tenant.read:tenant", permission = ScopelessPermission.class)
        public String tenant() {
            return "";
        }

        @PermissionsAllowed(value = "conforming.scope.list:none", permission = ScopelessPermission.class)
        public String list() {
            return "";
        }

        /** Not a facade method: static. */
        public static String helper() {
            return "";
        }
    }

    @Test
    void a_facade_that_matches_its_catalogue_has_no_offence() {
        List<CatalogueEntry> catalogue = List.of(
                new CatalogueEntry(ConformingFacade.class, "read", "conforming.entry.read", ScopeBinding.scope("scope")),
                new CatalogueEntry(ConformingFacade.class, "tenant", "conforming.tenant.read", ScopeBinding.tenant()),
                new CatalogueEntry(ConformingFacade.class, "list", "conforming.scope.list", ScopeBinding.none()));

        assertThat(FacadeConformanceGuard.offences(List.of(ConformingFacade.class), catalogue)).isEmpty();
        assertThatCode(() -> FacadeConformanceGuard.assertConforms(List.of(ConformingFacade.class), catalogue))
                .doesNotThrowAnyException();
    }

    /**
     * The facade the running test service refuses a call to
     * ({@code DeclaredPermissionModuleTest}): the guard reports the same method
     * without a declaration, and the scopeless method that declares no address.
     */
    @Test
    void the_guard_reports_the_methods_the_running_service_refuses_for_their_declaration() {
        List<CatalogueEntry> catalogue = List.of(
                new CatalogueEntry(EntryFacade.class, "read", "test.entry.read", ScopeBinding.scope("scope")),
                new CatalogueEntry(EntryFacade.class, "list", "test.entry.list", ScopeBinding.none()),
                new CatalogueEntry(EntryFacade.class, "tenant", "test.tenant.read", ScopeBinding.tenant()),
                new CatalogueEntry(EntryFacade.class, "unaddressed", "test.entry.unaddressed", ScopeBinding.none()),
                new CatalogueEntry(EntryFacade.class, "undeclared", "test.entry.undeclared", ScopeBinding.none()));

        assertThat(FacadeConformanceGuard.offences(List.of(EntryFacade.class), catalogue))
                .extracting(Offence::method, Offence::kind)
                .containsExactlyInAnyOrder(
                        tuple("undeclared", UNDECLARED),
                        tuple("unaddressed", WRONG_SCOPE_BINDING));
    }
}
