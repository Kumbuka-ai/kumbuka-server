// Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
// SPDX-License-Identifier: AGPL-3.0-only
// This file is part of Kumbuka and is licensed under the GNU Affero
// General Public License v3.0 only. See the LICENSE file in the
// repository root for the full licence text.

package ai.kumbuka.security.contract;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The facts of one call, against which a service checks the permission every
 * method declares and the scope it addresses.
 *
 * <p>The fields are those an edition's resolver delivers for a call, with one
 * deliberate difference: the resolver names at most one scope, while
 * {@link #scopes()} is the whole set of scopes the call may address.
 *
 * <p>The constructor refuses facts it cannot vouch for: a blank subject, an
 * actor that is present and blank, a permission outside the form
 * {@code service.object.act}, a blank channel, and any missing member. The
 * sets are copied, so the facts cannot change after the check has read them.
 *
 * @param subject        the subject the call is made for
 * @param actor          the delegated identity acting for the subject; empty
 *                       on a call without an actor
 * @param roleAnchors    the role anchors of the subject
 * @param permissions    the permissions the call holds, each of the form
 *                       {@code service.object.act}
 * @param scopes         the scopes the call may address
 * @param tenant         the tenant the call belongs to
 * @param channel        the channel the call came in through
 * @param authentication how and when the caller authenticated
 */
public record CallFacts(
        String subject,
        Optional<String> actor,
        Set<String> roleAnchors,
        Set<String> permissions,
        Set<ScopeGrant> scopes,
        UUID tenant,
        String channel,
        AuthenticationContext authentication) {

    /**
     * Three segments of lowercase words joined by a hyphen, separated by dots:
     * service, object, act.
     */
    private static final Pattern PERMISSION_FORM =
            Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*(\\.[a-z0-9]+(-[a-z0-9]+)*){2}");

    public CallFacts {
        requireNotBlank(subject, "subject");
        Objects.requireNonNull(actor, "the facts carry an actor or an empty one, never none");
        actor.ifPresent(a -> requireNotBlank(a, "actor"));
        roleAnchors = Set.copyOf(Objects.requireNonNull(roleAnchors, "the facts carry role anchors"));
        permissions = Set.copyOf(Objects.requireNonNull(permissions, "the facts carry permissions"));
        for (String permission : permissions) {
            if (!PERMISSION_FORM.matcher(permission).matches()) {
                throw new IllegalArgumentException(
                        "a permission has the form service.object.act: " + permission);
            }
        }
        scopes = Set.copyOf(Objects.requireNonNull(scopes, "the facts carry a scope set"));
        Objects.requireNonNull(tenant, "the facts carry a tenant");
        requireNotBlank(channel, "channel");
        Objects.requireNonNull(authentication, "the facts carry an authentication context");
    }

    private static void requireNotBlank(String value, String member) {
        Objects.requireNonNull(value, "the facts carry a " + member);
        if (value.isBlank()) {
            throw new IllegalArgumentException("the facts carry a " + member + " that is not blank");
        }
    }
}
