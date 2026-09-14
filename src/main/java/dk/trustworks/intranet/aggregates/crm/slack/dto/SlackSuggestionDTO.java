package dk.trustworks.intranet.aggregates.crm.slack.dto;

import dk.trustworks.intranet.aggregates.crm.account.dto.PersonDTO;

import java.time.LocalDate;
import java.util.List;

/**
 * One "Heard in Slack" row (spec §6.2): a company colleagues keep talking about in a
 * general channel that Intra has never heard of.
 *
 * <p>Matches {@code ISlackSuggestion} in {@code src/lib/crm/accountTypes.ts} field for
 * field, and sits beside {@code CalendarSuggestionDTO} on the Contacts view because the two
 * panels ask the same question through different doors.
 *
 * <p>It carries two spellings where the calendar row carries one. A domain identifies a
 * company by construction; a NAME the model read out of a sentence does not, so the row is
 * stored under {@code nameKey} — lower-cased and whitespace-collapsed, the string the
 * matcher resolves aliases on — while {@code displayName} is the spelling a human should
 * read.
 *
 * <p>{@code channels} is what makes the row worth trusting: a name heard once in four
 * channels is a different suggestion from one heard four times in the same one. It is also
 * nearly all the context there is, because no line of Slack is stored anywhere in this
 * lane — {@code permalink} is how somebody goes and reads the original in Slack, where the
 * workspace's own permissions apply.
 *
 * <p>{@code people} is the Trustworks colleagues who WROTE about the company, never anybody
 * at it. That is what makes the row actionable ("Tommy and Lukas keep mentioning them")
 * without storing a single thing about the people on the other side.
 *
 * @param nameKey        the key the row is stored and aliased under; lower-cased
 * @param displayName    the name as first heard — a company, never a person
 * @param mentions90d    cited lines in the last 90 days
 * @param mentionsTotal  cited lines ever, within the 12-month retention window
 * @param firstSeen      the earliest day still held
 * @param lastSeen       the most recent
 * @param channels       the source channels it was heard in, freshest first; a channel
 *                       since removed from the configuration drops out, because its name is
 *                       known only from that row
 * @param people         colleagues who wrote about them, most recent first, capped
 * @param peopleTotal    how many colleagues in total, before the cap
 * @param permalink      the most recent sighting that had one; null when none did
 * @param likelyClientUuid a client this name probably already IS, or null. Conservative and
 *                       token-adjacent: an exact prefix at a word boundary, or the initials
 *                       of every significant word. A name that fits two clients gets none.
 *                       It is a SUGGESTION for a person to accept, never an automatic link —
 *                       a wrong link is far worse than a missing one, because it silently
 *                       files somebody else's news on an account.
 * @param likelyClientName that client's name, for the row to say what it is offering
 * @param corroborated   heard on more than one day, or by more than one colleague, or in
 *                       more than one channel. A single mention by one person in one channel
 *                       is not yet a pattern; see {@code suggestions} for what is done with
 *                       that.
 */
public record SlackSuggestionDTO(
        String nameKey,
        String displayName,
        int mentions90d,
        int mentionsTotal,
        LocalDate firstSeen,
        LocalDate lastSeen,
        List<String> channels,
        List<PersonDTO> people,
        int peopleTotal,
        String permalink,
        String likelyClientUuid,
        String likelyClientName,
        boolean corroborated) {
}
