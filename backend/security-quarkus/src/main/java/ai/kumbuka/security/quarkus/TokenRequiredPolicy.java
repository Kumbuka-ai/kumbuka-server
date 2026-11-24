// Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
// SPDX-License-Identifier: AGPL-3.0-only
// This file is part of Kumbuka and is licensed under the GNU Affero
// General Public License v3.0 only. See the LICENSE file in the
// repository root for the full licence text.

package ai.kumbuka.security.quarkus;

import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.vertx.http.runtime.security.HttpSecurityPolicy;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Refuses every anonymous call to a path that is not on
 * {@link TokenFreePaths}; the framework answers the refusal with its
 * authentication challenge. An authenticated call passes this policy and
 * meets the declared permission of the method it reaches.
 *
 * <p>The policy carries no name, so Quarkus applies it to every request.
 */
@ApplicationScoped
public class TokenRequiredPolicy implements HttpSecurityPolicy {

    @Override
    public Uni<CheckResult> checkPermission(RoutingContext request, Uni<SecurityIdentity> identity,
            AuthorizationRequestContext requestContext) {
        if (TokenFreePaths.admits(request.normalizedPath())) {
            return Uni.createFrom().item(CheckResult.PERMIT);
        }
        return identity.map(id -> id == null || id.isAnonymous() ? CheckResult.DENY : CheckResult.PERMIT);
    }
}
