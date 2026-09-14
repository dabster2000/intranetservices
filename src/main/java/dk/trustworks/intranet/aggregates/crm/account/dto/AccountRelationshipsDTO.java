package dk.trustworks.intranet.aggregates.crm.account.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * The people we know at one account, the colleagues who know them, and the evidence for each
 * of those claims (spec §3.3, §3.4, §3.7).
 *
 * <h2>What changed, and why the old shape could not survive</h2>
 * Until this cut a person at a client <i>was</i> a display name, re-derived on every request,
 * and the tab drew a graph of names. Three things broke on that:
 * <ul>
 *   <li><b>A name cannot carry a claim.</b> "I know her, we worked together at KMD" and "she
 *       matters on this account" both need something to point at, and the same human arrives
 *       as {@code "Sara Louise Vest (XSVES)"} from one mailbox, {@code "Sara Vest"} from
 *       another and a TrustLink person id from a third. {@link ClientPersonDTO#uuid()} is now
 *       an {@code account_person} row and every edge resolves to one.</li>
 *   <li><b>Our own consultants were drawn as the client's contacts.</b> The registry
 *       classifies a person as {@code COLLEAGUE} and those never leave the backend: this
 *       record's {@code people} carries {@code CONTACT} and {@code ALUMNI} only.</li>
 *   <li><b>A cap that deleted people.</b> {@code externalTotal}, {@code trustworksPeople},
 *       {@code externalPeople}, {@code consentedPeople} and {@code totalPeople} are gone
 *       along with the twelve-person cap that made them necessary. The table is virtualised
 *       and reads the whole account.</li>
 * </ul>
 *
 * <h2>Everything here is still derived</h2>
 * There is no contact table anybody maintains. {@code MET} comes from consenting mailboxes'
 * calendar metadata, {@code KNOWS} from a colleague's own capture, {@code HEARD} from a Slack
 * day a model read, {@code CONNECTED} from TrustLink's mirror of LinkedIn — and
 * {@code CLAIM}, new in this cut, is the one thing a colleague types: "this is how I know
 * her". A claim is a statement <i>about</i> a person the sources already found; there is no
 * path from it to a new person.
 *
 * <h2>An empty answer is a real answer</h2>
 * Nobody has consented to calendar reads, nobody has filed a signal, and no TrustLink company
 * name maps to this client — that is common, and {@link FreshnessDTO#sharing()} is what lets
 * the tab say WHY it is empty instead of leaving a reader to conclude the client has no
 * relationships. An unknown {@code clientUuid} answers <b>200 with an all-empty record</b>,
 * never 404; callers and tests depend on that.
 *
 * <h2>No extra accessors on these records</h2>
 * Jackson's record support treats <i>every</i> no-arg method as a property, so a convenience
 * accessor added to any record below would silently appear on the wire and put the frontend's
 * declared types out of step with what it actually receives. Derivations belong in the
 * service or in {@code relationshipWarmth.ts}, never here.
 */
public record AccountRelationshipsDTO(
        List<ClientPersonDTO> people,
        List<ColleagueDTO> colleagues,
        List<RelationEdgeDTO> edges,
        FreshnessDTO freshness) {

    /**
     * The answer for an account that has nothing — and for a {@code clientUuid} that is not an
     * account at all.
     *
     * <p>A static factory rather than a constant because {@link FreshnessDTO#quiet()} is a
     * judgement about a date and the caller owns the clock.
     */
    public static AccountRelationshipsDTO empty(FreshnessDTO freshness) {
        return new AccountRelationshipsDTO(List.of(), List.of(), List.of(), freshness);
    }

    /**
     * One person at the client, as the registry holds them.
     *
     * @param uuid           the {@code account_person} row; the thing a claim and a star point at
     * @param kind           {@link #CONTACT} or {@link #ALUMNI}. {@code COLLEAGUE} never leaves
     *                       the backend — a person classified as one of ours is filtered out of
     *                       this list and every edge to them is dropped
     * @param linkedInUrl    TrustLink's, verbatim and <b>unvalidated</b>. The backend mirrors
     *                       what a third party wrote; the only guard is the frontend's
     *                       {@code isSafeExternalUrl} allow-list, and any new consumer needs it
     *                       too or it is a click-to-execute XSS served to everyone with
     *                       {@code accounts:read}
     * @param stakeholderUuid the {@code client_plan_stakeholder} row a star created, or null.
     *                       Non-null <i>is</i> "matters here" — there is no separate flag
     * @param tier           1–5 from {@code RelationshipWarmth}: the person's best edge, clamped
     *                       to 3 when they are {@code ALUMNI}. A person with no edges is 5
     * @param lastContactOn  the latest {@code MET} edge's day, or null
     * @param meetings       the sum of the person's {@code MET} edge weights. Known to
     *                       over-count one real event per consenting mailbox that saw it; that
     *                       is the calendar sync's one-row-per-mailbox shape, not this record's
     * @param alumniLeftOn   when they left us, for {@link #ALUMNI} only
     */
    public record ClientPersonDTO(
            String uuid,
            String name,
            String initials,
            String title,
            String kind,
            String linkedInUrl,
            String stakeholderUuid,
            int tier,
            LocalDate lastContactOn,
            int meetings,
            LocalDate alumniLeftOn) {

        /** Somebody at the client. */
        public static final String CONTACT = "CONTACT";

        /** A former colleague who now works there — one of the warmest contacts the firm has. */
        public static final String ALUMNI = "ALUMNI";
    }

    /**
     * One of ours on this account.
     *
     * <p><b>There is no filler.</b> A colleague is here because they own the account, hold a
     * {@code client_account_role} on it, or because an edge names them. The old "twelve most
     * recent people on a contract" seeding is deleted: it listed a decade of former
     * consultants and buried the handful who actually know somebody.
     *
     * @param uuid           null for a TrustLink trustworker name that matched no Intra user.
     *                       Such a person is still drawn — refusing to guess between two
     *                       duplicate {@code user} rows is exactly when the name is the only
     *                       signal we have — but they are counted on neither side of
     *                       {@link SharingDTO}, because somebody with no account cannot consent
     * @param onAccount      {@link #OWNER}, {@link #SUPPORTED_BY}, {@link #MEMBER} or null. A
     *                       team member with no contact at all renders as exactly that, which
     *                       is the white spot the "by colleague" view exists to show
     * @param sharesCalendar whether their mailbox may be read, with an explicit
     *                       {@code user_calendar_consent} row always beating the
     *                       SALES/PARTNER/ADMIN role default
     * @param peopleKnown    how many distinct people at this client they have any edge to
     */
    public record ColleagueDTO(
            String uuid,
            String name,
            String initials,
            String onAccount,
            boolean sharesCalendar,
            int peopleKnown) {

        /** {@code client.accountmanager} — the one owner, since V598. */
        public static final String OWNER = "OWNER";

        /** A {@code client_account_role} supporter. */
        public static final String SUPPORTED_BY = "SUPPORTED_BY";

        /** A {@code client_account_role} team member. */
        public static final String MEMBER = "MEMBER";
    }

    /**
     * One piece of evidence that a colleague and a person at the client have something to do
     * with each other.
     *
     * <p><b>The Trustworks end is joined by NAME, never by uuid.</b> That is what lets an
     * unmatched TrustLink trustworker be drawn at all, and it is how the Overview chips and
     * the plan's people list group. Two {@code user} rows for one human — this firm has
     * several — are therefore one colleague and one chip.
     *
     * @param personUuid  the {@code account_person} row, or null for an edge whose person the
     *                    registry has not reached yet (a signal captured seconds ago, before
     *                    its rebuild hook landed). Never a {@code COLLEAGUE} person: those
     *                    edges are dropped rather than drawn
     * @param twPersonUuid the colleague's Intra user, when they have one
     * @param externalName what to call the person; carried even when {@code personUuid} is null
     * @param meetings    how many meetings the two were both in; 0 for every source but
     *                    {@link #MET}
     * @param lastMet     {@link #MET} only
     * @param knowsVia    the sentence a colleague wrote for {@link #KNOWS}, the day's headline
     *                    for {@link #HEARD}, and null for the rest. For a signal that sentence
     *                    IS the evidence, which is why a blank one draws no edge; a Slack day
     *                    deliberately has no such gate, because it still has a day
     * @param source      {@link #MET}, {@link #CLAIM}, {@link #KNOWS}, {@link #HEARD} or
     *                    {@link #CONNECTED}. A source a consumer does not recognise must rank
     *                    last, never first
     * @param connectedOn {@link #CONNECTED} only, and null even then for the connections
     *                    TrustLink has no date for
     * @param heardOn     {@link #HEARD} only — the day the channel said it. A separate
     *                    component from {@code lastMet} because they are different claims and
     *                    every consumer words them differently
     * @param strength    {@link #CLAIM} only: 1 met once · 2 know each other · 3 good working
     *                    relationship · 4 trusted
     * @param how         {@link #CLAIM} only — free text the claimant typed
     * @param claimedAt   {@link #CLAIM} only, and the date warmth ranks a claim on
     */
    public record RelationEdgeDTO(
            String personUuid,
            String twPersonName,
            String twPersonUuid,
            String externalName,
            int meetings,
            LocalDate lastMet,
            String knowsVia,
            String source,
            LocalDate connectedOn,
            LocalDate heardOn,
            Integer strength,
            String how,
            LocalDate claimedAt) {

        /** They were both in a meeting — the strongest evidence the graph derives. */
        public static final String MET = "MET";

        /**
         * A colleague said, in so many words, how well they know this person. The only typed
         * edge, and the only one that can answer "who can actually pick up the phone".
         */
        public static final String CLAIM = "CLAIM";

        /** A colleague filed a signal saying how they know them. */
        public static final String KNOWS = "KNOWS";

        /**
         * A Slack channel talked about this person and this colleague was in that
         * conversation. Nobody claimed an acquaintance — but it is dated, which a signal is
         * not.
         */
        public static final String HEARD = "HEARD";

        /** Connected on LinkedIn, per TrustLink. Weakest, and by far the most numerous. */
        public static final String CONNECTED = "CONNECTED";
    }

    /**
     * Whether this account is actually being talked to (spec §3.7).
     *
     * <p>Every counter here is about the account as a whole rather than about one person, and
     * each answers a question the owner asks in the same breath: when did we last really talk
     * to anybody, how many people is that, how many of us have any contact at all, and which
     * of the people we said matter have none.
     *
     * @param lastContactOn         the latest {@link RelationEdgeDTO#MET} edge across the
     *                              account, or null
     * @param lastContactWith       the person on that edge
     * @param lastContactBy         the colleague on that edge
     * @param peopleMet90d          distinct people with a {@code MET} edge inside 90 days
     * @param colleaguesWithContact colleagues with a {@code MET}, {@code CLAIM} or
     *                              {@code KNOWS} edge — the three that mean somebody did or
     *                              said something, as opposed to being in a channel or holding
     *                              a decade-old invitation
     * @param starredWithoutContact starred people whose tier is 4 or 5 — the white spots: we
     *                              have said they matter and we have nothing on them
     * @param quiet                 {@code SectorService.isQuiet}, the same 90-day rule the
     *                              portfolio's quiet badge uses, so the two never disagree.
     *                              Never seen counts as quiet, and exactly 90 days does not
     * @param sharing               why the calendar half may be thin
     */
    public record FreshnessDTO(
            LocalDate lastContactOn,
            String lastContactWith,
            String lastContactBy,
            int peopleMet90d,
            int colleaguesWithContact,
            int starredWithoutContact,
            boolean quiet,
            SharingDTO sharing) {
    }

    /**
     * "3 of 5 on this account share calendar metadata", and who the other two are.
     *
     * <p>Only colleagues with an Intra user count, on <b>both</b> sides: a TrustLink name that
     * matched no user has no account to consent with and would otherwise sit in the
     * denominator for ever, holding the ratio permanently below 100% with nothing anybody
     * could do about it.
     *
     * @param notSharing by name, so the owner can go and ask them (decision 9)
     */
    public record SharingDTO(int consented, int total, List<PersonDTO> notSharing) {
    }
}
