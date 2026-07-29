package ai.kumbuka.util;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.Pattern;

/**
 * Validates the optional {@code reference} provenance URL. The URL is
 * stored as metadata and never fetched, so the only risks are storing a secret
 * or a non-http pointer. Rules:
 * <ul>
 *   <li>null/blank → accepted (the field is optional);</li>
 *   <li>must be an absolute {@code http(s)} URL;</li>
 *   <li>must NOT carry credentials — basic-auth ({@code scheme://user:pass@host})
 *       or the common secret query params ({@code token=}, {@code password=},
 *       {@code secret=}, {@code api_key=}, {@code access_token=}). These are
 *       secrets and collide with the never-store rule, especially in shared scopes.</li>
 * </ul>
 * The DB CHECK {@code memory_reference_no_credentials} is the defence-in-depth backstop.
 */
public final class ReferenceUrlValidator {

    private ReferenceUrlValidator() {}

    private static final int MAX_LEN = 2048;

    private static final Pattern BASIC_AUTH = Pattern.compile(
        "(?i)^[a-z][a-z0-9+.-]*://[^/@\\s]*@");

    private static final Pattern CRED_PARAM = Pattern.compile(
        "(?i)[?&](token|password|passwd|secret|api[_-]?key|access[_-]?token)=");

    /** @throws IllegalArgumentException if the URL is non-blank and invalid/credential-bearing. */
    public static void validate(String reference) {
        if (reference == null || reference.isBlank()) {
            return;
        }
        String u = reference.trim();
        if (u.length() > MAX_LEN) {
            throw new IllegalArgumentException("reference URL too long (max " + MAX_LEN + ")");
        }
        if (BASIC_AUTH.matcher(u).find() || CRED_PARAM.matcher(u).find()) {
            throw new IllegalArgumentException(
                "reference URL must not carry credentials (basic-auth or token/password query params)");
        }
        final URI uri;
        try {
            uri = new URI(u);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("reference URL is not a valid URL");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("reference URL must be an absolute http(s) URL");
        }
    }
}
