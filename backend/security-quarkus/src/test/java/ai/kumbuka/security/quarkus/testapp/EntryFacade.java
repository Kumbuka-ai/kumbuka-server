// Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
// SPDX-License-Identifier: AGPL-3.0-only
// This file is part of Kumbuka and is licensed under the GNU Affero
// General Public License v3.0 only. See the LICENSE file in the
// repository root for the full licence text.

package ai.kumbuka.security.quarkus.testapp;

import ai.kumbuka.security.quarkus.CurrentCallFacts;
import ai.kumbuka.security.quarkus.ScopePermission;
import ai.kumbuka.security.quarkus.ScopelessPermission;
import io.quarkus.security.PermissionsAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The facade of the test service. It has the scopes {@code alpha} and
 * {@code beta}; a scope it does not have is answered as any service answers
 * one, with the framework's {@link NotFoundException}. {@link #EXECUTED}
 * counts the calls that reached a method body.
 */
@ApplicationScoped
public class EntryFacade {

    /** The number of calls that reached the body of a method of this facade. */
    public static final AtomicInteger EXECUTED = new AtomicInteger();

    private static final Set<String> SCOPES = Set.of("alpha", "beta");

    @Inject
    CurrentCallFacts call;

    @PermissionsAllowed(value = "test.entry.read", permission = ScopePermission.class, params = "scope")
    public String read(String scope) {
        EXECUTED.incrementAndGet();
        if (!SCOPES.contains(scope)) {
            throw new NotFoundException();
        }
        return "entry of " + scope;
    }

    @PermissionsAllowed(value = "test.entry.list:none", permission = ScopelessPermission.class)
    public String list() {
        EXECUTED.incrementAndGet();
        return "list";
    }

    @PermissionsAllowed(value = "test.tenant.read:tenant", permission = ScopelessPermission.class)
    public String tenant() {
        EXECUTED.incrementAndGet();
        return call.facts().tenant().toString();
    }

    /** Declares a scopeless permission and leaves out what it addresses. */
    @PermissionsAllowed(value = "test.entry.unaddressed", permission = ScopelessPermission.class)
    public String unaddressed() {
        EXECUTED.incrementAndGet();
        return "unaddressed";
    }

    /** Declares nothing. */
    public String undeclared() {
        EXECUTED.incrementAndGet();
        return "undeclared";
    }
}
