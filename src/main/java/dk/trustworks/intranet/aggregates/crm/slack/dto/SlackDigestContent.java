package dk.trustworks.intranet.aggregates.crm.slack.dto;

import java.util.List;

/**
 * The model's structured reading of one day in an account space, AFTER
 * {@code AccountSlackDigestService.parse} has validated, capped and stripped it. This is
 * what {@code account_slack_digest.digest_json} holds and what the timeline row carries
 * under its summary line.
 *
 * <p>Every string here is a paraphrase the model wrote and the backend bounded — never a
 * message. The shape is what the account owner asked for in CRM spec §4.9 ("decisions and
 * next steps") widened to what a day in a client channel actually contains: the risks
 * colleagues voice, what the client is waiting on, who at the client was named, and what
 * the day was about.
 *
 * @param headline     one line for the timeline row, at most 200 chars; null when the day
 *                     was irrelevant chatter ({@code relevance = NONE})
 * @param relevance    {@code NONE}, {@code LOW} or {@code HIGH}
 * @param decisions    things decided, agreed, confirmed or rejected — ours and the client's
 * @param nextSteps    concrete things somebody will do, with who and when where stated
 * @param risks        what threatens the delivery, the timeline or the relationship
 * @param clientAsks   what the client asked for or is waiting on, and what we wait on
 * @param clientPeople people on the client side the messages named, with a role if stated
 * @param topics       one to six short tags
 * @param confidence   the model's own confidence in the whole reading, 0-1
 */
public record SlackDigestContent(
        String headline,
        String relevance,
        List<Item> decisions,
        List<Item> nextSteps,
        List<Note> risks,
        List<Note> clientAsks,
        List<Person> clientPeople,
        List<String> topics,
        double confidence) {

    /** A decision or a next step: the line, who owns it (a first name) and an ISO date, both nullable. */
    public record Item(String text, String who, String when) {
    }

    /** A risk or a client ask — one line. */
    public record Note(String text) {
    }

    /** Somebody on the client side, as named in the channel; the role only when one was stated. */
    public record Person(String name, String role) {
    }

    public static final String RELEVANCE_NONE = "NONE";
    public static final String RELEVANCE_LOW = "LOW";
    public static final String RELEVANCE_HIGH = "HIGH";

    /** True when the reading has at least one substantive item to show under the headline. */
    public boolean hasDetails() {
        return !decisions.isEmpty() || !nextSteps.isEmpty() || !risks.isEmpty()
                || !clientAsks.isEmpty() || !clientPeople.isEmpty();
    }
}
