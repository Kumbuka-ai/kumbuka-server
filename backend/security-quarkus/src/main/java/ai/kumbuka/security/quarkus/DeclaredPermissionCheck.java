// Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
// SPDX-License-Identifier: AGPL-3.0-only
// This file is part of Kumbuka and is licensed under the GNU Affero
// General Public License v3.0 only. See the LICENSE file in the
// repository root for the full licence text.

package ai.kumbuka.security.quarkus;

import ai.kumbuka.security.contract.CallFacts;
import ai.kumbuka.security.contract.Refusal;
import ai.kumbuka.security.contract.ScopeGrant;
import io.smallrye.mutiny.Uni;
import jakarta.ws.rs.NotFoundException;

import java.security.Permission;
import java.util.Optional;
import java.util.function.Function;

/**
 * THE CHECK. Every facade method declares its permission through
 * {@code @PermissionsAllowed} with a {@link DeclaredPermission}; Quarkus asks
 * the identity of the call to check it, and the identity asks this class,
 * which {@link CallFactsAugmentor} bound to it. There is no second place that
 * decides a declared permission.
 *
 * <p>The order of the decision:
 * <ol>
 *   <li>no facts: refused;</li>
 *   <li>a permission that is no {@link DeclaredPermission}: refused, the
 *       method does not declare what it addresses;</li>
 *   <li>the declared permission is not among the facts' permissions: refused;</li>
 *   <li>a method addressing a scope: refused when the scope set is empty, when
 *       the call names no scope, and when the scope it names is not in the
 *       set; a scopeless method that declares neither the tenant nor nothing:
 *       refused;</li>
 *   <li>otherwise admitted.</li>
 * </ol>
 *
 * <p>How a refusal is answered: a scope outside the set is answered exactly as
 * a scope that does not exist, by the framework's {@link NotFoundException},
 * which a service answers as its own {@code NOT_FOUND}; its reason goes to one
 * log line and nowhere else. Every other refusal is answered as a refused
 * permission. A failure inside the check fails the call.
 */
public final class DeclaredPermissionCheck {

    private DeclaredPermissionCheck() {
    }

    /** Decides one declared permission against the facts of the call. */
    public static Decision decide(Optional<CallFacts> facts, Permission requested) {
        String permission = requested.getName();
        if (facts.isEmpty()) {
            return refused(new Refusal.NoFacts(permission));
        }
        if (!(requested instanceof DeclaredPermission declared)) {
            return refused(new Refusal.AddressUndeclared(permission));
        }
        CallFacts held = facts.get();
        if (!held.permissions().contains(permission)) {
            return refused(new Refusal.PermissionMissing(permission));
        }
        return switch (declared) {
            case ScopePermission scoped -> decideScope(held, scoped);
            case ScopelessPermission scopeless -> scopeless.address().isPresent()
                    ? new Decision.Admitted()
                    : refused(new Refusal.AddressUndeclared(permission));
        };
    }

    private static Decision decideScope(CallFacts held, ScopePermission scoped) {
        String permission = scoped.getName();
        if (held.scopes().isEmpty()) {
            return refused(new Refusal.ScopeSetEmpty(permission));
        }
        Optional<String> addressed = scoped.scope();
        if (addressed.isEmpty()) {
            return refused(new Refusal.ScopeAbsent(permission));
        }
        String slug = addressed.get();
        boolean inSet = held.scopes().stream().map(ScopeGrant::slug).anyMatch(slug::equals);
        return inSet ? new Decision.Admitted() : refused(new Refusal.ScopeOutsideSet(permission, slug));
    }

    private static Decision refused(Refusal refusal) {
        return new Decision.Refused(refusal);
    }

    /**
     * The permission checker {@link CallFactsAugmentor} attaches to the
     * identity of a call: {@code true} admits, {@code false} refuses the
     * permission, a failure with {@link NotFoundException} answers a scope
     * outside the set as a scope that does not exist.
     */
    static Function<Permission, Uni<Boolean>> checkerFor(Optional<CallFacts> facts) {
        return requested -> Uni.createFrom().item(() -> answer(decide(facts, requested)));
    }

    private static boolean answer(Decision decision) {
        return switch (decision) {
            case Decision.Admitted admitted -> true;
            case Decision.Refused refused -> refuse(refused.refusal());
        };
    }

    private static boolean refuse(Refusal refusal) {
        return switch (refusal) {
            case Refusal.ScopeOutsideSet outside -> {
                SecurityLog.scopeOutsideSet(outside);
                throw new NotFoundException();
            }
            case Refusal.NoFacts r -> SecurityLog.refused(r);
            case Refusal.PermissionMissing r -> SecurityLog.refused(r);
            case Refusal.ScopeSetEmpty r -> SecurityLog.refused(r);
            case Refusal.ScopeAbsent r -> SecurityLog.refused(r);
            case Refusal.AddressUndeclared r -> SecurityLog.refused(r);
        };
    }
}
