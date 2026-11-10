// Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
// SPDX-License-Identifier: AGPL-3.0-only
// This file is part of Kumbuka and is licensed under the GNU Affero
// General Public License v3.0 only. See the LICENSE file in the
// repository root for the full licence text.

package ai.kumbuka.domain;

import ai.kumbuka.tenancy.StringUuidConverter;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.TenantId;

import java.time.Instant;
import java.util.UUID;

/**
 * One token of a scope's tag vocabulary. Declared before use, withdrawn but
 * never deleted; {@code (scope_id, token)} is unique across both states, so a
 * withdrawn token stays taken.
 *
 * <p>The services never read this table — they read {@code platform.tag_access}.
 */
@Entity
@Table(name = "tag")
public class Tag extends PanacheEntityBase {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    public UUID id;

    /** Tenant axis — auto-populated by Hibernate (ADR-0011). */
    @Convert(converter = StringUuidConverter.class)
    @TenantId
    @Column(name = "tenant_id", nullable = false)
    public String tenantId;

    @Column(name = "scope_id", nullable = false)
    public UUID scopeId;

    /** {@code ^[a-z0-9][a-z0-9:-]{0,62}$}; a facet is a prefix, e.g. {@code component:api}. */
    @Column(nullable = false)
    public String token;

    /** At most 500 characters; optional. */
    @Column
    public String description;

    @Column(nullable = false)
    @Convert(converter = TagStatus.JpaConverter.class)
    public TagStatus status = TagStatus.DECLARED;

    /** Subject of the caller that declared the tag — who did it, never how often. */
    @Column(name = "created_by", nullable = false)
    public String createdBy;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    public Instant createdAt;

    @Column(name = "withdrawn_at")
    public Instant withdrawnAt;
}
