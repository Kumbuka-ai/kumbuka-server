// Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
// SPDX-License-Identifier: AGPL-3.0-only
// This file is part of Kumbuka and is licensed under the GNU Affero
// General Public License v3.0 only. See the LICENSE file in the
// repository root for the full licence text.

package ai.kumbuka.security.quarkus.conformance;

import ai.kumbuka.security.quarkus.ScopePermission;
import ai.kumbuka.security.quarkus.ScopelessPermission;
import io.quarkus.security.PermissionsAllowed;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The facade conformance guard a service runs in its tests. It is handed the
 * service's facade classes and its catalogue, both written by the service, and
 * reports:
 *
 * <ul>
 *   <li>every public facade method that declares no permission;</li>
 *   <li>every public facade method without a catalogue row;</li>
 *   <li>every declaration that is not exactly one declared permission of the
 *       binding ({@link ScopePermission} or {@link ScopelessPermission});</li>
 *   <li>every method whose permission differs from its row;</li>
 *   <li>every method whose scope binding is missing or differs from its row:
 *       a scope argument named in {@code params} that is not the row's, or
 *       that the method has no {@code String} parameter for, and a scopeless
 *       declaration without {@code :tenant} or {@code :none};</li>
 *   <li>every catalogue row without a method.</li>
 * </ul>
 *
 * <p>The expectation is the catalogue and only the catalogue: nothing a facade
 * declares becomes a row. Use in a service's test:
 *
 * <pre>{@code
 * FacadeConformanceGuard.assertConforms(List.of(VerbSurface.class), CATALOGUE);
 * }</pre>
 *
 * <p>Methods are matched by name: every overload of a method answers to the
 * same row. Static, synthetic and bridge methods are not facade methods.
 */
public final class FacadeConformanceGuard {

    private FacadeConformanceGuard() {
    }

    /** Fails with every offence, one per line, or returns where there is none. */
    public static void assertConforms(Collection<Class<?>> facades, Collection<CatalogueEntry> catalogue) {
        List<Offence> offences = offences(facades, catalogue);
        if (!offences.isEmpty()) {
            throw new AssertionError("the facades deviate from the catalogue in " + offences.size()
                    + " place(s):\n" + offences.stream().map(Offence::toString).collect(Collectors.joining("\n")));
        }
    }

    /** Every offence of the facades against the catalogue; empty where they conform. */
    public static List<Offence> offences(Collection<Class<?>> facades, Collection<CatalogueEntry> catalogue) {
        List<Offence> offences = new ArrayList<>();
        Set<String> found = new LinkedHashSet<>();
        for (Class<?> facade : facades) {
            for (Method method : facadeMethods(facade)) {
                found.add(key(facade, method.getName()));
                Optional<CatalogueEntry> row = rowOf(catalogue, facade, method.getName());
                offences.addAll(checkMethod(facade, method, row));
            }
        }
        for (CatalogueEntry row : catalogue) {
            if (!found.contains(key(row.facade(), row.method()))) {
                offences.add(new Offence(row.facade(), row.method(), Offence.Kind.ROW_WITHOUT_METHOD,
                        "the catalogue expects " + row.permission() + " here, and no public facade method has this name"));
            }
        }
        return List.copyOf(offences);
    }

    private static List<Method> facadeMethods(Class<?> facade) {
        return Arrays.stream(facade.getDeclaredMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers()))
                .filter(m -> !Modifier.isStatic(m.getModifiers()))
                .filter(m -> !m.isSynthetic() && !m.isBridge())
                .sorted(Comparator.comparing(Method::getName).thenComparing(Method::toGenericString))
                .toList();
    }

    private static Optional<CatalogueEntry> rowOf(Collection<CatalogueEntry> catalogue, Class<?> facade, String method) {
        return catalogue.stream()
                .filter(r -> r.facade().equals(facade) && r.method().equals(method))
                .findFirst();
    }

    private static List<Offence> checkMethod(Class<?> facade, Method method, Optional<CatalogueEntry> row) {
        List<Offence> offences = new ArrayList<>();
        String name = method.getName();
        PermissionsAllowed[] declarations = method.getAnnotationsByType(PermissionsAllowed.class);
        if (declarations.length == 0) {
            offences.add(new Offence(facade, name, Offence.Kind.UNDECLARED,
                    "declares no permission; expected "
                            + row.map(r -> r.permission() + " addressing " + describe(r.binding()))
                                    .orElse("a catalogue row")));
        }
        if (row.isEmpty()) {
            offences.add(new Offence(facade, name, Offence.Kind.NOT_IN_CATALOGUE,
                    "is a public facade method without a catalogue row"));
            return offences;
        }
        if (declarations.length == 0) {
            return offences;
        }
        CatalogueEntry expected = row.get();
        if (declarations.length > 1 || declarations[0].value().length != 1) {
            offences.add(new Offence(facade, name, Offence.Kind.FOREIGN_DECLARATION,
                    "declares more than one permission; expected exactly " + expected.permission()));
            return offences;
        }
        PermissionsAllowed declaration = declarations[0];
        Class<?> permissionClass = declaration.permission();
        if (permissionClass != ScopePermission.class && permissionClass != ScopelessPermission.class) {
            offences.add(new Offence(facade, name, Offence.Kind.FOREIGN_DECLARATION,
                    "declares through " + permissionClass.getSimpleName()
                            + "; expected ScopePermission or ScopelessPermission"));
            return offences;
        }
        String[] declared = declaration.value()[0].split(":", -1);
        if (!declared[0].equals(expected.permission())) {
            offences.add(new Offence(facade, name, Offence.Kind.WRONG_PERMISSION,
                    "declares " + declared[0] + "; expected " + expected.permission()));
        }
        Optional<ScopeBinding> binding = declaredBinding(method, declaration, declared);
        if (binding.isEmpty() || !binding.get().equals(expected.binding())) {
            offences.add(new Offence(facade, name, Offence.Kind.WRONG_SCOPE_BINDING,
                    "declares " + binding.map(FacadeConformanceGuard::describe).orElse("no scope binding")
                            + "; expected " + describe(expected.binding())));
        }
        return offences;
    }

    /** What the declaration binds; empty where it binds nothing the check could decide. */
    private static Optional<ScopeBinding> declaredBinding(Method method, PermissionsAllowed declaration,
            String[] declared) {
        if (declaration.permission() == ScopePermission.class) {
            String[] params = declaration.params();
            if (declared.length != 1 || params.length != 1 || PermissionsAllowed.AUTODETECTED.equals(params[0])
                    || !hasStringParameter(method, params[0])) {
                return Optional.empty();
            }
            return Optional.of(ScopeBinding.scope(params[0]));
        }
        if (declared.length != 2) {
            return Optional.empty();
        }
        return switch (declared[1]) {
            case "tenant" -> Optional.of(ScopeBinding.tenant());
            case "none" -> Optional.of(ScopeBinding.none());
            default -> Optional.empty();
        };
    }

    /**
     * Whether the method has a {@code String} parameter of this name. A method
     * compiled without parameter names has none the guard can see, and fails.
     */
    private static boolean hasStringParameter(Method method, String name) {
        for (Parameter parameter : method.getParameters()) {
            if (parameter.isNamePresent() && parameter.getName().equals(name)
                    && parameter.getType() == String.class) {
                return true;
            }
        }
        return false;
    }

    private static String key(Class<?> facade, String method) {
        return facade.getName() + "#" + method;
    }

    private static String describe(ScopeBinding binding) {
        return switch (binding) {
            case ScopeBinding.Scope scope -> "scope through argument " + scope.argument();
            case ScopeBinding.Tenant tenant -> "tenant";
            case ScopeBinding.None none -> "nothing";
        };
    }
}
