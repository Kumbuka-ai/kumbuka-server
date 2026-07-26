package ai.kumbuka.overlay;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure-unit gate for {@link GuidanceLoader}: the two-level resolution order, the
 * outcome matrix, strict parsing, and every per-entry / per-document validation
 * rule. No CDI, no database — the loader is exercised directly against files in
 * a temp directory and against classpath stand-in resources.
 *
 * <p>The whole point is fail-loud: a file that exists but cannot be honoured
 * aborts (throws), it is NEVER silently downgraded to the bundled default.
 */
class GuidanceLoaderTest {

    private static final String BUNDLED = GuidanceOverlay.BUNDLED_RESOURCE;

    private static final String VALID_ONE_ENTRY = """
        {"version":"ext-9","versionDate":"2026-07-26T00:00:00Z","entries":[
          {"logicalName":"solo","key":"convention.solo","type":"convention","content":"one entry only"}]}""";

    @TempDir
    Path tmp;

    // ---------------------------------------------------------------------
    // Outcome matrix
    // ---------------------------------------------------------------------

    @Test
    void externalAbsent_usesBundledDefault() {
        GuidanceLoader.Loaded loaded = GuidanceLoader.load(tmp.resolve("nope.json"), BUNDLED);
        assertThat(loaded.source()).isEqualTo(GuidanceLoader.Source.BUNDLED);
        assertThat(loaded.entries()).isNotEmpty();
    }

    @Test
    void nullExternalPath_usesBundledDefault() {
        GuidanceLoader.Loaded loaded = GuidanceLoader.load(null, BUNDLED);
        assertThat(loaded.source()).isEqualTo(GuidanceLoader.Source.BUNDLED);
    }

    @Test
    void externalPresentButUnreadable_failsBoot_neverDowngrades() throws IOException {
        Path f = write("perm.json", VALID_ONE_ENTRY);
        try {
            Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("---------"));
        } catch (UnsupportedOperationException e) {
            Assumptions.abort("non-POSIX filesystem");
        }
        // Skip when the current user can read it anyway (e.g. running as root).
        Assumptions.assumeFalse(Files.isReadable(f), "file still readable for this user");

        assertThatThrownBy(() -> GuidanceLoader.load(f, BUNDLED))
            .isInstanceOf(GuidanceLoadException.class)
            .hasMessageContaining("not readable");
    }

    @Test
    void externalPresentButUnreadable_directory_failsBoot() {
        // A directory exists and "is readable", but reading it as a file fails —
        // exercises the read-IOException branch (still a refused boot).
        assertThatThrownBy(() -> GuidanceLoader.load(tmp, BUNDLED))
            .isInstanceOf(GuidanceLoadException.class)
            .hasMessageContaining("read");
    }

    @Test
    void externalPresentButMalformedJson_failsBoot() throws IOException {
        Path f = write("bad.json", "{ this is not json ");
        assertThatThrownBy(() -> GuidanceLoader.load(f, BUNDLED))
            .isInstanceOf(GuidanceLoadException.class)
            .hasMessageContaining("not valid JSON");
    }

    @Test
    void externalPresentButFailsValidation_failsBoot() throws IOException {
        Path f = write("invalid.json", """
            {"version":"1","versionDate":"2026-07-26T00:00:00Z","entries":[
              {"logicalName":"x","key":"convention.x","type":"convention","content":"  "}]}""");
        assertThatThrownBy(() -> GuidanceLoader.load(f, BUNDLED))
            .isInstanceOf(GuidanceLoadException.class)
            .hasMessageContaining("content");
    }

    @Test
    void bundledDefaultFailsValidation_failsBoot() {
        assertThatThrownBy(() -> GuidanceLoader.load(null, "/guidance/invalid-bundled.json"))
            .isInstanceOf(GuidanceLoadException.class)
            .hasMessageContaining("invalid key");
    }

    @Test
    void missingBundledResource_failsBoot() {
        assertThatThrownBy(() -> GuidanceLoader.load(null, "/guidance/does-not-exist.json"))
            .isInstanceOf(GuidanceLoadException.class)
            .hasMessageContaining("missing from the classpath");
    }

    // ---------------------------------------------------------------------
    // External file is the SOLE source (no merge with the bundled default)
    // ---------------------------------------------------------------------

    @Test
    void validExternalFile_isSoleSource_notMergedWithBundled() throws IOException {
        Path f = write("solo.json", VALID_ONE_ENTRY);
        GuidanceLoader.Loaded loaded = GuidanceLoader.load(f, BUNDLED);

        assertThat(loaded.source()).isEqualTo(GuidanceLoader.Source.EXTERNAL);
        assertThat(loaded.resolvedPath()).isEqualTo(f.toString());
        assertThat(loaded.version()).isEqualTo("ext-9");
        // Exactly the file's one entry — the bundled default's three are NOT
        // merged in, not topped up, not consulted for missing keys.
        assertThat(loaded.entries()).hasSize(1);
        assertThat(loaded.entries().get(0).key()).isEqualTo("convention.solo");
    }

    @Test
    void emptyEntryList_isValid_meansNoGuidance() throws IOException {
        Path f = write("empty.json",
            "{\"version\":\"1\",\"versionDate\":\"2026-07-26T00:00:00Z\",\"entries\":[]}");
        GuidanceLoader.Loaded loaded = GuidanceLoader.load(f, BUNDLED);
        assertThat(loaded.source()).isEqualTo(GuidanceLoader.Source.EXTERNAL);
        assertThat(loaded.entries()).isEmpty();
    }

    // ---------------------------------------------------------------------
    // Strict parsing: unknown fields are an error, incl. a forbidden scope
    // ---------------------------------------------------------------------

    @Test
    void unknownField_failsBoot() throws IOException {
        Path f = write("unknown.json", """
            {"version":"1","versionDate":"2026-07-26T00:00:00Z","typo":true,"entries":[]}""");
        assertThatThrownBy(() -> GuidanceLoader.load(f, BUNDLED))
            .isInstanceOf(GuidanceLoadException.class)
            .hasMessageContaining("unknown field")
            .hasMessageContaining("typo");
    }

    @Test
    void scopeFieldOnEntry_failsBoot_securityProperty() throws IOException {
        // The load-bearing security property: an externally supplied scope would
        // inject content past tenant isolation. Strict parsing rejects it.
        Path f = write("scope.json", """
            {"version":"1","versionDate":"2026-07-26T00:00:00Z","entries":[
              {"logicalName":"x","key":"convention.x","type":"convention",
               "content":"c","scope":"some-tenant-project"}]}""");
        assertThatThrownBy(() -> GuidanceLoader.load(f, BUNDLED))
            .isInstanceOf(GuidanceLoadException.class)
            .hasMessageContaining("unknown field")
            .hasMessageContaining("scope");
    }

    @Test
    void privateAndLockAndSourceFields_failBoot() throws IOException {
        for (String forbidden : new String[] {"\"isPrivate\":true", "\"lock\":\"none\"", "\"source\":\"mcp\""}) {
            Path f = write("forbid-" + forbidden.hashCode() + ".json", """
                {"version":"1","versionDate":"2026-07-26T00:00:00Z","entries":[
                  {"logicalName":"x","key":"convention.x","type":"convention","content":"c",%s}]}"""
                .formatted(forbidden));
            assertThatThrownBy(() -> GuidanceLoader.load(f, BUNDLED))
                .as("forbidden field %s must fail the boot", forbidden)
                .isInstanceOf(GuidanceLoadException.class)
                .hasMessageContaining("unknown field");
        }
    }

    @Test
    void inlineCommentField_isAllowed() throws IOException {
        Path f = write("comment.json", """
            {"_comment":"doc note","version":"1","versionDate":"2026-07-26T00:00:00Z","entries":[
              {"_comment":"entry note","logicalName":"x","key":"convention.x","type":"convention","content":"c"}]}""");
        assertThatCode(() -> GuidanceLoader.load(f, BUNDLED)).doesNotThrowAnyException();
    }

    // ---------------------------------------------------------------------
    // Per-entry validation
    // ---------------------------------------------------------------------

    @Test
    void blankLogicalName_failsBoot() {
        assertEntryFails("""
            {"logicalName":"  ","key":"convention.x","type":"convention","content":"c"}""",
            "logicalName");
    }

    @Test
    void blankKey_failsBoot() {
        assertEntryFails("""
            {"logicalName":"x","key":"","type":"convention","content":"c"}""", "key");
    }

    @Test
    void invalidKeyPattern_failsBoot() {
        assertEntryFails("""
            {"logicalName":"x","key":"Convention.BadKey","type":"convention","content":"c"}""",
            "invalid key");
    }

    @Test
    void unknownType_failsBoot() {
        assertEntryFails("""
            {"logicalName":"x","key":"convention.x","type":"guideline","content":"c"}""",
            "unknown type");
    }

    @Test
    void contentTooLong_failsBoot() {
        String tooLong = "a".repeat(1501);
        assertEntryFails("""
            {"logicalName":"x","key":"convention.x","type":"convention","content":"%s"}"""
            .formatted(tooLong), "too long");
    }

    @Test
    void blankContent_failsBoot() {
        assertEntryFails("""
            {"logicalName":"x","key":"convention.x","type":"convention","content":"   "}""",
            "content");
    }

    // ---------------------------------------------------------------------
    // Per-document validation
    // ---------------------------------------------------------------------

    @Test
    void missingVersion_failsBoot() throws IOException {
        Path f = write("nover.json",
            "{\"versionDate\":\"2026-07-26T00:00:00Z\",\"entries\":[]}");
        assertThatThrownBy(() -> GuidanceLoader.load(f, BUNDLED))
            .isInstanceOf(GuidanceLoadException.class).hasMessageContaining("version");
    }

    @Test
    void missingVersionDate_failsBoot() throws IOException {
        Path f = write("nodate.json", "{\"version\":\"1\",\"entries\":[]}");
        assertThatThrownBy(() -> GuidanceLoader.load(f, BUNDLED))
            .isInstanceOf(GuidanceLoadException.class).hasMessageContaining("versionDate");
    }

    @Test
    void unparseableVersionDate_failsBoot() throws IOException {
        Path f = write("baddate.json",
            "{\"version\":\"1\",\"versionDate\":\"last tuesday\",\"entries\":[]}");
        assertThatThrownBy(() -> GuidanceLoader.load(f, BUNDLED))
            .isInstanceOf(GuidanceLoadException.class).hasMessageContaining("versionDate");
    }

    @Test
    void missingEntriesArray_failsBoot() throws IOException {
        Path f = write("noentries.json",
            "{\"version\":\"1\",\"versionDate\":\"2026-07-26T00:00:00Z\"}");
        assertThatThrownBy(() -> GuidanceLoader.load(f, BUNDLED))
            .isInstanceOf(GuidanceLoadException.class).hasMessageContaining("entries");
    }

    @Test
    void duplicateKey_failsBoot() throws IOException {
        Path f = write("dupkey.json", """
            {"version":"1","versionDate":"2026-07-26T00:00:00Z","entries":[
              {"logicalName":"a","key":"convention.dup","type":"convention","content":"c1"},
              {"logicalName":"b","key":"convention.dup","type":"convention","content":"c2"}]}""");
        assertThatThrownBy(() -> GuidanceLoader.load(f, BUNDLED))
            .isInstanceOf(GuidanceLoadException.class).hasMessageContaining("duplicate key");
    }

    @Test
    void duplicateLogicalName_failsBoot() throws IOException {
        Path f = write("duplogical.json", """
            {"version":"1","versionDate":"2026-07-26T00:00:00Z","entries":[
              {"logicalName":"same","key":"convention.a","type":"convention","content":"c1"},
              {"logicalName":"same","key":"convention.b","type":"convention","content":"c2"}]}""");
        assertThatThrownBy(() -> GuidanceLoader.load(f, BUNDLED))
            .isInstanceOf(GuidanceLoadException.class).hasMessageContaining("duplicate logicalName");
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private Path write(String name, String content) throws IOException {
        Path f = tmp.resolve(name);
        Files.writeString(f, content);
        return f;
    }

    private void assertEntryFails(String entryJson, String messageNeedle) {
        try {
            Path f = write("entry-" + entryJson.hashCode() + ".json", """
                {"version":"1","versionDate":"2026-07-26T00:00:00Z","entries":[%s]}"""
                .formatted(entryJson));
            assertThatThrownBy(() -> GuidanceLoader.load(f, BUNDLED))
                .isInstanceOf(GuidanceLoadException.class)
                .hasMessageContaining(messageNeedle);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }
}
