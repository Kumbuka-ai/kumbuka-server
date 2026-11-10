// Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
// SPDX-License-Identifier: AGPL-3.0-only
// This file is part of Kumbuka and is licensed under the GNU Affero
// General Public License v3.0 only. See the LICENSE file in the
// repository root for the full licence text.

package ai.kumbuka.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Lifecycle of a declared tag, mirrored from the DB CHECK constraint on
 * {@code tag.status}. There is no third state: a tag is never deleted, only
 * withdrawn, and a withdrawn tag is never declared again.
 */
public enum TagStatus {
    DECLARED("declared"),
    WITHDRAWN("withdrawn");

    private final String dbValue;

    TagStatus(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    public static TagStatus fromDb(String value) {
        return switch (value) {
            case "declared"  -> DECLARED;
            case "withdrawn" -> WITHDRAWN;
            default -> throw new IllegalArgumentException("unknown tag status: " + value);
        };
    }

    @Converter(autoApply = false)
    public static class JpaConverter implements AttributeConverter<TagStatus, String> {
        @Override
        public String convertToDatabaseColumn(TagStatus attribute) {
            return attribute == null ? null : attribute.dbValue();
        }
        @Override
        public TagStatus convertToEntityAttribute(String dbData) {
            return dbData == null ? null : TagStatus.fromDb(dbData);
        }
    }
}
