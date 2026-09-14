package dk.trustworks.intranet.aggregates.crm.slack.dto;

import java.util.List;
import java.util.Locale;
import java.util.Set;

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
 * <h2>Why {@code signalType} exists, and why {@code relevance} is no longer asked for</h2>
 * The first production run (2026-09-14) graded 11 of 20 account-space days HIGH, and five
 * of those eleven were internal engineering: a CVE, an E2E test fixture, a review comment
 * on an architecture drawing, a Coupa password reset. Meanwhile the source-channel lane
 * filed "styregruppemøde skal drøfte flerårigt samarbejde og kontraktfornyelser" and
 * "forlængelser for tre ressourcer ind i 2027" as LOW. Both prompts had asked for HIGH
 * when the day "holds a decision, a risk or a client ask" — a test on the GRAMMAR of what
 * was said, which a build failure passes and a renewal conversation can fail.
 *
 * <p>So the model is now asked what KIND of account event it read, from a closed list, and
 * {@code relevance} is DERIVED from that ({@link #relevanceOf}). Three things follow:
 * a build failure has a name of its own and cannot borrow a decision's grade; the CRM can
 * ask "every extension signal across all accounts", which one three-level enum could never
 * answer; and {@code AccountSlackDigestService.relevance}'s old reconciliation — which
 * promoted any LOW carrying a non-empty list, and ignored {@code nextSteps} while doing it
 * — has nothing left to reconcile.
 *
 * @param headline     one line for the timeline row, at most 200 chars; null when the day
 *                     was irrelevant chatter ({@code signalType = NONE})
 * @param signalType   the kind of account event, from the closed set below
 * @param relevance    {@code NONE}, {@code LOW} or {@code HIGH} — DERIVED from
 *                     {@code signalType}, never taken from the model
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
        String signalType,
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

    // ------------------------------------------------------------------------
    // Signal types — the closed set the model chooses from
    //
    // Each one names something an account manager DOES something about, and the
    // set is ordered by consequence in PRIORITY below, which is how a day that
    // carries two of them picks one and how a merged multi-part mention picks
    // the row's type. Adding a value means: this list, PRIORITY, one of the two
    // HIGH/LOW sets, the enum in both prompt schemas, and the migration's
    // column comment. Nothing reads these as an SQL enum — the column is a
    // varchar, so an unknown value degrades to NONE rather than failing a write.
    // ------------------------------------------------------------------------

    /** Somebody on or off the engagement, an FTE share changing, a start date. */
    public static final String SIGNAL_ALLOCATION = "ALLOCATION";
    /** A prolongation, a renewal, a contract period or an option being discussed or taken. */
    public static final String SIGNAL_EXTENSION = "EXTENSION";
    /** Work beyond what is contracted: a new phase, an upsell, a need the client has voiced. */
    public static final String SIGNAL_NEW_SCOPE = "NEW_SCOPE";
    /** An offer, a pitch, a tender or a bid — sent, to be sent, or published. */
    public static final String SIGNAL_PROPOSAL = "PROPOSAL";
    /**
     * A new opening: an inbound approach, somebody flagging a target, an explicit "we should
     * be in on this". The one type that does NOT presuppose an engagement — it is how a
     * company we have never worked with first earns a row.
     *
     * <p>Added after the first eleven types missed the case they were least likely to
     * contain: they were derived from 43 readings of existing-engagement traffic, so the
     * set had no word for new business. A colleague posting a prospect's LinkedIn note with
     * "vi skal med i denne dialog... er det en du vil tage?" graded RELATIONSHIP — LOW,
     * because a person at the company was named — which is the quietest thing the taxonomy
     * could have said about the most actionable message in the channel.
     */
    public static final String SIGNAL_LEAD = "LEAD";
    /** A yes: signed, approved, awarded, or a verbal go-ahead. */
    public static final String SIGNAL_WON = "WON";
    /** A no: rejected, cancelled, lost, or a client walking away. */
    public static final String SIGNAL_LOST = "LOST";
    /** A purchasing, legal or contractual gate standing between us and the work. */
    public static final String SIGNAL_PROCUREMENT = "PROCUREMENT";
    /** Dissatisfaction, a complaint, an escalation — the relationship itself is at risk. */
    public static final String SIGNAL_ESCALATION = "ESCALATION";
    /** A regulatory, legal or contractual exposure that would cost us if it is wrong. */
    public static final String SIGNAL_COMPLIANCE = "COMPLIANCE";
    /** Delivery status the CLIENT can see: a date slipping, testing blocked, a release held. */
    public static final String SIGNAL_DELIVERY = "DELIVERY";
    /** A meeting, a call, a visit, or a client-side person arriving, leaving or being named. */
    public static final String SIGNAL_RELATIONSHIP = "RELATIONSHIP";
    /** Nothing about the account — internal chatter, tooling, our own builds. */
    public static final String SIGNAL_NONE = "NONE";

    /**
     * Most consequential first. Used to pick one type for a day that carries two, and to
     * pick the type of a merged mention in {@code SlackMentionExtractionService}.
     */
    public static final List<String> PRIORITY = List.of(
            SIGNAL_WON, SIGNAL_LOST, SIGNAL_EXTENSION, SIGNAL_NEW_SCOPE, SIGNAL_PROPOSAL,
            // Below PROPOSAL on purpose: a proposal out is further along than an opening
            // somebody has spotted, so on a day that holds both, the proposal is the news.
            SIGNAL_LEAD,
            SIGNAL_ESCALATION, SIGNAL_PROCUREMENT, SIGNAL_ALLOCATION, SIGNAL_COMPLIANCE,
            SIGNAL_DELIVERY, SIGNAL_RELATIONSHIP, SIGNAL_NONE);

    /**
     * The types that make a row HIGH — the ones that move money, continuity or the
     * relationship, and that somebody should see this week.
     */
    private static final Set<String> HIGH_SIGNALS = Set.of(
            SIGNAL_ALLOCATION, SIGNAL_EXTENSION, SIGNAL_NEW_SCOPE, SIGNAL_PROPOSAL, SIGNAL_LEAD,
            SIGNAL_WON, SIGNAL_LOST, SIGNAL_PROCUREMENT, SIGNAL_ESCALATION, SIGNAL_COMPLIANCE);

    /**
     * The types that are worth keeping and are not news: they answer "what is going on
     * here" when somebody opens the account, and they never interrupt anybody.
     */
    private static final Set<String> LOW_SIGNALS = Set.of(SIGNAL_DELIVERY, SIGNAL_RELATIONSHIP);

    /**
     * The types that make a row HIGH, for a caller that has to ask the question in SQL.
     * A copy, so nothing outside can widen the set that decides what is loud.
     */
    public static List<String> highSignals() {
        return List.copyOf(HIGH_SIGNALS);
    }

    /** The closed set, for validation. Order is PRIORITY's. */
    public static boolean isSignalType(String value) {
        return PRIORITY.contains(value);
    }

    /**
     * The stored signal type for whatever the model answered: the value when it is one of
     * ours, {@link #SIGNAL_NONE} otherwise. An unknown value is a model that ignored its
     * own schema, and reading that as NONE keeps it out of the feed rather than letting it
     * through ungraded.
     */
    public static String signalTypeOf(String raw) {
        if (raw == null) {
            return SIGNAL_NONE;
        }
        String value = raw.trim().toUpperCase(Locale.ROOT);
        return isSignalType(value) ? value : SIGNAL_NONE;
    }

    /**
     * Relevance, derived. {@code NONE} for a day with no signal or no headline to show —
     * a headline is the row, so a row without one cannot be rendered whatever it is
     * graded. Otherwise HIGH for the money-and-continuity types and LOW for the rest.
     *
     * <p>This replaces the old reconciliation against the lists. That rule promoted any
     * reading with a non-empty decisions/risks/clientAsks list to HIGH, which is how "Ny
     * CVE rammer sandsynligvis alle pipelines" and "E2E-fejl i PR-builds skyldes manglende
     * reset af velkomstbanner" were graded the same as "Marta øges til 50% mindst til juni
     * 2027"; and it never looked at {@code nextSteps}, which is how a steering-group
     * meeting about contract renewals was graded LOW.
     */
    public static String relevanceOf(String signalType, String headline) {
        String signal = signalTypeOf(signalType);
        if (SIGNAL_NONE.equals(signal) || headline == null || headline.isBlank()) {
            return RELEVANCE_NONE;
        }
        if (HIGH_SIGNALS.contains(signal)) {
            return RELEVANCE_HIGH;
        }
        return LOW_SIGNALS.contains(signal) ? RELEVANCE_LOW : RELEVANCE_NONE;
    }

    /** The more consequential of two signal types, by {@link #PRIORITY}. */
    public static String strongerSignal(String left, String right) {
        int l = PRIORITY.indexOf(signalTypeOf(left));
        int r = PRIORITY.indexOf(signalTypeOf(right));
        return l <= r ? signalTypeOf(left) : signalTypeOf(right);
    }

    /** True when the reading has at least one substantive item to show under the headline. */
    public boolean hasDetails() {
        return !decisions.isEmpty() || !nextSteps.isEmpty() || !risks.isEmpty()
                || !clientAsks.isEmpty() || !clientPeople.isEmpty();
    }
}
