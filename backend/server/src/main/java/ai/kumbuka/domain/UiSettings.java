package ai.kumbuka.domain;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.io.IOException;
import java.util.Objects;

/**
 * Per-user UI presentation settings — the typed shape of the
 * {@code user_account.settings} jsonb column (one column, one typed field).
 *
 * <p><strong>Boundary — presentation state ONLY, never content or
 * behaviour.</strong> This object may hold nothing but UI presentation
 * state (collapsed/expanded, layout preferences). Search terms, last-opened
 * scopes, last-read entries, usage timestamps, counters, history — anything
 * that would let an operator or admin infer how a user works — is forbidden
 * here, without exception. The platform does not observe how teams work; a
 * settings field is the easiest place to break that promise by accident,
 * so do not put "just one convenient key" here.
 *
 * <p>The boundary is typed and strict, on purpose: unknown fields are
 * rejected (the any-setter throws, surfacing as 400 at the API), and the
 * boolean fields reject non-boolean JSON (no string/number coercion).
 * Adding a UI switch = one field here plus one merge line in
 * {@code UiSettings.merge} — no schema change, no migration. Removing a
 * field is a deliberate act: rows still carrying the removed key fail loud
 * on read and need a data migration first.
 *
 * <p>{@code null} means "unset" — the console applies its own default
 * (surfaces start expanded). Fields are Booleans, not booleans, exactly so
 * a PATCH can distinguish "not sent" from "set to false".
 */
public class UiSettings {

    /** The connect block on the overview page is collapsed. */
    @JsonDeserialize(using = StrictBoolean.class)
    public Boolean connectCollapsed;

    /** The navigation sidebar is collapsed. */
    @JsonDeserialize(using = StrictBoolean.class)
    public Boolean navCollapsed;

    /** The scope list on the scope browser is collapsed. */
    @JsonDeserialize(using = StrictBoolean.class)
    public Boolean scopesCollapsed;

    /**
     * Field-wise merge: every field the patch carries (non-null) wins; every
     * field it omits keeps the current value. A call that sets only one
     * field must leave the others untouched — two open tabs saving different
     * surfaces must not erase each other's writes. Returns a fresh instance
     * so the ORM's dirty-check sees a new value.
     */
    public static UiSettings merge(UiSettings current, UiSettings patch) {
        UiSettings base = current == null ? new UiSettings() : current;
        UiSettings merged = new UiSettings();
        merged.connectCollapsed =
            patch.connectCollapsed != null ? patch.connectCollapsed : base.connectCollapsed;
        merged.navCollapsed =
            patch.navCollapsed != null ? patch.navCollapsed : base.navCollapsed;
        merged.scopesCollapsed =
            patch.scopesCollapsed != null ? patch.scopesCollapsed : base.scopesCollapsed;
        return merged;
    }

    /** Unknown fields are a typed reject, never silently ignored or stored. */
    @JsonAnySetter
    void rejectUnknown(String field, Object value) {
        throw new IllegalArgumentException("unknown settings field: " + field);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof UiSettings other)) {
            return false;
        }
        return Objects.equals(connectCollapsed, other.connectCollapsed)
            && Objects.equals(navCollapsed, other.navCollapsed)
            && Objects.equals(scopesCollapsed, other.scopesCollapsed);
    }

    @Override
    public int hashCode() {
        return Objects.hash(connectCollapsed, navCollapsed, scopesCollapsed);
    }

    /**
     * Booleans at this boundary are strict: JSON {@code true}/{@code false}
     * only. Jackson's default coercion would quietly accept {@code "true"}
     * or {@code 1} — three spellings of every flag is exactly the rot this
     * typed field exists to prevent.
     */
    static final class StrictBoolean extends JsonDeserializer<Boolean> {
        @Override
        public Boolean deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
            JsonToken t = p.currentToken();
            if (t == JsonToken.VALUE_TRUE) {
                return Boolean.TRUE;
            }
            if (t == JsonToken.VALUE_FALSE) {
                return Boolean.FALSE;
            }
            return (Boolean) ctx.handleUnexpectedToken(Boolean.class, p);
        }
    }
}
