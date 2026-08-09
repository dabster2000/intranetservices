package dk.trustworks.intranet.security;

import java.util.Set;

/**
 * The concrete reach of an actor for one permission (Phase 8, task 8.2): either
 * unbounded ({@code ALL} — the sentinel the phase file calls for), or the exact
 * set of subject UUIDs the actor may touch, or nothing at all.
 *
 * <p>{@link #none()} is the fail-closed default: no grant, an unknown actor, and
 * any resolution failure all land here — never on {@link #unbounded()}.
 *
 * @param widestScope the widest scope among the grants that produced this
 *                    resolution, for traces ("Anna holds salaries:read with
 *                    scope TEAM"); {@code null} when there is no grant
 * @param unbounded   {@code true} only for an {@code ALL}-scope grant
 * @param subjects    the resolved subject UUIDs; empty when unbounded or denied
 */
public record ScopeResolution(DataScope widestScope, boolean unbounded, Set<String> subjects) {

    private static final ScopeResolution NONE = new ScopeResolution(null, false, Set.of());
    private static final ScopeResolution UNBOUNDED = new ScopeResolution(DataScope.ALL, true, Set.of());

    public static ScopeResolution none() {
        return NONE;
    }

    public static ScopeResolution unboundedAll() {
        return UNBOUNDED;
    }

    public static ScopeResolution bounded(DataScope widestScope, Set<String> subjects) {
        return new ScopeResolution(widestScope, false, Set.copyOf(subjects));
    }

    /** Whether the actor may touch this subject under this resolution. */
    public boolean permits(String subjectUuid) {
        return unbounded || (subjectUuid != null && subjects.contains(subjectUuid));
    }

    /** No grant and no subjects — every check under this resolution denies. */
    public boolean isNone() {
        return !unbounded && subjects.isEmpty();
    }
}
