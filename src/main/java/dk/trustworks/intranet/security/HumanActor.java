package dk.trustworks.intranet.security;

import java.util.regex.Pattern;

/**
 * The one place that decides whether an {@code X-Requested-By} value names an
 * acting <em>human</em>. UUID shape is the discriminator: the BFF sends the
 * session user's uuid, while machine identities are non-UUID strings — API
 * client ids ({@code autofix-worker}), {@code system:*} actors, and the
 * {@code "anonymous"} default {@link HeaderInterceptor} back-fills for
 * headerless calls. {@code HeaderInterceptor} applies the same heuristic in
 * reverse to tell API clients from users.
 *
 * <p>Phase 9 scope enforcement keys on this: a human actor is judged by their
 * own grants; a machine identity is deliberately passed untouched until
 * Phase 12 (the findings-recorded fail-open). Treating a client id or
 * {@code "anonymous"} as a human would resolve to no reach and deny every
 * batch and system call.
 */
final class HumanActor {

    private static final Pattern UUID_SHAPE = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private HumanActor() {
    }

    /**
     * The candidate as a human actor uuid, or {@code null} for machine
     * identities (client ids, {@code system:*}, {@code "anonymous"}, absent).
     */
    static String uuidOrNull(String candidate) {
        return candidate != null && UUID_SHAPE.matcher(candidate).matches() ? candidate : null;
    }
}
