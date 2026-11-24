// Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
// SPDX-License-Identifier: AGPL-3.0-only
// This file is part of Kumbuka and is licensed under the GNU Affero
// General Public License v3.0 only. See the LICENSE file in the
// repository root for the full licence text.

package ai.kumbuka.security.quarkus;

import ai.kumbuka.security.contract.CallFacts;
import ai.kumbuka.security.contract.CallFactsSource;
import ai.kumbuka.security.contract.PresentedCredential;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.SecurityIdentityAugmentor;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.util.Map;
import java.util.Optional;

/**
 * Fetches the facts of every authenticated call from the service's
 * {@link CallFactsSource}, attaches them to the call's identity under
 * {@link #FACTS_ATTRIBUTE}, and binds {@link DeclaredPermissionCheck} to the
 * identity as its permission checker.
 *
 * <p>The binding ships no {@link CallFactsSource}. A service that embeds the
 * binding without one does not build: the injection point below stays
 * unsatisfied, and the Quarkus build refuses it.
 *
 * <p>The source is called off the event loop, so it may read a database. If
 * it fails, the authentication of the call fails; if it finds no facts, the
 * check is bound to the empty facts and refuses every declared method.
 */
@ApplicationScoped
public class CallFactsAugmentor implements SecurityIdentityAugmentor {

    /** The identity attribute that carries the {@link CallFacts} of the call. */
    public static final String FACTS_ATTRIBUTE = "ai.kumbuka.security.call-facts";

    private final CallFactsSource source;

    public CallFactsAugmentor(CallFactsSource source) {
        this.source = source;
    }

    @Override
    public Uni<SecurityIdentity> augment(SecurityIdentity identity, AuthenticationRequestContext context) {
        if (identity.isAnonymous()) {
            return Uni.createFrom().item(identity);
        }
        return context.runBlocking(() -> withFacts(identity));
    }

    private SecurityIdentity withFacts(SecurityIdentity identity) {
        Optional<CallFacts> facts = source.factsOf(presented(identity));
        QuarkusSecurityIdentity.Builder builder = QuarkusSecurityIdentity.builder(identity);
        facts.ifPresent(f -> builder.addAttribute(FACTS_ATTRIBUTE, f));
        builder.addPermissionChecker(DeclaredPermissionCheck.checkerFor(facts));
        return builder.build();
    }

    private static PresentedCredential presented(SecurityIdentity identity) {
        Map<String, Object> claims = identity.getPrincipal() instanceof JsonWebToken token
                ? ClaimValues.of(token)
                : Map.of();
        return new PresentedCredential(identity.getPrincipal().getName(), claims);
    }
}
