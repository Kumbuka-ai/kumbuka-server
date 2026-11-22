// Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
// SPDX-License-Identifier: AGPL-3.0-only
// This file is part of Kumbuka and is licensed under the GNU Affero
// General Public License v3.0 only. See the LICENSE file in the
// repository root for the full licence text.

package ai.kumbuka.security.quarkus.testapp;

import io.quarkus.security.identity.request.BaseAuthenticationRequest;

/** The test's stand-in for a verified token: the subject it names. */
public class TestTokenRequest extends BaseAuthenticationRequest {

    private final String subject;

    public TestTokenRequest(String subject) {
        this.subject = subject;
    }

    public String subject() {
        return subject;
    }
}
