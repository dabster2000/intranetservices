package dk.trustworks.intranet.aggregates.crm.person.services;

import dk.trustworks.intranet.aggregates.crm.person.PersonNames;
import dk.trustworks.intranet.aggregates.crm.person.model.AccountPerson;
import dk.trustworks.intranet.aggregates.crm.person.model.AccountPersonBuildState;
import dk.trustworks.intranet.aggregates.crm.person.model.AccountPersonIdentity;
import dk.trustworks.intranet.aggregates.crm.person.model.enums.AccountPersonIdentityKind;
import dk.trustworks.intranet.aggregates.crm.person.model.enums.AccountPersonKind;
import dk.trustworks.intranet.aggregates.crm.person.model.enums.AccountPersonSource;
import dk.trustworks.intranet.aggregates.crm.slack.dto.SlackDigestContent;
import dk.trustworks.intranet.aggregates.crm.slack.services.AccountSlackDigestService;
import dk.trustworks.intranet.userservice.model.enums.StatusType;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Builds and maintains {@code account_person} — who we know at an account — out of the four
 * feeds that see people (spec §3.1, §3.2, §4.1, §4.3).
 *
 * <h2>What this fixes</h2>
 * Before this cut a person at a client <i>was</i> a display name, recomputed on every request.
 * Production shows what that costs: {@code "Sara Louise Vest (XSVES)"} is drawn on Banedanmark
 * as one of the client's people with 53 meetings, while "Sara Vest" — the same human, our own
 * consultant, placed there since 2025 — is drawn on the other side of the same picture
 * (defect D1). {@code "STMJ (Stephan Mosko Jensen)"} is the same on Novo Nordisk.
 * {@code "Sif S. Broby Madsen"} from a calendar and {@code "Sif Broby Madsen"} from TrustLink
 * are two different people (defect D8). A former colleague who stayed at the client — the
 * warmest contact an account can have — is shown as a stranger (defect D4).
 *
 * <p>All four are one problem: nothing owned the question "who is this, and is it somebody we
 * already know". This class owns it, once, and writes the answer down.
 *
 * <h2>The shape of a rebuild</h2>
 * <ol>
 *   <li><b>Read</b> the four sources for one client into {@link Sighting}s — one per
 *       observation, not one per person.</li>
 *   <li><b>Merge</b> the sightings into {@link PersonDraft}s with {@link #merge(List)}: a
 *       key-graph over e-mail addresses, TrustLink ids and name keys, so two sightings that
 *       share <i>any</i> key are one person, transitively.</li>
 *   <li><b>Classify</b> each draft with {@link #classify(String, ColleagueIndex, Set)} — is
 *       this one of ours, one of ours who left, or a person at the client?</li>
 *   <li><b>Upsert</b> persons and identities, stamping {@code last_seen_at}. Never delete.</li>
 * </ol>
 * Steps 2 and 3 are pure static methods over plain records precisely so the whole rule set is
 * held by the DB-free tier that gates every deploy; step 1 and step 4 are the only parts that
 * touch a database.
 *
 * <h2>It never deletes a person</h2>
 * A claim, a star, or a misclassification somebody is about to correct all have to survive a
 * source going quiet, an alias being switched off, or a mailbox losing consent.
 * {@code last_seen_at} stopping is the signal. Rows leave only through
 * {@code CrmRetentionPurgeJob} (V608).
 *
 * <h2>Transactions</h2>
 * Every database touch is inside its own {@code QuarkusTransaction.requiringNew()}, and
 * {@link #rebuildAll()} gives <b>each client its own</b>. That is not tidiness: one client's
 * duplicate-key surprise must cost that client's registry and not the night's whole pass, and
 * a rebuild triggered after a signal capture must never be able to roll the capture back —
 * which is the exact loss the signal feature exists to prevent.
 *
 * <h2>Logging</h2>
 * Counts and client uuids only. A person's name never reaches a log line, and
 * {@link #failureCodeOf(RuntimeException)} yields an exception CLASS name — a constraint
 * violation's message quotes the row, and the row here is somebody's name and address.
 */
@JBossLog
@ApplicationScoped
public class AccountPersonService {

    /** Matches {@code account_person.name VARCHAR(255)}. */
    public static final int MAX_NAME_CHARS = 255;

    /** Matches {@code account_person.name_key VARCHAR(190)}. */
    public static final int MAX_NAME_KEY_CHARS = 190;

    /** Matches {@code account_person.initials VARCHAR(16)}. */
    public static final int MAX_INITIALS_CHARS = 16;

    /** Matches {@code account_person.title VARCHAR(500)}. Real job titles are long. */
    public static final int MAX_TITLE_CHARS = 500;

    /** Matches {@code account_person.linkedin_url VARCHAR(500)}. */
    public static final int MAX_LINKEDIN_CHARS = 500;

    /** Matches {@code account_person_identity.value VARCHAR(320)} — RFC 5321's maximum. */
    public static final int MAX_IDENTITY_CHARS = 320;

    /** Matches {@code account_person_build_state.failure_code VARCHAR(40)}. */
    static final int MAX_FAILURE_CODE_CHARS = 40;

    /** The code for a failure this class has no better name for. */
    static final String FAILURE_UNEXPECTED = "UNEXPECTED";

    /**
     * Which source's spelling of a name wins when several name one person (spec §4.3).
     *
     * <p>TrustLink first because it is the person's own LinkedIn spelling; then the calendar,
     * where the LONGEST name wins because a client's Exchange writes more than we hold rather
     * than less ({@code "Sara Louise Vest"} over {@code "Sara Vest"}); then a signal, which is
     * a colleague typing what they heard; then a Slack reading, which is a model paraphrasing
     * what a colleague typed. Later entries are strictly less authoritative than earlier ones,
     * which is the only property this list has to have.
     */
    static final List<AccountPersonSource> NAME_PREFERENCE =
            List.of(AccountPersonSource.TRUSTLINK, AccountPersonSource.CALENDAR,
                    AccountPersonSource.SIGNAL, AccountPersonSource.SLACK);

    /**
     * Which source's job title wins (spec §3.1): TrustLink {@code position} first — the
     * person's own LinkedIn — then a signal's {@code person_role}, then a Slack reading's
     * role. The calendar carries no title at all and is deliberately absent.
     */
    static final List<AccountPersonSource> TITLE_PREFERENCE =
            List.of(AccountPersonSource.TRUSTLINK, AccountPersonSource.SIGNAL, AccountPersonSource.SLACK);

    @Inject
    EntityManager em;

    /**
     * Reads {@code account_slack_mention.digest_json}. Injected rather than re-implemented
     * because the column is written by that service and a second parser would drift from it.
     */
    @Inject
    AccountSlackDigestService slackDigestService;

    /**
     * The nightly sweep's kill switch. Checked in {@link #rebuildAll()} and deliberately
     * <b>not</b> in {@link #rebuild(String)}: the switch exists to stop an unattended pass
     * over every client, not to refuse a person who pressed Refresh on one account or a hook
     * that fires because somebody just captured a signal. Default {@code true} — unlike the
     * calendar and TrustLink feeds this job reads no third party and writes nothing that is
     * not already in our own database.
     */
    @ConfigProperty(name = "dk.trustworks.crm.person.rebuild.enabled", defaultValue = "true")
    boolean rebuildEnabled;

    // ------------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------------

    /**
     * Rebuilds the registry for one client.
     *
     * <p>Opens its own transactions, so it is safe to call from anywhere — including from
     * inside an existing transaction, which it will not join and therefore cannot roll back.
     * That is the property the signal hook depends on (deviation D-e).
     *
     * <p>It does <b>not</b> stamp {@code account_person_build_state}. That row is the sweep's
     * bookkeeping; if one account's refresh moved {@code last_run_at}, the recovery cron would
     * conclude the registry had been built for all 300 of the others.
     *
     * @param clientUuid the account; blank is a no-op, not an error, because every caller is
     *                   a hook and a hook must never be the thing that fails a write
     */
    public RebuildSummary rebuild(String clientUuid) {
        String client = clientUuid == null ? "" : clientUuid.trim();
        if (client.isEmpty()) {
            return RebuildSummary.ran(0, 0, 0, 0, null);
        }
        ColleagueIndex colleagues = QuarkusTransaction.requiringNew().call(this::loadColleagues);
        LocalDateTime now = LocalDateTime.now();
        return QuarkusTransaction.requiringNew().call(() -> rebuildOne(client, colleagues, now));
    }

    /**
     * Rebuilds the registry for every client, one transaction each.
     *
     * <p>One {@code now} for the whole pass, taken here, so "seen in the last run" is a query
     * anybody can write and so the tests are repeatable. The colleague directory and the
     * client list are read once, before the loop; everything else is per client.
     *
     * <p>A client whose rebuild throws costs that client only — the failure is counted, the
     * code is logged, the pass continues, and {@code last_success_at} does not move. The
     * widening gap between {@code last_run_at} and {@code last_success_at} is the alarm.
     */
    public RebuildSummary rebuildAll() {
        if (!rebuildEnabled) {
            log.info("Account person registry rebuild is switched off (dk.trustworks.crm.person.rebuild.enabled=false)");
            return RebuildSummary.disabled();
        }

        LocalDateTime now = LocalDateTime.now();
        ColleagueIndex colleagues = QuarkusTransaction.requiringNew().call(this::loadColleagues);
        List<String> clients = QuarkusTransaction.requiringNew().call(this::loadClientUuids);
        if (clients.isEmpty()) {
            log.info("Account person registry rebuild: there are no clients, nothing can be attributed");
        }

        int built = 0;
        int people = 0;
        int identities = 0;
        int failures = 0;
        String failureCode = null;

        for (String clientUuid : clients) {
            try {
                RebuildSummary one = QuarkusTransaction.requiringNew()
                        .call(() -> rebuildOne(clientUuid, colleagues, now));
                built++;
                people += one.peopleUpserted();
                identities += one.identitiesUpserted();
            } catch (RuntimeException e) {
                failures++;
                failureCode = failureCodeOf(e);
                // The code, not the message: a constraint violation quotes the row it
                // rejected, and the row here is a named human at a client.
                log.warnf("Account person rebuild failed for client %s (code=%s)", clientUuid, failureCode);
            }
        }

        RebuildSummary summary = RebuildSummary.ran(built, people, identities, failures, failureCode);
        QuarkusTransaction.requiringNew().run(() -> writeState(now, summary));
        log.infof("Account person rebuild done: status=%s clients=%d people=%d identities=%d failures=%d",
                summary.status(), summary.clientsBuilt(), summary.peopleUpserted(),
                summary.identitiesUpserted(), summary.failures());
        return summary;
    }

    /**
     * Has the registry ever been built?
     *
     * <p><b>Two states mean never-run</b> and both are handled in
     * {@link AccountPersonBuildState#hasNeverRun}: no row at all (a database restored from
     * before V604, or a seed that lost a race) and the seeded row with a null
     * {@code last_run_at}. This is the TrustLink trap repeated exactly; code that checks only
     * the column throws on the one machine where it mattered instead of rebuilding.
     *
     * <p>Read in its own transaction because its only caller is a scheduled tick, which has no
     * request context and therefore no session of its own.
     */
    public boolean hasNeverRun() {
        return QuarkusTransaction.requiringNew()
                .call(() -> AccountPersonBuildState.hasNeverRun(AccountPersonBuildState.singleton()));
    }

    /**
     * What we call the person at each known address on this account, lower-cased address to
     * name.
     *
     * <p>For {@code AccountActivityService}: a "Meeting with …" line and the people table must
     * not disagree about what somebody is called, and before the registry existed they did —
     * the feed rendered whatever the mailbox typed while the table rendered its own pick. The
     * registry is now the single answer and the feed's old {@code bestExternalNames} is the
     * fallback for an address no rebuild has reached yet.
     *
     * <p>Every kind is included, {@code COLLEAGUE} as well. This answers "what is the person
     * at this address called", which has the same answer whoever they turn out to be; deciding
     * who may be <i>shown</i> is the caller's business and not a naming question.
     */
    public Map<String, String> displayNamesByEmail(String clientUuid) {
        if (clientUuid == null || clientUuid.isBlank()) {
            return Map.of();
        }
        Query query = em.createNativeQuery("""
                select i.value, p.name
                  from account_person_identity i
                  join account_person p on p.uuid = i.person_uuid
                 where i.client_uuid = :clientUuid and i.kind = 'EMAIL'
                """);
        query.setParameter("clientUuid", clientUuid.trim());

        Map<String, String> names = new LinkedHashMap<>();
        for (Object[] row : rowsOf(query)) {
            String email = asString(row[0]);
            String name = asString(row[1]);
            if (email == null || email.isBlank() || name == null || name.isBlank()) {
                continue;
            }
            names.put(email.trim().toLowerCase(Locale.ROOT), name);
        }
        return names;
    }

    // ------------------------------------------------------------------------
    // The pure core — merge, classification, naming. No database below this line
    // until the "Reading the sources" banner.
    // ------------------------------------------------------------------------

    /**
     * One observation of a person by one source. Not one person — a person is what
     * {@link #merge(List)} makes out of several of these.
     *
     * @param source           which feed saw them
     * @param name             the parsed display name, never null or blank
     * @param initials         the client's shorthand, or null
     * @param title            a job title this source carried, or null
     * @param linkedinUrl      TrustLink's URL, or null
     * @param email            the lower-cased address this sighting came from, or null
     * @param trustlinkPersonId TrustLink's person id, or null
     * @param nameKey          {@code PersonNames.key(name)}, already truncated to the column.
     *                         Carried rather than re-derived: the person row's
     *                         {@code name_key} and its {@code NAME} identity have to be the
     *                         same 190 characters, and deriving them in two places is exactly
     *                         how they stop being that
     */
    record Sighting(AccountPersonSource source, String name, String initials, String title,
                    String linkedinUrl, String email, String trustlinkPersonId, String nameKey) {

        /** Every key this sighting can be merged on, strongest first. */
        List<Identity> identities() {
            List<Identity> identities = new ArrayList<>(3);
            if (email != null) {
                identities.add(new Identity(AccountPersonIdentityKind.EMAIL, email));
            }
            if (trustlinkPersonId != null) {
                identities.add(new Identity(AccountPersonIdentityKind.TRUSTLINK, trustlinkPersonId));
            }
            identities.add(new Identity(AccountPersonIdentityKind.NAME, nameKey));
            return identities;
        }
    }

    /** One {@code account_person_identity} row, before it has a person. */
    record Identity(AccountPersonIdentityKind kind, String value) {

        /** The map key the merge graph is built on. */
        String key() {
            return kind.name() + "|" + value;
        }
    }

    /**
     * One person, as the sources describe them, before anything is written.
     *
     * @param sources comma-joined and already in {@link AccountPersonSource} declaration order
     */
    record PersonDraft(String name, String nameKey, String initials, String title,
                       String linkedinUrl, String sources, List<Identity> identities) {
    }

    /** One Trustworks user, as the classification rule needs them. */
    record ColleagueRef(String userUuid, String fullName, boolean employedToday) {
    }

    /** What a name turned out to be, and which user backs it. */
    record Classification(AccountPersonKind kind, String userUuid) {

        /** Nobody of ours. The common case and the safe default. */
        static Classification contact() {
            return new Classification(AccountPersonKind.CONTACT, null);
        }
    }

    /**
     * Every Trustworks user a name could resolve to, indexed by {@code PersonNames.key}.
     *
     * <p>Keyed on the name key rather than scanned because the key is the gate: a person's
     * name is only ever compared with an employee whose first and last token already match it,
     * which is both the fast path and the thing that keeps the looser placement rule safe.
     * "Marianne Hansen" can never reach the employee "Anne Hansen" here — their keys are
     * {@code marianne|hansen} and {@code anne|hansen} — so the substring hole
     * {@code ColleagueDirectory} guards against with a token test cannot even be opened.
     */
    static final class ColleagueIndex {

        private record Entry(String userUuid, List<String> tokens, boolean employedToday) {
        }

        private final Map<String, List<Entry>> byKey;

        private ColleagueIndex(Map<String, List<Entry>> byKey) {
            this.byKey = byKey;
        }

        /**
         * Builds the index, skipping users with no uuid and users whose name produces no key
         * at all — a {@code user} row with neither a first nor a last name cannot be matched
         * to anybody and an empty key would match every person whose name is equally empty.
         */
        static ColleagueIndex of(Collection<ColleagueRef> refs) {
            Map<String, List<Entry>> byKey = new LinkedHashMap<>();
            if (refs != null) {
                for (ColleagueRef ref : refs) {
                    if (ref == null || ref.userUuid() == null || ref.userUuid().isBlank()) {
                        continue;
                    }
                    String key = PersonNames.key(ref.fullName());
                    List<String> tokens = PersonNames.tokens(ref.fullName());
                    if (key == null || key.isBlank() || tokens.isEmpty()) {
                        continue;
                    }
                    byKey.computeIfAbsent(key, k -> new ArrayList<>())
                            .add(new Entry(ref.userUuid().trim(), tokens, ref.employedToday()));
                }
            }
            return new ColleagueIndex(byKey);
        }

        static ColleagueIndex empty() {
            return new ColleagueIndex(Map.of());
        }

        private List<Entry> candidates(String nameKey) {
            List<Entry> found = byKey.get(nameKey);
            return found == null ? List.of() : found;
        }

        /** How many distinct name keys the index can match. Logged once per pass. */
        int size() {
            return byKey.size();
        }
    }

    /**
     * Groups sightings into people (spec §3.1).
     *
     * <p>A key-graph, not a pairwise comparison: sighting <i>i</i> and sighting <i>j</i> are
     * the same person when they share any identity, and — this is the part a pairwise rule
     * misses — when a third sighting shares one key with each of them. An address links two
     * calendar sightings that spell the name differently; a name key links either of them to a
     * TrustLink person; a TrustLink id links two companies' rows for one person. The
     * transitive closure is the person.
     *
     * <p>Union-find over sighting indices, with a map from identity key to the first sighting
     * that claimed it. Insertion order is preserved for the groups and within them, so two
     * runs over the same rows produce the same drafts in the same order — a registry whose
     * rows swap identity between two nights would move claims.
     *
     * @param sightings may be null or contain nulls
     * @return one draft per person, never null
     */
    static List<PersonDraft> merge(List<Sighting> sightings) {
        List<Sighting> real = new ArrayList<>();
        if (sightings != null) {
            for (Sighting sighting : sightings) {
                if (sighting != null) {
                    real.add(sighting);
                }
            }
        }
        int count = real.size();
        int[] parent = new int[count];
        for (int i = 0; i < count; i++) {
            parent[i] = i;
        }

        Map<String, Integer> owner = new HashMap<>();
        for (int i = 0; i < count; i++) {
            for (Identity identity : real.get(i).identities()) {
                Integer previous = owner.putIfAbsent(identity.key(), i);
                if (previous != null) {
                    union(parent, previous, i);
                }
            }
        }

        Map<Integer, List<Sighting>> groups = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            groups.computeIfAbsent(root(parent, i), key -> new ArrayList<>()).add(real.get(i));
        }

        List<PersonDraft> drafts = new ArrayList<>(groups.size());
        for (List<Sighting> group : groups.values()) {
            drafts.add(draft(group));
        }
        return drafts;
    }

    /** One group of sightings, reduced to the row we are about to write. */
    private static PersonDraft draft(List<Sighting> group) {
        Sighting naming = preferredNaming(group);

        String initials = naming.initials();
        if (initials == null) {
            for (Sighting sighting : group) {
                if (sighting.initials() != null) {
                    initials = sighting.initials();
                    break;
                }
            }
        }

        Set<AccountPersonSource> sources = EnumSet.noneOf(AccountPersonSource.class);
        for (Sighting sighting : group) {
            sources.add(sighting.source());
        }

        Map<String, Identity> identities = new LinkedHashMap<>();
        for (Sighting sighting : group) {
            for (Identity identity : sighting.identities()) {
                identities.putIfAbsent(identity.key(), identity);
            }
        }
        List<Identity> ordered = new ArrayList<>(identities.values());
        ordered.sort(Comparator.comparingInt((Identity identity) -> identity.kind().ordinal())
                .thenComparing(Identity::value));

        return new PersonDraft(naming.name(), naming.nameKey(), initials,
                bySourcePreference(group, TITLE_PREFERENCE, Sighting::title),
                bySourcePreference(group, List.of(AccountPersonSource.TRUSTLINK), Sighting::linkedinUrl),
                AccountPersonSource.join(sources), ordered);
    }

    /**
     * Which sighting's spelling of the name the person is shown under (spec §4.3).
     *
     * <p>{@link #NAME_PREFERENCE} decides the source; within the winning source a real name
     * beats a bare mailbox, and then the LONGEST name wins, because the longer spelling is the
     * more complete one — the client's Exchange writes a middle name our own records do not
     * carry, and showing the fuller name is what lets a reader recognise who is meant. Equal
     * lengths fall back to natural string order so the answer cannot depend on which mailbox
     * was read first. {@link #outranksAsName} holds both halves and says why the address test
     * has to come first.
     */
    static Sighting preferredNaming(Collection<Sighting> group) {
        for (AccountPersonSource source : NAME_PREFERENCE) {
            Sighting best = null;
            for (Sighting sighting : group) {
                if (sighting.source() != source) {
                    continue;
                }
                if (best == null || outranksAsName(sighting, best)) {
                    best = sighting;
                }
            }
            if (best != null) {
                return best;
            }
        }
        // A source added to the enum but not to NAME_PREFERENCE. Naming somebody after an
        // unranked source beats leaving a person the sources clearly saw without a name.
        return group.iterator().next();
    }

    /**
     * Is {@code candidate} the better spelling of this person than {@code best}?
     *
     * <p><b>A mailbox loses to a name before any length is compared, and that order is the
     * fix for defect D5.</b> {@code account_meeting_attendee.display_name} is nullable and
     * {@link #calendarSightings(String)} selects {@code distinct (email, display_name)}, so one
     * mailbox routinely yields TWO calendar sightings — one parsed out of a display name, one
     * out of the bare address — and both join on the same {@code EMAIL} identity into the same
     * group. Longest-wins alone then labels the person with their own mailbox:
     * {@code xhvig@bane.dk} is 13 characters and {@code Hanne Vig} is 9, so the account page
     * shows the address of the very person it was built to name. An address is never a better
     * spelling of a human than a name, however long it is; only when both spellings are the
     * same KIND does the longer one win, because the longer one is then the more complete name
     * ({@code Sara Louise Vest} over {@code Sara Vest}).
     */
    private static boolean outranksAsName(Sighting candidate, Sighting best) {
        boolean candidateIsAddress = isAddress(candidate.name());
        if (candidateIsAddress != isAddress(best.name())) {
            return !candidateIsAddress;
        }
        int byLength = Integer.compare(candidate.name().length(), best.name().length());
        return byLength > 0 || (byLength == 0 && candidate.name().compareTo(best.name()) < 0);
    }

    /**
     * Is this "name" really a mailbox — the string {@code PersonNames.parse} falls back to when
     * it can read no person out of an address? An {@code @} is the whole test: a parsed name
     * never carries one, and an address always does.
     */
    private static boolean isAddress(String name) {
        return name != null && name.indexOf('@') >= 0;
    }

    /** The first non-null value a source in {@code preference} order carried. */
    private static String bySourcePreference(Collection<Sighting> group, List<AccountPersonSource> preference,
                                             Function<Sighting, String> field) {
        for (AccountPersonSource source : preference) {
            for (Sighting sighting : group) {
                if (sighting.source() == source && field.apply(sighting) != null) {
                    return field.apply(sighting);
                }
            }
        }
        return null;
    }

    /**
     * Is this name one of ours, one of ours who left, or a person at the client (spec §3.2,
     * §4.1)?
     *
     * <p><b>This is the defect the whole cut exists to fix, so the rule is spelled out.</b> A
     * name resolves to a user when their {@code PersonNames.key} are equal <i>and</i> one of
     * two things is true:
     * <ul>
     *   <li><b>strict</b> — the employee's tokens are a contiguous run in the person's name,
     *       or the person's name is a reduction of the employee's. Exactly
     *       {@code PersonNames.containsSequence} / {@code isReductionOf}, which is exactly
     *       what {@code ColleagueDirectory} has always applied to every employee.</li>
     *   <li><b>placed</b> — that user has a placement at <b>this</b> client. Key equality
     *       already means the first and the last token match, so this is spec §4.1 rule b:
     *       {@code [sara, louise, vest, xsves]} ⊇ {@code sara … vest},
     *       {@code [stmj, stephan, mosko, jensen]} ⊇ {@code stephan … jensen}.</li>
     * </ul>
     *
     * <p><b>A key match alone is not enough and must never become enough.</b> "Lars Peter
     * Jensen" at a client where no Lars Jensen of ours has ever worked keys to
     * {@code lars|jensen} just like our Lars Jensen does, and he is a client person who must
     * stay one (spec §4.1). The placement is what separates the two cases; dropping it turns
     * every common Danish name at every account into a colleague and deletes real contacts
     * from the page with nothing to show it happened.
     *
     * <p>Then employment, asked about <b>today</b> and not about the day of any meeting:
     * employed is {@code COLLEAGUE} (never shown), not employed is {@code ALUMNI} (shown, and
     * ranked warm). That is what makes a re-hire flip back at the next rebuild (defect D2) and
     * a leaver become the warm contact they are (defect D4).
     *
     * <p>When several users share one key and more than one matches — two of ours with the
     * same first and last name — a strict match beats a placement-only one, because a token
     * match is evidence about <i>which</i> of them this is; then an employed user beats a
     * former one, because showing our own consultant as a client contact is the failure this
     * code exists to prevent; then the lower uuid, so the answer is stable.
     *
     * @param placedHere user uuids with a placement at this client; may be null
     */
    static Classification classify(String name, ColleagueIndex colleagues, Set<String> placedHere) {
        if (name == null || colleagues == null) {
            return Classification.contact();
        }
        String key = PersonNames.key(name);
        if (key == null || key.isBlank()) {
            return Classification.contact();
        }
        List<ColleagueIndex.Entry> candidates = colleagues.candidates(key);
        if (candidates.isEmpty()) {
            return Classification.contact();
        }

        List<String> personTokens = PersonNames.tokens(name);
        ColleagueIndex.Entry best = null;
        boolean bestStrict = false;
        for (ColleagueIndex.Entry candidate : candidates) {
            boolean strict = PersonNames.containsSequence(personTokens, candidate.tokens())
                    || PersonNames.isReductionOf(personTokens, candidate.tokens());
            boolean placed = placedHere != null && placedHere.contains(candidate.userUuid());
            if (!strict && !placed) {
                continue;
            }
            if (best == null || outranksAsMatch(candidate, strict, best, bestStrict)) {
                best = candidate;
                bestStrict = strict;
            }
        }
        if (best == null) {
            return Classification.contact();
        }
        return new Classification(
                best.employedToday() ? AccountPersonKind.COLLEAGUE : AccountPersonKind.ALUMNI,
                best.userUuid());
    }

    private static boolean outranksAsMatch(ColleagueIndex.Entry candidate, boolean candidateStrict,
                                           ColleagueIndex.Entry best, boolean bestStrict) {
        if (candidateStrict != bestStrict) {
            return candidateStrict;
        }
        if (candidate.employedToday() != best.employedToday()) {
            return candidate.employedToday();
        }
        return candidate.userUuid().compareTo(best.userUuid()) < 0;
    }

    /**
     * One sighting out of what a source gave, or null when there is no person in it.
     *
     * <p>Null rather than a row with an empty name: a source that hands us a blank display
     * name and a blank address has not seen anybody, and materialising a nameless person would
     * put a row on the account page that no reader could act on and no rebuild could ever
     * merge away.
     */
    static Sighting sighting(AccountPersonSource source, String displayName, String email,
                             String trustlinkPersonId, String title, String linkedinUrl) {
        PersonNames.Parsed parsed = PersonNames.parse(displayName, email);
        if (parsed == null) {
            return null;
        }
        String nameKey = trimTo(parsed.key(), MAX_NAME_KEY_CHARS);
        String name = trimTo(parsed.name(), MAX_NAME_CHARS);
        if (nameKey == null || name == null) {
            return null;
        }
        return new Sighting(source, name,
                trimTo(parsed.initials(), MAX_INITIALS_CHARS),
                trimTo(title, MAX_TITLE_CHARS),
                trimTo(linkedinUrl, MAX_LINKEDIN_CHARS),
                normaliseEmail(email),
                trimTo(trustlinkPersonId, MAX_IDENTITY_CHARS),
                nameKey);
    }

    /**
     * The address as an identity: trimmed, lower-cased with {@link Locale#ROOT}, and only when
     * it actually is one.
     *
     * <p>Graph hands the same mailbox back as {@code Mickie.Storm@ArbaSecurity.com} and
     * {@code mickie.storm@arbasecurity.com} on different events; two casings must not become
     * two people. A value with no {@code @} is not an address and is not stored as one — it
     * would be a second name key wearing the wrong kind.
     */
    static String normaliseEmail(String email) {
        if (email == null) {
            return null;
        }
        String normalised = email.trim().toLowerCase(Locale.ROOT);
        if (normalised.isEmpty() || !normalised.contains("@")) {
            return null;
        }
        return trimTo(normalised, MAX_IDENTITY_CHARS);
    }

    /** Trimmed and cut to the column's width; blank becomes null. */
    static String trimTo(String raw, int maxChars) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() > maxChars ? trimmed.substring(0, maxChars) : trimmed;
    }

    /**
     * Was this person one of ours on this date?
     *
     * <p>The latest status on or before the date wins; on a tie the NON-{@code TERMINATED} row
     * wins, because a rehire and a termination are routinely filed on the same day and reading
     * it the other way marks somebody who came back as gone. {@code TERMINATED} and
     * {@code PREBOARDING} both mean "not one of ours", and so does having no status row at or
     * before the date at all. Ported from {@code ColleagueDirectory.wasEmployedOn} on purpose:
     * two spellings of "is this person employed" would drift, and the drift shows up as our
     * own consultants reappearing on an account as its client contacts.
     */
    static boolean employedOn(List<StatusPoint> statuses, LocalDate date) {
        if (statuses == null || date == null) {
            return false;
        }
        StatusType effective = statuses.stream()
                .filter(point -> point.from() != null && !point.from().isAfter(date))
                .max(Comparator.comparing(StatusPoint::from)
                        .thenComparing(point -> point.status() == StatusType.TERMINATED ? 0 : 1))
                .map(StatusPoint::status)
                .orElse(null);
        return effective != null
                && effective != StatusType.TERMINATED
                && effective != StatusType.PREBOARDING;
    }

    /** One {@code userstatus} row, reduced to the two columns the employment rule reads. */
    record StatusPoint(StatusType status, LocalDate from) {
    }

    /**
     * A CODE for the build-state row: the exception's class name, capped at the column width.
     *
     * <p>Never {@code getMessage()}. A duplicate-key violation's message quotes the values it
     * rejected, and the values here are a person's name and e-mail address; a
     * {@code VARCHAR(40)} in a table anybody can read is not where those belong.
     */
    static String failureCodeOf(RuntimeException e) {
        if (e == null) {
            return FAILURE_UNEXPECTED;
        }
        String name = e.getClass().getSimpleName();
        if (name == null || name.isBlank()) {
            return FAILURE_UNEXPECTED;
        }
        return name.length() > MAX_FAILURE_CODE_CHARS ? name.substring(0, MAX_FAILURE_CODE_CHARS) : name;
    }

    private static void union(int[] parent, int a, int b) {
        int rootA = root(parent, a);
        int rootB = root(parent, b);
        if (rootA != rootB) {
            // The lower index wins so the group's representative is its first sighting and the
            // group order below is the order the sources were read in.
            parent[Math.max(rootA, rootB)] = Math.min(rootA, rootB);
        }
    }

    private static int root(int[] parent, int index) {
        int current = index;
        while (parent[current] != current) {
            parent[current] = parent[parent[current]];
            current = parent[current];
        }
        return current;
    }

    // ------------------------------------------------------------------------
    // Writing one client
    // ------------------------------------------------------------------------

    /**
     * Where one client's rebuild lands its writes, and the seam that lets the fast tier watch
     * the ORDER it lands them in.
     *
     * <p>Not an abstraction for its own sake. Two database constraints reject a rebuild whose
     * statements arrive in the wrong order — {@code fk_account_person_identity} and
     * {@code uq_account_person_client_key}, both documented on {@link #applyDrafts} — and a
     * rejected statement costs that client's whole registry for the night. Panache's
     * {@code persist()} needs a container and a session, so without this interface the one
     * property that matters could only be exercised by a {@code @QuarkusTest} against a real
     * MariaDB, which is not the tier that gates a deploy. With it, the order is held by the
     * DB-free run.
     */
    interface RegistryWriter {

        /** Queue an insert or an update of this person. */
        void write(AccountPerson person);

        /** Queue an insert or an update of this identity. */
        void write(AccountPersonIdentity identity);

        /**
         * Everything queued so far reaches the database NOW, and therefore strictly before
         * anything queued after this call.
         */
        void flush();
    }

    /**
     * The production writer: Panache's {@code persist} and the session's own {@code flush}.
     *
     * <p>{@code persist()} on an entity the session already manages is a no-op by design —
     * the UPDATE comes out of dirty checking at flush — so one method serves both an insert
     * and an update and no caller has to know which it is looking at.
     */
    private record PersistingWriter(EntityManager em) implements RegistryWriter {

        @Override
        public void write(AccountPerson person) {
            person.persist();
        }

        @Override
        public void write(AccountPersonIdentity identity) {
            identity.persist();
        }

        @Override
        public void flush() {
            em.flush();
        }
    }

    /**
     * One client's registry as it stands, indexed the three ways the upsert asks about it.
     *
     * <p>Read once per client so the loop below is map lookups rather than a query per draft,
     * and <b>mutated as the run proceeds</b>: a name key a draft vacates has to stop being a
     * survivor candidate the moment it is vacated, and a person a draft creates has to become
     * one.
     */
    record Registry(Map<String, AccountPerson> personsByNameKey,
                    Map<String, AccountPerson> personsByUuid,
                    Map<String, AccountPersonIdentity> identitiesByKey) {

        static Registry of(Collection<AccountPerson> persons, Collection<AccountPersonIdentity> identities) {
            Map<String, AccountPerson> byNameKey = new HashMap<>();
            Map<String, AccountPerson> byUuid = new HashMap<>();
            for (AccountPerson person : persons) {
                byNameKey.put(person.getNameKey(), person);
                byUuid.put(person.getUuid(), person);
            }
            Map<String, AccountPersonIdentity> byIdentityKey = new HashMap<>();
            for (AccountPersonIdentity identity : identities) {
                byIdentityKey.put(identity.getKind().name() + "|" + identity.getValue(), identity);
            }
            return new Registry(byNameKey, byUuid, byIdentityKey);
        }

        static Registry empty() {
            return new Registry(new HashMap<>(), new HashMap<>(), new HashMap<>());
        }
    }

    /** A person the run has written, and the identities that still have to point at them. */
    private record PersonWrite(String personUuid, List<Identity> identities) {
    }

    /**
     * The whole of one client's rebuild, inside the caller's transaction.
     *
     * <p>Reads what the client already has, then hands the drafts to {@link #applyDrafts},
     * which owns every rule about what is written and — the part that is easy to lose — in
     * what order.
     */
    RebuildSummary rebuildOne(String clientUuid, ColleagueIndex colleagues, LocalDateTime now) {
        List<PersonDraft> drafts = merge(readSightings(clientUuid));
        Set<String> placedHere = readPlacements(clientUuid);
        Registry registry = Registry.of(AccountPerson.listForClient(clientUuid),
                AccountPersonIdentity.listForClient(clientUuid));
        return applyDrafts(clientUuid, drafts, colleagues, placedHere, registry, now, new PersistingWriter(em));
    }

    /**
     * Writes one client's drafts, in the only order the two indexes will accept.
     *
     * <h2>Why the order is spelled out and not left to the flush</h2>
     * A single end-of-transaction flush cannot produce it, and the reason is in Hibernate
     * rather than in this code:
     * <ul>
     *   <li><b>{@code hibernate.order_inserts=true}</b> (application.yml) means the
     *       {@code InsertActionSorter} re-groups queued inserts by entity name before they are
     *       emitted, and it derives its dependencies ONLY from {@code EntityType} /
     *       {@code CollectionType} / {@code ComponentType} properties.
     *       {@code AccountPersonIdentity.personUuid} is a plain {@code @Column String} and not
     *       a {@code @ManyToOne}, so <b>no dependency is recorded</b>: let one identity insert
     *       be queued before the first person insert and the whole identity group is emitted
     *       first, MariaDB answers error 1452 on {@code fk_account_person_identity}, and the
     *       client's entire rebuild is lost. Hence pass one writes every person, then
     *       {@link RegistryWriter#flush()}, and only then the identities.</li>
     *   <li><b>Inserts run before updates</b> in every flush, whatever the order they were
     *       queued in. A draft that takes a name key another row is being updated AWAY from
     *       would therefore insert while the old row still holds the key —
     *       {@code uq_account_person_client_key}, error 1062, the same total loss. The key is
     *       only freed in {@link Registry#personsByNameKey()} until somebody flushes, so this
     *       flushes the moment a draft wants a key that is free in the map and still taken in
     *       the database. {@code hibernate.order_updates=true} sorts updates by primary key,
     *       so waiting for the two statements to happen to fall the right way round is not a
     *       plan either.</li>
     * </ul>
     * Both rules cost a handful of extra round trips on a client whose people actually moved,
     * and nothing at all on one whose people did not.
     *
     * <p>Static and free of the {@code EntityManager} on purpose: everything it decides is
     * decided from its arguments, so the DB-free tier can drive it with a recording
     * {@link RegistryWriter} and assert the order rather than trusting a comment about it.
     */
    static RebuildSummary applyDrafts(String clientUuid, List<PersonDraft> drafts, ColleagueIndex colleagues,
                                      Set<String> placedHere, Registry registry, LocalDateTime now,
                                      RegistryWriter writer) {
        Set<String> claimed = new HashSet<>();
        // Name keys this run has freed in the map but NOT yet in the database. Cleared by
        // every flush, because a flush is exactly the moment the database catches up.
        Set<String> vacatedKeys = new HashSet<>();
        // Loser person uuid -> the row it merged into, for the identity pass below.
        Map<String, String> mergedAway = new LinkedHashMap<>();
        List<PersonWrite> written = new ArrayList<>(drafts.size());
        int people = 0;
        int identities = 0;

        for (PersonDraft draft : drafts) {
            AccountPerson person = survivorFor(draft, registry, claimed);
            if (person == null && registry.personsByNameKey().containsKey(draft.nameKey())) {
                // Two drafts wanting one name key cannot happen — distinct groups hold
                // distinct NAME identities and the chosen key is always one of the group's —
                // so this is a corrupted read, not a case to guess at. Skip the draft rather
                // than take the unique-key violation down with the whole client.
                log.warnf("Account person rebuild: two people want one name key on client %s — skipping one", clientUuid);
                continue;
            }
            if (person == null) {
                person = new AccountPerson();
                person.setUuid(UUID.randomUUID().toString());
                person.setClientUuid(clientUuid);
                person.setFirstSeenAt(now);
            } else if (person.getNameKey() != null && !person.getNameKey().equals(draft.nameKey())) {
                // The person is keeping their row but changing key — the old key must stop
                // being a survivor candidate or a later draft would try to take this row too.
                registry.personsByNameKey().remove(person.getNameKey());
                vacatedKeys.add(person.getNameKey());
            }

            if (vacatedKeys.contains(draft.nameKey())) {
                // This draft is about to take a key an earlier draft only freed in the map.
                // The UPDATE that frees it has to reach MariaDB before the write that takes
                // it, or uq_account_person_client_key rejects one of them and the client's
                // whole rebuild goes with it. Drafts are processed in order and a key is only
                // ever taken after it was vacated, so flushing here is enough: the pending
                // batch can never hold both halves of the same key at once.
                writer.flush();
                vacatedKeys.clear();
            }

            Classification classification = classify(draft.name(), colleagues, placedHere);
            person.setName(draft.name());
            person.setNameKey(draft.nameKey());
            person.setInitials(draft.initials());
            person.setTitle(draft.title());
            person.setKind(classification.kind());
            person.setAlumniUserUuid(classification.userUuid());
            person.setLinkedinUrl(draft.linkedinUrl());
            person.setSources(draft.sources());
            person.setLastSeenAt(now);
            writer.write(person);
            people++;

            claimed.add(person.getUuid());
            registry.personsByNameKey().put(person.getNameKey(), person);
            registry.personsByUuid().put(person.getUuid(), person);

            retireMergedAway(draft, person, registry, claimed, mergedAway, writer);
            written.add(new PersonWrite(person.getUuid(), draft.identities()));
        }

        // Every person is now IN the database, so every identity below has a parent row to
        // point at. The javadoc above says what order_inserts does without this line.
        writer.flush();

        repointMergedAway(mergedAway, registry, writer);
        for (PersonWrite write : written) {
            for (Identity identity : write.identities()) {
                upsertIdentity(clientUuid, write.personUuid(), identity, now, registry, writer);
                identities++;
            }
        }

        return RebuildSummary.ran(1, people, identities, 0, null);
    }

    /**
     * Retires the rows this draft turned out to be the same person as (spec §3.1, defect D8).
     *
     * <p>When two existing rows collapse into one draft, {@link #survivorFor} keeps one of
     * them and the other is left behind. The rebuild never deletes, so before this existed the
     * loser kept its old name key and went on being selected by the relationships tab: a
     * duplicate person, forever, with no edges, tier 5 and 0 meetings — D8 surviving in the
     * one place the registry was built to end it.
     *
     * <p><b>Retired, not deleted, and the difference is the whole point.</b> A claim or a star
     * on the loser has to survive (spec §3.1), and {@code account_relation_claim} and
     * {@code client_plan_stakeholder} both point at the uuid. So the row stays and its
     * {@code sources} is emptied — "no feed backs this row any more", which no live person can
     * ever be, since every draft carries at least one source. {@code last_seen_at} is
     * deliberately NOT stamped: the loser was last seen whenever it was last seen, and moving
     * it forward would tell the retention purge this row is current.
     *
     * <p>The loser is marked {@code claimed} so nothing later in the same run can revive it
     * half-written — a row cannot be both "merged into S" and "alive as T" in one pass without
     * the identity move below sending T's keys to S. A draft that wanted the retired row's
     * name key is therefore skipped for the night by the guard in {@link #applyDrafts}, which
     * is the safe half of that trade and self-healing: the loser's identities have moved to
     * the survivor by then, so tomorrow nothing points at it, nothing retires it, and the
     * draft takes the row.
     */
    private static void retireMergedAway(PersonDraft draft, AccountPerson survivor, Registry registry,
                                         Set<String> claimed, Map<String, String> mergedAway,
                                         RegistryWriter writer) {
        for (Identity identity : draft.identities()) {
            AccountPersonIdentity row = registry.identitiesByKey().get(identity.key());
            if (row == null || row.getPersonUuid() == null) {
                continue;
            }
            String loserUuid = row.getPersonUuid();
            if (loserUuid.equals(survivor.getUuid()) || claimed.contains(loserUuid)) {
                continue;
            }
            AccountPerson loser = registry.personsByUuid().get(loserUuid);
            if (loser == null) {
                continue;
            }
            loser.setSources(AccountPerson.RETIRED_SOURCES);
            claimed.add(loserUuid);
            mergedAway.put(loserUuid, survivor.getUuid());
            writer.write(loser);
        }
    }

    /**
     * Moves everything a retired row still owned onto the row it merged into.
     *
     * <p>The draft's own identities are re-pointed by {@link #upsertIdentity} anyway; these
     * are the ones no current source produced — an address the client stopped using, a
     * spelling that has gone out of the calendar. Left on the retired row they resolve, on
     * every read, to a person the page no longer draws, so an old spelling in a signal or a
     * Slack day would silently stop drawing its edge. Moved, they resolve to the survivor and
     * the next rebuild finds them there, which is what stops the same two rows merging and
     * retiring each other night after night.
     *
     * <p>Runs after the person flush because it writes a {@code person_uuid} that may belong
     * to a row inserted moments ago, and before the draft identities so that a draft's own
     * assignment is the one that stands. They are not counted as upserts: the counter answers
     * "how many identities did the sources produce", and these are the ones they did not.
     */
    private static void repointMergedAway(Map<String, String> mergedAway, Registry registry,
                                          RegistryWriter writer) {
        if (mergedAway.isEmpty()) {
            return;
        }
        for (AccountPersonIdentity row : registry.identitiesByKey().values()) {
            String survivorUuid = mergedAway.get(row.getPersonUuid());
            if (survivorUuid == null) {
                continue;
            }
            row.setPersonUuid(survivorUuid);
            writer.write(row);
        }
    }

    /**
     * Which existing row this draft is, or null when it is somebody new.
     *
     * <p>The name key wins. {@code UNIQUE (client_uuid, name_key)} is the merge rule's
     * backbone, so a row already holding the key <b>is</b> this person by definition, and
     * taking any other row would mean writing a duplicate key. Only when no row holds the key
     * do the identities decide, and then the OLDEST candidate wins — a person who has been on
     * the account for two years keeps their uuid, and therefore their claims and their star,
     * when a second identity arrives and turns out to be theirs.
     *
     * <p>A row another draft has already taken this run is never offered again. Two drafts can
     * legitimately point at one old row when a bridge between them has disappeared — an alias
     * switched off takes the TrustLink sighting that linked two addresses — and the second of
     * them must become its own person rather than overwrite the first.
     */
    private static AccountPerson survivorFor(PersonDraft draft, Registry registry, Set<String> claimed) {
        Map<String, AccountPerson> personsByNameKey = registry.personsByNameKey();
        Map<String, AccountPerson> personsByUuid = registry.personsByUuid();
        Map<String, AccountPersonIdentity> identitiesByKey = registry.identitiesByKey();

        AccountPerson byKey = personsByNameKey.get(draft.nameKey());
        if (byKey != null && !claimed.contains(byKey.getUuid())) {
            return byKey;
        }
        if (byKey != null) {
            return null;
        }

        AccountPerson oldest = null;
        Set<String> seen = new LinkedHashSet<>();
        for (Identity identity : draft.identities()) {
            AccountPersonIdentity row = identitiesByKey.get(identity.key());
            if (row == null || row.getPersonUuid() == null || !seen.add(row.getPersonUuid())) {
                continue;
            }
            AccountPerson candidate = personsByUuid.get(row.getPersonUuid());
            if (candidate == null || claimed.contains(candidate.getUuid())) {
                continue;
            }
            if (oldest == null || olderThan(candidate, oldest)) {
                oldest = candidate;
            }
        }
        return oldest;
    }

    private static boolean olderThan(AccountPerson candidate, AccountPerson best) {
        LocalDateTime candidateSeen = candidate.getFirstSeenAt();
        LocalDateTime bestSeen = best.getFirstSeenAt();
        if (candidateSeen == null || bestSeen == null) {
            return candidateSeen != null;
        }
        int byDate = candidateSeen.compareTo(bestSeen);
        return byDate < 0 || (byDate == 0 && candidate.getUuid().compareTo(best.getUuid()) < 0);
    }

    /**
     * Writes one identity, pointing it at this person.
     *
     * <p>Always re-points {@code person_uuid}: an identity that used to belong to one row and
     * now belongs to another is the merge actually happening, and it has to be an UPDATE of
     * the row the unique key already holds rather than a delete and an insert that would race
     * it. {@code first_seen_at} is set once and never moves.
     */
    private static void upsertIdentity(String clientUuid, String personUuid, Identity identity, LocalDateTime now,
                                       Registry registry, RegistryWriter writer) {
        AccountPersonIdentity row = registry.identitiesByKey().get(identity.key());
        if (row == null) {
            row = new AccountPersonIdentity();
            row.setUuid(AccountPersonIdentity.deterministicUuid(clientUuid, identity.kind(), identity.value()));
            row.setClientUuid(clientUuid);
            row.setKind(identity.kind());
            row.setValue(identity.value());
            row.setFirstSeenAt(now);
            registry.identitiesByKey().put(identity.key(), row);
        }
        row.setPersonUuid(personUuid);
        row.setLastSeenAt(now);
        writer.write(row);
    }

    /**
     * Stamps the singleton. Two-phase on purpose: {@code last_run_at} always,
     * {@code last_success_at} only on a pass with zero failed clients, so the recovery cron
     * can tell "it ran and got nowhere" from "it has never run".
     */
    void writeState(LocalDateTime now, RebuildSummary summary) {
        AccountPersonBuildState state = AccountPersonBuildState.singleton();
        if (state == null) {
            // The V604 seed is missing — a database restored from before it, or a seed that
            // lost a race. Create the row rather than throwing; hasNeverRun() already reads
            // the absence correctly and this is the run that fixes it.
            state = new AccountPersonBuildState();
            state.setId(AccountPersonBuildState.SINGLETON_ID);
        }
        state.setLastRunAt(now);
        if (summary.failures() == 0) {
            state.setLastSuccessAt(now);
        }
        state.setClientsBuilt(summary.clientsBuilt());
        state.setPeopleUpserted(summary.peopleUpserted());
        state.setIdentitiesUpserted(summary.identitiesUpserted());
        state.setFailures(summary.failures());
        state.setFailureCode(summary.failureCode());
        state.persist();
    }

    // ------------------------------------------------------------------------
    // Reading the sources
    // ------------------------------------------------------------------------

    /** Every sighting of a person at one client, from all four feeds, in source order. */
    private List<Sighting> readSightings(String clientUuid) {
        List<Sighting> sightings = new ArrayList<>();
        sightings.addAll(calendarSightings(clientUuid));
        sightings.addAll(trustLinkSightings(clientUuid));
        sightings.addAll(signalSightings(clientUuid));
        sightings.addAll(slackSightings(clientUuid));
        return sightings;
    }

    /**
     * The client's own people on meetings our consenting mailboxes saw.
     *
     * <p>{@code account_meeting_attendee} already holds only external attendees on a matched
     * client domain — the calendar sync dropped rooms, our own people and delivery meetings on
     * the way in — so this needs no filter of its own beyond the join back to the meeting for
     * its client.
     */
    private List<Sighting> calendarSightings(String clientUuid) {
        Query query = em.createNativeQuery("""
                select distinct a.email, a.display_name
                  from account_meeting_attendee a
                  join account_meeting m on m.uuid = a.meeting_uuid
                 where m.client_uuid = :clientUuid
                """);
        query.setParameter("clientUuid", clientUuid);

        List<Sighting> sightings = new ArrayList<>();
        for (Object[] row : rowsOf(query)) {
            add(sightings, sighting(AccountPersonSource.CALENDAR, asString(row[1]), asString(row[0]),
                    null, null, null));
        }
        return sightings;
    }

    /**
     * TrustLink's tier-5 people at this client.
     *
     * <p><b>The three-condition alias join is load-bearing and must never be simplified.</b>
     * The TrustLink sync never deletes, so switching an alias off in the editor is the only
     * control anybody has over which strangers stay attached to an account. Reading
     * {@code trustlink_connection} on {@code client_uuid} alone — the obvious shortcut —
     * permanently strands every person a since-disabled alias ever contributed, with nothing
     * on the page saying where they came from. The join relies on {@code utf8mb4_general_ci}
     * making {@code a.company_name = c.company_name} case-insensitive on both sides.
     */
    private List<Sighting> trustLinkSightings(String clientUuid) {
        Query query = em.createNativeQuery("""
                select distinct c.person_id, c.full_name, c.position, c.linkedin_url
                  from trustlink_connection c
                  join trustlink_company_alias a on a.client_uuid = c.client_uuid
                                                and a.company_name = c.company_name
                                                and a.enabled = 1
                 where c.client_uuid = :clientUuid
                """);
        query.setParameter("clientUuid", clientUuid);

        List<Sighting> sightings = new ArrayList<>();
        for (Object[] row : rowsOf(query)) {
            add(sightings, sighting(AccountPersonSource.TRUSTLINK, asString(row[1]), null,
                    asString(row[0]), asString(row[2]), asString(row[3])));
        }
        return sightings;
    }

    /** People a colleague named when they captured a signal about this account. */
    private List<Sighting> signalSightings(String clientUuid) {
        Query query = em.createNativeQuery("""
                select distinct s.person_name, s.person_role
                  from account_signal s
                 where s.client_uuid = :clientUuid
                   and s.person_name is not null
                   and trim(s.person_name) <> ''
                """);
        query.setParameter("clientUuid", clientUuid);

        List<Sighting> sightings = new ArrayList<>();
        for (Object[] row : rowsOf(query)) {
            add(sightings, sighting(AccountPersonSource.SIGNAL, asString(row[0]), null,
                    null, asString(row[1]), null));
        }
        return sightings;
    }

    /**
     * People a Slack day's validated reading named, out of {@code digest_json}.
     *
     * <p>Dismissed mentions are excluded: a dismissal is somebody saying the day was not about
     * this client, and the graph reader has always filtered them at read time.
     *
     * <p>A reading that will not parse is skipped, not thrown on. The column is TEXT written
     * by a model and edited by nobody, but "edited by nobody" is a convention, and a
     * hand-corrected row that lost a brace must cost that day's people rather than that
     * client's whole registry. {@code fromJson} already answers null and logs it.
     *
     * <p>The uuid is selected alongside the JSON only to force a two-column result: a
     * single-column native query hands back a {@code List<Object>} rather than a
     * {@code List<Object[]>} and the cast below would fail on the first row.
     */
    private List<Sighting> slackSightings(String clientUuid) {
        Query query = em.createNativeQuery("""
                select m.uuid, m.digest_json
                  from account_slack_mention m
                 where m.client_uuid = :clientUuid and m.dismissed_at is null
                """);
        query.setParameter("clientUuid", clientUuid);

        List<Sighting> sightings = new ArrayList<>();
        for (Object[] row : rowsOf(query)) {
            SlackDigestContent content = slackDigestService.fromJson(asString(row[1]));
            if (content == null || content.clientPeople() == null) {
                continue;
            }
            for (SlackDigestContent.Person person : content.clientPeople()) {
                if (person == null) {
                    continue;
                }
                add(sightings, sighting(AccountPersonSource.SLACK, person.name(), null,
                        null, person.role(), null));
            }
        }
        return sightings;
    }

    /**
     * Every user with a placement at this client — the set spec §4.1 rule b is confined to.
     *
     * <p>Two ways a placement is recorded, and both count. A {@code contract_consultants} row
     * is the formal one, read with <b>no status and no date predicate</b>: the question is
     * "has this person ever sat at this client", not "are they there this morning", and a
     * BUDGET or CLOSED contract is still a record that they were. A row in
     * {@code crm_colleague_client_email} is the informal one — an address the calendar sync
     * learned belongs to one of ours at a client domain — and its client comes from the
     * domain, because that table stores whose an address is and not where.
     */
    private Set<String> readPlacements(String clientUuid) {
        Set<String> placed = new LinkedHashSet<>();

        Query contracts = em.createNativeQuery("""
                select distinct cc.useruuid
                  from contract_consultants cc
                  join contracts ct on ct.uuid = cc.contractuuid
                 where cc.useruuid is not null and ct.clientuuid = :clientUuid
                """);
        contracts.setParameter("clientUuid", clientUuid);
        placed.addAll(stringColumn(contracts));

        Query learned = em.createNativeQuery("""
                select distinct e.user_uuid
                  from crm_colleague_client_email e
                  join client_domain d on d.domain = substring_index(e.email, '@', -1)
                 where d.client_uuid = :clientUuid
                """);
        learned.setParameter("clientUuid", clientUuid);
        placed.addAll(stringColumn(learned));

        return placed;
    }

    /**
     * Every employee's name and whether they are one of ours today.
     *
     * <p>Terminated people are read too, and that is the point: a former colleague at the
     * client is {@code ALUMNI}, which is a thing to show warmly, and a directory that dropped
     * leavers could only ever answer {@code CONTACT} for them.
     *
     * <p>Read once per pass in {@link #rebuildAll()}. {@link #rebuild(String)} reads it again
     * for its single client, which is a whole-directory query for one account; that is the
     * price of a hook that must not depend on a cache being warm, and the directory is
     * hundreds of rows, not millions.
     */
    ColleagueIndex loadColleagues() {
        Query query = em.createNativeQuery("""
                select u.uuid, u.firstname, u.lastname, s.status, s.statusdate
                  from user u
                  join userstatus s on s.useruuid = u.uuid
                 where s.status is not null
                   and s.statusdate is not null
                """);

        Map<String, String> namesByUuid = new LinkedHashMap<>();
        Map<String, List<StatusPoint>> statusesByUuid = new LinkedHashMap<>();
        for (Object[] row : rowsOf(query)) {
            String userUuid = asString(row[0]);
            StatusType status = toStatusType(asString(row[3]));
            LocalDate statusDate = toLocalDate(row[4]);
            if (userUuid == null || status == null || statusDate == null) {
                continue;
            }
            String first = asString(row[1]) == null ? "" : asString(row[1]);
            String last = asString(row[2]) == null ? "" : asString(row[2]);
            namesByUuid.putIfAbsent(userUuid, (first + " " + last).trim());
            statusesByUuid.computeIfAbsent(userUuid, key -> new ArrayList<>())
                    .add(new StatusPoint(status, statusDate));
        }

        LocalDate today = LocalDate.now();
        List<ColleagueRef> refs = new ArrayList<>(namesByUuid.size());
        for (Map.Entry<String, String> entry : namesByUuid.entrySet()) {
            refs.add(new ColleagueRef(entry.getKey(), entry.getValue(),
                    employedOn(statusesByUuid.get(entry.getKey()), today)));
        }
        ColleagueIndex index = ColleagueIndex.of(refs);
        log.infof("Account person rebuild: colleague directory loaded, nameKeys=%d", index.size());
        return index;
    }

    /** Every client, in no particular order. The pass rebuilds all of them. */
    List<String> loadClientUuids() {
        return stringColumn(em.createNativeQuery("select uuid from client"));
    }

    // ------------------------------------------------------------------------
    // The run summary
    // ------------------------------------------------------------------------

    /**
     * What one pass did.
     *
     * <p>The status leads, because every counter behind it is zero under two quite different
     * circumstances — the switch is off, or the pass ran and found nothing — and only the
     * status says which one this was.
     *
     * @param failureCode the CODE of the last client that failed, or null; never a message
     */
    public record RebuildSummary(Status status, int clientsBuilt, int peopleUpserted,
                                 int identitiesUpserted, int failures, String failureCode) {

        public enum Status {
            /** The pass ran, whatever it found. */
            RAN,
            /** {@code dk.trustworks.crm.person.rebuild.enabled} is false; nothing was read. */
            DISABLED
        }

        public static RebuildSummary ran(int clientsBuilt, int peopleUpserted, int identitiesUpserted,
                                         int failures, String failureCode) {
            return new RebuildSummary(Status.RAN, clientsBuilt, peopleUpserted, identitiesUpserted,
                    failures, failureCode);
        }

        public static RebuildSummary disabled() {
            return new RebuildSummary(Status.DISABLED, 0, 0, 0, 0, null);
        }
    }

    // ------------------------------------------------------------------------
    // Driver quirks
    // ------------------------------------------------------------------------

    private static void add(List<Sighting> sightings, Sighting sighting) {
        if (sighting != null) {
            sightings.add(sighting);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Object[]> rowsOf(Query query) {
        return query.getResultList();
    }

    /**
     * The first column of every row as a trimmed, non-blank string.
     *
     * <p>Handles both shapes a native query can return: a one-column select hands back the
     * values themselves, a wider one hands back {@code Object[]}. Writing the cast for only
     * one of them is a {@code ClassCastException} waiting for whoever adds a column.
     */
    private static List<String> stringColumn(Query query) {
        List<?> rows = query.getResultList();
        List<String> values = new ArrayList<>(rows.size());
        for (Object row : rows) {
            Object value = row instanceof Object[] columns ? (columns.length == 0 ? null : columns[0]) : row;
            String text = asString(value);
            if (text != null && !text.isBlank()) {
                values.add(text.trim());
            }
        }
        return values;
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    /**
     * An unknown status value is not a status. New enum constants land in the database before
     * the code that knows them, and a {@code valueOf} blowing up here would take a whole
     * nightly pass with it.
     */
    private static StatusType toStatusType(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return StatusType.valueOf(value.trim());
        } catch (IllegalArgumentException e) {
            log.warnf("Account person rebuild: unknown user status '%s' ignored", value);
            return null;
        }
    }

    /** MariaDB hands a DATE back as {@code java.sql.Date} and a DATETIME as a {@code Timestamp}. */
    static LocalDate toLocalDate(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate();
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime().toLocalDate();
        }
        if (value instanceof LocalDate date) {
            return date;
        }
        if (value instanceof LocalDateTime dateTime) {
            return dateTime.toLocalDate();
        }
        return null;
    }
}
