// Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
// SPDX-License-Identifier: AGPL-3.0-only
// This file is part of Kumbuka and is licensed under the GNU Affero
// General Public License v3.0 only. See the LICENSE file in the
// repository root for the full licence text.

package ai.kumbuka.security.quarkus.testapp;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;

/** The REST adapter of the test service: each route calls one facade method, or none. */
@Path("/api")
public class EntryResource {

    @Inject
    EntryFacade facade;

    @GET
    @Path("{scope}/entry")
    public String read(@PathParam("scope") String scope) {
        return facade.read(scope);
    }

    @GET
    @Path("list")
    public String list() {
        return facade.list();
    }

    @GET
    @Path("tenant")
    public String tenant() {
        return facade.tenant();
    }

    @GET
    @Path("unaddressed")
    public String unaddressed() {
        return facade.unaddressed();
    }

    @GET
    @Path("undeclared")
    public String undeclared() {
        return facade.undeclared();
    }

    /** Reaches no facade method; only the token decides. */
    @GET
    @Path("open")
    public String open() {
        return "open";
    }
}
