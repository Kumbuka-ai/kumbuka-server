// Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
// SPDX-License-Identifier: AGPL-3.0-only
// This file is part of Kumbuka and is licensed under the GNU Affero
// General Public License v3.0 only. See the LICENSE file in the
// repository root for the full licence text.

package ai.kumbuka.security.quarkus;

import jakarta.json.JsonArray;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;
import org.eclipse.microprofile.jwt.JsonWebToken;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Turns the claims of a verified token into the plain Java values the
 * contract's {@code PresentedCredential} carries, so that no JSON or token
 * type reaches an implementation of the facts interface.
 */
final class ClaimValues {

    /** The raw token is no claim of the caller; it never leaves the binding. */
    private static final String RAW_TOKEN = "raw_token";

    private ClaimValues() {
    }

    static Map<String, Object> of(JsonWebToken token) {
        Map<String, Object> claims = new LinkedHashMap<>();
        Collection<String> names = token.getClaimNames();
        if (names == null) {
            return claims;
        }
        for (String name : names) {
            if (RAW_TOKEN.equals(name)) {
                continue;
            }
            plain(token.getClaim(name)).ifPresent(value -> claims.put(name, value));
        }
        return claims;
    }

    /** The plain value of one claim value; empty for a JSON null or an absent value. */
    static Optional<Object> plain(Object value) {
        return switch (value) {
            case null -> Optional.empty();
            case JsonString s -> Optional.of(s.getString());
            case JsonNumber n -> Optional.of(number(n.bigDecimalValue()));
            case JsonObject o -> Optional.of(plainMap(o));
            case JsonArray a -> Optional.of(plainList(a));
            case JsonValue v when v.getValueType() == JsonValue.ValueType.TRUE -> Optional.of(Boolean.TRUE);
            case JsonValue v when v.getValueType() == JsonValue.ValueType.FALSE -> Optional.of(Boolean.FALSE);
            case JsonValue v -> Optional.empty();
            case String s -> Optional.of(s);
            case Boolean b -> Optional.of(b);
            case Integer i -> Optional.of(i.longValue());
            case Long l -> Optional.of(l);
            case BigDecimal d -> Optional.of(number(d));
            case Number n -> Optional.of(number(new BigDecimal(n.toString())));
            case Map<?, ?> m -> Optional.of(plainMap(m));
            case Collection<?> c -> Optional.of(plainList(c));
            default -> Optional.of(value.toString());
        };
    }

    /** A {@code Long} where the number is a whole number that fits, a {@code BigDecimal} otherwise. */
    private static Object number(BigDecimal value) {
        try {
            return value.longValueExact();
        } catch (ArithmeticException notAWholeLong) {
            return value;
        }
    }

    private static Map<String, Object> plainMap(Map<?, ?> map) {
        Map<String, Object> out = new LinkedHashMap<>();
        map.forEach((k, v) -> plain(v).ifPresent(p -> out.put(String.valueOf(k), p)));
        return Map.copyOf(out);
    }

    private static List<Object> plainList(Collection<?> values) {
        List<Object> out = new ArrayList<>();
        values.forEach(v -> plain(v).ifPresent(out::add));
        return List.copyOf(out);
    }
}
