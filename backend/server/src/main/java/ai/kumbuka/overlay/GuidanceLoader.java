package ai.kumbuka.overlay;

import ai.kumbuka.domain.MemoryType;
import ai.kumbuka.util.MemoryContentValidator;
import ai.kumbuka.util.SlugPatterns;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Resolves, strictly parses and validates the system-guidance document from
 * exactly one of two sources — the operator's external file or the bundled
 * default — and hands back a validated in-memory model. Every failure mode is a
 * {@link GuidanceLoadException}, so the eager startup load turns any problem
 * into a refused boot rather than a silent downgrade.
 *
 * <p><b>Resolution order (exactly two levels, never merged).</b>
 * <ol>
 *   <li>If the external file exists and is readable it is the SOLE source — the
 *       bundled default is not consulted, not topped up, not merged for missing
 *       keys.</li>
 *   <li>If it does not exist, the bundled default is the source.</li>
 * </ol>
 * A file that exists but cannot be honoured (unreadable, malformed, invalid)
 * fails the boot; it is never silently downgraded to the bundled default — that
 * would hide the operator's mistake behind working behaviour.
 *
 * <p><b>Strict parsing.</b> Unknown fields are an error, not ignored: an
 * operator who misspells a field name is told, not silently disregarded. This
 * is also what makes the fixed-in-code properties (scope, privacy, source,
 * lock) unsettable from the file — a document that carries any of them names an
 * unknown field and aborts the boot.
 */
final class GuidanceLoader {

    private GuidanceLoader() {}

    /** Which of the two sources the active document came from. */
    enum Source { EXTERNAL, BUNDLED }

    /** The validated document: metadata plus the entries, ready to build from. */
    record Loaded(Source source, String resolvedPath, String version,
                  Instant versionDate, List<Entry> entries) {}

    /**
     * Wire + validated entry shape. {@code comment} carries an optional inline
     * {@code _comment} (JSON has no native comments) and is ignored downstream;
     * every other unrecognised field is rejected by strict parsing.
     */
    record Entry(@JsonProperty("_comment") String comment,
                 String logicalName, String key, String type, String content) {}

    /** Wire document shape. Strict: any field not declared here is an error. */
    private record Doc(@JsonProperty("_comment") String comment,
                       String version, String versionDate, List<Entry> entries) {}

    /**
     * Resolve the source per the two-level order, parse strictly, validate, and
     * return the model. Never returns the bundled default in place of a broken
     * external file.
     *
     * @param externalPath the configured external file path, or {@code null} to
     *                      force the bundled default (unit-test convenience)
     * @param bundledResource classpath path of the bundled default resource
     */
    static Loaded load(Path externalPath, String bundledResource) {
        if (externalPath != null && Files.exists(externalPath)) {
            return loadExternal(externalPath);
        }
        return loadBundled(bundledResource);
    }

    private static Loaded loadExternal(Path path) {
        if (!Files.isReadable(path)) {
            throw new GuidanceLoadException(
                "external system-guidance file is present but not readable: " + path
                + " — fix its permissions, or remove it to fall back to the bundled default.");
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(path);
        } catch (IOException e) {
            throw new GuidanceLoadException(
                "failed to read external system-guidance file: " + path, e);
        }
        return parseValidate(Source.EXTERNAL, path.toString(), bytes);
    }

    private static Loaded loadBundled(String bundledResource) {
        try (InputStream is = GuidanceLoader.class.getResourceAsStream(bundledResource)) {
            if (is == null) {
                throw new GuidanceLoadException(
                    "bundled default guidance resource is missing from the classpath: " + bundledResource);
            }
            return parseValidate(Source.BUNDLED, bundledResource, is.readAllBytes());
        } catch (IOException e) {
            throw new GuidanceLoadException(
                "failed to read bundled default guidance resource: " + bundledResource, e);
        }
    }

    private static Loaded parseValidate(Source source, String where, byte[] bytes) {
        Doc doc = parseStrict(where, bytes);
        validate(where, doc);
        Instant versionDate = parseVersionDate(where, doc.versionDate());
        return new Loaded(source, where, doc.version(), versionDate, doc.entries());
    }

    /**
     * Strict parse. {@code FAIL_ON_UNKNOWN_PROPERTIES} is left enabled (the
     * Jackson default), so a misspelled or injected field — including a
     * forbidden {@code scope} — aborts the boot rather than being ignored.
     */
    private static Doc parseStrict(String where, byte[] bytes) {
        try {
            return new ObjectMapper().readValue(bytes, Doc.class);
        } catch (UnrecognizedPropertyException e) {
            throw new GuidanceLoadException(
                "system-guidance source " + where + " has an unknown field '" + e.getPropertyName()
                + "'. Unknown fields are rejected — check for a typo, and note that scope, privacy, "
                + "source and lock are fixed in code and cannot be set from the file.", e);
        } catch (JsonProcessingException e) {
            throw new GuidanceLoadException(
                "system-guidance source " + where + " is not valid JSON: " + e.getOriginalMessage(), e);
        } catch (IOException e) {
            throw new GuidanceLoadException(
                "failed to parse system-guidance source " + where, e);
        }
    }

    private static void validate(String where, Doc doc) {
        requireField(where, "version", doc.version());
        requireField(where, "versionDate", doc.versionDate());
        // An empty entry list is valid and means "no guidance"; a missing array
        // is not the same thing — the field is required.
        if (doc.entries() == null) {
            throw new GuidanceLoadException(
                "system-guidance source " + where + " is missing the required 'entries' array "
                + "(an empty array is allowed and means 'no guidance').");
        }
        Set<String> keys = new HashSet<>();
        Set<String> logicalNames = new HashSet<>();
        int i = 0;
        for (Entry e : doc.entries()) {
            String at = "entry[" + i + "]";
            requireField(where, at + ".logicalName", e.logicalName());
            requireField(where, at + ".key", e.key());
            requireField(where, at + ".type", e.type());
            requireField(where, at + ".content", e.content());

            if (!SlugPatterns.KEY.matcher(e.key()).matches()) {
                throw new GuidanceLoadException(
                    "system-guidance source " + where + " " + at + " has an invalid key '" + e.key()
                    + "' — keys are lowercase alphanumerics joined by single '.' or '-' separators "
                    + "(the same grammar the database enforces).");
            }
            try {
                MemoryType.fromDb(e.type());
            } catch (IllegalArgumentException ex) {
                throw new GuidanceLoadException(
                    "system-guidance source " + where + " " + at + " has an unknown type '" + e.type()
                    + "' — must be one of decision, convention, constraint, open_question, glossary, status.");
            }
            if (e.content().length() > MemoryContentValidator.MAX_LEN) {
                throw new GuidanceLoadException(
                    "system-guidance source " + where + " " + at + " content is too long ("
                    + e.content().length() + " chars; max " + MemoryContentValidator.MAX_LEN
                    + ", the same limit the database enforces).");
            }
            if (!keys.add(e.key())) {
                throw new GuidanceLoadException(
                    "system-guidance source " + where + " has a duplicate key '" + e.key()
                    + "' — each key must be unique across entries.");
            }
            if (!logicalNames.add(e.logicalName())) {
                throw new GuidanceLoadException(
                    "system-guidance source " + where + " has a duplicate logicalName '" + e.logicalName()
                    + "' — two identical logical names derive the same synthetic id, an identity collision.");
            }
            i++;
        }
    }

    private static void requireField(String where, String field, String value) {
        if (value == null || value.isBlank()) {
            throw new GuidanceLoadException(
                "system-guidance source " + where + " is missing required non-blank field '" + field + "'.");
        }
    }

    private static Instant parseVersionDate(String where, String versionDate) {
        try {
            return Instant.parse(versionDate);
        } catch (DateTimeParseException e) {
            throw new GuidanceLoadException(
                "system-guidance source " + where + " has an unparseable versionDate '" + versionDate
                + "' — expected an ISO-8601 instant, e.g. 2026-07-25T00:00:00Z.", e);
        }
    }
}
