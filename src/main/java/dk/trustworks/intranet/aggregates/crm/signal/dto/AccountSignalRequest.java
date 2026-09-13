package dk.trustworks.intranet.aggregates.crm.signal.dto;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * The body of {@code POST /account-signals} — everything a caller is allowed to decide.
 *
 * <p>Deliberately narrow. The author, the source, the status and the timestamp are
 * derived server-side from the request context and are absent here on purpose, so no
 * caller can forge who heard something or pre-decide a signal (the mass-assignment rule;
 * same posture as {@code UserDeclaredAvailabilityResource} and {@code BugReportResource}).
 *
 * <p><b>Clients are a list (V593).</b> One line often names more than one account — the
 * capture this was written for named two and only the second survived. {@link #clientUuids}
 * is the field to send; each one becomes its own row, sharing a capture uuid. The singular
 * {@link #clientUuid} is kept so a caller that predates V593 (and the Slack entry points,
 * still unbuilt) does not break, and {@link #allClientUuids()} is the only thing the
 * service reads — never either field directly.
 *
 * <p>Every client must be one the author actually picked with {@code @}. The extractor's
 * own reading of which clients a line names is a SUGGESTION shown in the panel, never an
 * input to this: a signal lands on an account because a person said so.
 *
 * <p>{@link #colleagueUuids} are Trustworks colleagues the line named besides the author
 * — the "sammen Tobias Kjølsen" half of a sentence, which had nowhere to go before V593.
 * Unlike the clients these MAY come from the extractor, because the author sees them as
 * removable chips in the panel before pressing Save and is therefore asserting them.
 *
 * @param clientUuid     legacy single client; unioned with {@link #clientUuids}
 * @param clientUuids    the accounts this capture is about; at least one, each must exist
 * @param text           the verbatim line as typed, {@code @Client} prefixes included
 * @param personName     extracted person, or null
 * @param personRole     extracted role, or null
 * @param relationText   how the author knows the person, phrased from the author, or null
 * @param signalType     one of {@code ORG_CHANGE, COMING_PROJECT, CONTACT_MOVED, TENDER,
 *                       OTHER}; null is accepted and stored as {@code OTHER}
 * @param colleagueUuids Trustworks colleagues the line named besides the author; may be
 *                       null or empty, which is the common case
 */
public record AccountSignalRequest(
        String clientUuid,
        List<String> clientUuids,
        String text,
        String personName,
        String personRole,
        String relationText,
        String signalType,
        List<String> colleagueUuids) {

    /**
     * Every client this capture names, de-duplicated, order preserved.
     *
     * <p>The union of the singular and the plural field. Order is preserved because the
     * first account named is the one the author led with, and nothing downstream should
     * reorder what they said.
     */
    public List<String> allClientUuids() {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        addTrimmed(merged, clientUuid);
        if (clientUuids != null) {
            clientUuids.forEach(uuid -> addTrimmed(merged, uuid));
        }
        return new ArrayList<>(merged);
    }

    /** The colleagues named, de-duplicated and trimmed; never null. */
    public List<String> allColleagueUuids() {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (colleagueUuids != null) {
            colleagueUuids.forEach(uuid -> addTrimmed(merged, uuid));
        }
        return new ArrayList<>(merged);
    }

    private static void addTrimmed(LinkedHashSet<String> target, String raw) {
        if (raw != null && !raw.isBlank()) {
            target.add(raw.trim());
        }
    }
}
