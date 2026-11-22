// Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
// SPDX-License-Identifier: AGPL-3.0-only
// This file is part of Kumbuka and is licensed under the GNU Affero
// General Public License v3.0 only. See the LICENSE file in the
// repository root for the full licence text.

package ai.kumbuka.security.quarkus.testapp;

import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.IdentityProvider;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

/** Turns the test's token into an identity carrying the subject as principal. */
@ApplicationScoped
public class TestTokenIdentityProvider implements IdentityProvider<TestTokenRequest> {

    @Override
    public Class<TestTokenRequest> getRequestType() {
        return TestTokenRequest.class;
    }

    @Override
    public Uni<SecurityIdentity> authenticate(TestTokenRequest request, AuthenticationRequestContext context) {
        return Uni.createFrom().item(QuarkusSecurityIdentity.builder()
                .setPrincipal(new QuarkusPrincipal(request.subject()))
                .build());
    }
}
