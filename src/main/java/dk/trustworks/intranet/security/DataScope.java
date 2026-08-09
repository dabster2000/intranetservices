package dk.trustworks.intranet.security;

import java.util.Locale;

/**
 * The row-level dimension of a grant (Phase 8, task 8.1): WHICH records a
 * permission reaches, not just whether the action is allowed.
 *
 * <p>Declaration order is widening order — {@link #OWN} is the narrowest reach,
 * {@link #ALL} is unbounded. {@code role_permission.data_scope} stores the name
 * verbatim; the column default is {@code ALL} because before Phase 8 holding a
 * permission always implied unbounded reach.
 *
 * <p>Scope <em>kinds</em> are code (each needs a domain-aware resolver in
 * {@link AccessScopeResolver}); which scope a role gets is data (a column value,
 * editable per grant — except for {@link ProtectedPermissions}, whose scopes are
 * console-immutable like their bindings).
 */
public enum DataScope {

    /** The actor themselves, nothing else. */
    OWN,

    /** Members of teams where the actor is an active LEADER or SPONSOR — resolved
     *  from the live team relationship, never from role possession alone. */
    TEAM,

    /** Members of the actor's practice. */
    PRACTICE,

    /** Employees of the actor's company. */
    COMPANY,

    /** Unbounded. The only scope legacy boolean permission checks accept. */
    ALL;

    /** Strictly wider reach than {@code other}. */
    public boolean isWiderThan(DataScope other) {
        return other != null && ordinal() > other.ordinal();
    }

    /**
     * Parses a stored column value; returns {@code null} for anything
     * unrecognisable so callers fail closed rather than guessing.
     */
    public static DataScope fromDb(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
