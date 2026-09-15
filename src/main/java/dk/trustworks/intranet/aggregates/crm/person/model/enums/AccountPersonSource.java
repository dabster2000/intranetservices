package dk.trustworks.intranet.aggregates.crm.person.model.enums;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Which of the four feeds currently back a row in {@code account_person} (spec §3.1, V604).
 *
 * <h2>Why this is not persisted as an enum column</h2>
 * The spec writes {@code SET(CALENDAR,TRUSTLINK,SIGNAL,SLACK)}, and V604 deliberately does
 * not: {@code account_person.sources} is a plain {@code VARCHAR(60)} holding the names
 * comma-joined. A native MySQL {@code SET} arrives in Hibernate as a string anyway, but only
 * after an {@code AttributeConverter} nobody reading the entity would expect, and a converter
 * that silently drops an unknown member is a worse failure than a string nobody converted.
 * So the column is a string, this enum owns the encoding, and {@link #join(Collection)} /
 * {@link #split(String)} are the only two places that know the separator.
 *
 * <h2>What the value is for</h2>
 * It is provenance, not a filter: "this person is here because a meeting and a LinkedIn
 * connection both name them" reads very differently from "this person is here because one
 * LinkedIn connection names them", and the Sources section of the tab says which. It is
 * recomputed from scratch on every rebuild rather than accumulated, so a source going quiet —
 * an alias switched off, a mention dismissed — is visible the next morning. The row itself
 * still never leaves: {@code sources} narrowing to nothing is a person whose feeds have gone
 * silent, which is a thing to show, not a thing to delete.
 *
 * <h2>Order is part of the value</h2>
 * {@link #join(Collection)} always emits declaration order, so two rebuilds that saw the same
 * feeds produce the same 30 characters and an equality check on the column means what it
 * looks like it means.
 */
public enum AccountPersonSource {

    /** {@code account_meeting} + {@code account_meeting_attendee} — somebody was in a room. */
    CALENDAR,

    /** {@code trustlink_connection} under an <b>enabled</b> alias — somebody pressed connect. */
    TRUSTLINK,

    /** {@code account_signal.person_name} — a colleague typed a name into a capture. */
    SIGNAL,

    /** {@code account_slack_mention.digest_json} — a model's reading named somebody. */
    SLACK,

    /** An exact calendar email explicitly reviewed and starred; no meeting claim is implied. */
    REVIEW;

    /** The separator in {@code account_person.sources}. Not a character an enum name can contain. */
    public static final String SEPARATOR = ",";

    /**
     * The column value for a set of sources: declaration order, comma-joined, never null.
     *
     * <p>{@code ""} for an empty set rather than null, because the column is
     * {@code NOT NULL DEFAULT ''} and "no feed backs this row any more" is a real and
     * expected state — a person kept alive only by a claim or a star.
     */
    public static String join(Collection<AccountPersonSource> sources) {
        if (sources == null || sources.isEmpty()) {
            return "";
        }
        Set<AccountPersonSource> ordered = EnumSet.copyOf(sources);
        StringBuilder joined = new StringBuilder();
        for (AccountPersonSource source : ordered) {
            if (!joined.isEmpty()) {
                joined.append(SEPARATOR);
            }
            joined.append(source.name());
        }
        return joined.toString();
    }

    /**
     * The sources a stored column value names, ignoring anything this code does not know.
     *
     * <p>An unrecognised member is skipped rather than thrown on: a newer deployment may
     * have written a fifth source into a row an older one is now reading, and a
     * {@code valueOf} blowing up here would take a whole nightly rebuild with it over a
     * column that is only ever displayed. {@link Locale#ROOT} on the case fold, never the
     * default locale.
     */
    public static List<AccountPersonSource> split(String sources) {
        if (sources == null || sources.isBlank()) {
            return List.of();
        }
        List<AccountPersonSource> parsed = new ArrayList<>();
        for (String part : sources.split(SEPARATOR)) {
            String name = part.trim().toUpperCase(Locale.ROOT);
            if (name.isEmpty()) {
                continue;
            }
            try {
                AccountPersonSource source = AccountPersonSource.valueOf(name);
                if (!parsed.contains(source)) {
                    parsed.add(source);
                }
            } catch (IllegalArgumentException ignored) {
                // A source this deployment does not know about. Provenance is not a control.
            }
        }
        return parsed;
    }
}
