package ai.kumbuka.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure-logic coverage of the typed settings shape: the field-wise merge
 * (the two-tabs guarantee) and the unknown-field reject. The HTTP-level
 * behaviour (400s, persistence, per-user isolation) is covered against a
 * real database in {@code SessionResourceTest}.
 */
class UiSettingsTest {

    private static UiSettings settings(Boolean connect, Boolean nav) {
        UiSettings s = new UiSettings();
        s.connectCollapsed = connect;
        s.navCollapsed = nav;
        return s;
    }

    @Test
    void merge_patchFieldWins_omittedFieldKeepsCurrent() {
        UiSettings current = settings(true, false);
        UiSettings patch = settings(null, true);   // only navCollapsed sent

        UiSettings merged = UiSettings.merge(current, patch);

        assertThat(merged.connectCollapsed).isTrue();   // kept
        assertThat(merged.navCollapsed).isTrue();       // patched
    }

    @Test
    void merge_explicitFalse_overwrites() {
        // false is a value, not an omission — expanding a surface must stick.
        UiSettings merged = UiSettings.merge(settings(true, true), settings(false, null));

        assertThat(merged.connectCollapsed).isFalse();
        assertThat(merged.navCollapsed).isTrue();
    }

    @Test
    void merge_nullCurrent_behavesLikeEmpty() {
        UiSettings merged = UiSettings.merge(null, settings(true, null));

        assertThat(merged.connectCollapsed).isTrue();
        assertThat(merged.navCollapsed).isNull();
    }

    @Test
    void merge_returnsAFreshInstance() {
        // A new instance, so the ORM dirty-check compares values, not identity.
        UiSettings current = settings(true, true);
        UiSettings merged = UiSettings.merge(current, new UiSettings());

        assertThat(merged).isNotSameAs(current).isEqualTo(current);
    }

    @Test
    void unknownField_isARejectNotAStore() {
        assertThatThrownBy(() -> new UiSettings().rejectUnknown("lastSearchTerm", "x"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("lastSearchTerm");
    }
}
