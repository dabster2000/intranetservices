package dk.trustworks.intranet.aggregates.crm.retention.services;

import dk.trustworks.intranet.aggregates.crm.account.dto.AccountActivityDTO;
import dk.trustworks.intranet.aggregates.crm.account.services.AccountActivityService;
import dk.trustworks.intranet.aggregates.crm.retention.CrmRetentionFeatureFlag;
import dk.trustworks.intranet.aggregates.crm.retention.CrmRetentionParameters;
import dk.trustworks.intranet.aggregates.crm.retention.dto.CrmRetentionPurgeRunDTO;
import dk.trustworks.intranet.aggregates.crm.retention.model.CrmRetentionPurgeRun;
import dk.trustworks.intranet.aggregates.crm.retention.model.enums.CrmPurgeStatus;
import dk.trustworks.intranet.aggregates.crm.retention.model.enums.CrmPurgeTrigger;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.context.ManagedExecutor;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The 24-month CRM retention purge (spec §7) — the job eight migration headers and nine
 * entity javadocs have been promising since V584.
 *
 * <h2>The rule</h2>
 * Third-party personal data goes 24 months after the account last saw activity.
 * {@link #RETENTION_MONTHS} is a compiled constant and not a setting: it is the signed-off
 * policy every one of those headers quotes, and the moment it becomes an admin toggle every
 * sentence in the schema that says "24 months" becomes a statement about nothing. The cap
 * and the arming flag are the tunable parts, and they live in
 * {@link CrmRetentionParameters} and {@link CrmRetentionFeatureFlag}.
 *
 * <h2>Two switches, and the run this ships in</h2>
 * {@code dk.trustworks.crm.retention.purge.enabled} (default {@code true}) stops
 * {@code CrmRetentionPurgeJob} from starting at all — the lever for stopping the job without
 * a deploy. {@code app_settings.crm.retention.purge.enabled}, seeded {@code 'false'} by V608,
 * is what arms the destruction, and <b>flipping that row is the moment automatic deletion
 * starts</b>. A run that fires while it is off deletes nothing and closes its row
 * {@link CrmPurgeStatus#STOPPED}, because a night with no row at all reads exactly like a
 * night the scheduler never fired.
 *
 * <h2>The clock, and why it does not feed on itself</h2>
 * "Last activity" starts at {@link AccountActivityService#lastActivityForAll()} — the same
 * answer the accounts list shows, so the two can never disagree about whether an account is
 * cold — and is then taken as the <b>later</b> of that and the human clock: the newest
 * {@code account_relation_claim} and the newest {@code client_plan_stakeholder} on the
 * account. That fold is not decoration. Neither of those two tables is one of the ten sources
 * {@code lastActivityForAll()} unions, so without it a colleague could claim a person or star
 * a stakeholder on a long-quiet account this afternoon and this job would erase the claim
 * tonight, and every following night. Only when every one of those is silent does the account
 * fall back to its own registry rows' {@code last_seen_at}.
 *
 * <p>There is a trap in that clock and the table choice in §7 is what defuses it. Five of
 * the ten sources {@code lastActivityForAll} unions are themselves tables a purge could
 * empty, and emptying one moves the account's last-activity date <em>backwards</em>, which
 * makes more rows eligible on the next run: a self-feeding cascade. So the purge keeps the
 * dated skeletons. {@code account_meeting} rows stay and only their attendees go;
 * {@code account_signal} rows are redacted in place and keep their {@code created_at};
 * {@code client_note} is out of scope entirely. The one clock source this job does delete
 * from is {@code account_slack_mention}, and a Slack mention is never an account's <em>only</em>
 * activity of the last two years by the time it is 24 months stale.
 *
 * <h2>Eligibility is one method, used by the job and by the dry run</h2>
 * {@link #eligible(LocalDate)} is called by both, so the preview cannot lie about an
 * irreversible action — the rule the employee-documents retention lane states out loud and
 * the reason its eligibility sits in a shared service. It also carries the rule that keeps
 * the cap moving: an account is eligible only while it still <em>has</em> something to
 * erase, so the same ten oldest accounts cannot occupy every night's cap for ever.
 *
 * <h2>Per-account failures are caught, never thrown</h2>
 * One account that throws is logged, counted and skipped, and tomorrow's run finds it again
 * because eligibility counts what is still there rather than remembering what was tried. An
 * exception that escaped would abandon every remaining account and leave the run row open.
 *
 * <p><b>What is logged about a failure is the exception's type, never its message.</b> This
 * job's exceptions are SQL errors about the very rows it is erasing, and a constraint or
 * data error can echo a row's value verbatim — writing that into CloudWatch would file the
 * personal data the job exists to destroy somewhere with a longer retention than the table
 * it came from. Same reason {@code failure_code} is a code.
 *
 * <h2>Transactions</h2>
 * <b>No {@code @Transactional} on the entry points.</b> The sweep is minutes of work over
 * whole-table reads and a transaction spanning it would hold a pooled connection throughout.
 * Every write is its own {@link QuarkusTransaction#requiringNew()}, and the unit is <b>one
 * account</b>: the five statements for a subject commit together or not at all, which is the
 * rule {@code RecruitmentAnonymizerService} follows for the same reason — half-erased is a
 * state nobody can reason about, and the retry is free.
 *
 * <p>{@link ManagedExecutor} propagates the JTA context of the thread that submits, so
 * {@link #runAsync} snapshots the run uuid as a plain {@code String} and submits only after
 * the opening transaction has committed and closed.
 *
 * <h2>The run lock</h2>
 * An {@link AtomicBoolean}, not {@code ConcurrentExecution.SKIP} alone: SKIP guards the
 * scheduler's own invocations and cannot see a run started through
 * {@code POST /crm/retention/purge}, so without this an engineer pressing the button at
 * 03:31 would race the nightly one onto the same rows. It is an {@code AtomicBoolean} and
 * not a {@code ReentrantLock} because the manual path takes it on the request thread and
 * releases it on a worker.
 */
@JBossLog
@ApplicationScoped
public class CrmRetentionPurgeService {

    /**
     * The window, in months, from an account's last activity to the erasure of the
     * third-party personal data held about it.
     *
     * <p><b>Compiled on purpose.</b> Spec §3.4/§7 and eight migration headers quote this
     * number; {@code RecruitmentGdprParameters} draws the same line around its own 6-month
     * retention: policy constants are changed by a reviewed code change, never by an admin
     * toggle.
     */
    public static final int RETENTION_MONTHS = 24;

    /**
     * What is left where a signal's free text was.
     *
     * <p>{@code account_signal.signal_text} is {@code NOT NULL}, so the erasure primitive has
     * to be a placeholder rather than a null — the same reason the recruitment anonymiser
     * carries {@code [anonymized]}. The three structured columns beside it are genuinely
     * nulled.
     */
    public static final String REDACTED_SIGNAL_TEXT = "[redacted 24 m]";

    /** How long a purge run is worth keeping. Past a quarter nobody is asking about that night. */
    public static final int RUN_RETENTION_DAYS = 90;

    /** The runs endpoint shows a handful, not a history. */
    public static final int DEFAULT_LIMIT = 5;
    public static final int MAX_LIMIT = 50;

    /** The run threw outside the per-account handling. */
    public static final String FAILURE_UNEXPECTED = "UNEXPECTED";

    /** The executor refused the work — the run never started and must not be left open. */
    static final String FAILURE_SUBMIT_REJECTED = "SUBMIT_REJECTED";

    /** At least one account threw and was skipped. A code, deliberately saying no more. */
    static final String FAILURE_ACCOUNT = "ACCOUNT_FAILED";

    /** The TrustLink staleness sweep threw. The accounts above it had already committed. */
    static final String FAILURE_TRUSTLINK = "TRUSTLINK_FAILED";

    // ------------------------------------------------------------------------
    // SQL
    //
    // Native, because five of these tables have no entity in this package and two of the
    // columns (client_plan_stakeholder.person_uuid, account_person_identity.value) belong to
    // tables this cut is adding beside it — a purge that compiled against its neighbours'
    // entities would break the day one of them is renamed, which is not a risk worth taking
    // for a job whose whole value is that it keeps running. Everything is parameter-bound;
    // the only thing concatenated into a statement is PERSON_KEPT_BY_TRUSTLINK, a compiled
    // constant, and never a value.
    // ------------------------------------------------------------------------

    /**
     * Whether a TrustLink connection still backs a person row.
     *
     * <p>The one predicate that decides whether an {@code account_person} row survives, and
     * it is a constant rather than two similar strings precisely so the count the preview
     * shows and the delete the run performs can never drift apart. An account_person carries
     * a {@code TRUSTLINK} identity whose value is {@code trustlink_connection.person_id};
     * while TrustLink is still returning that person, deleting the row would only have it
     * re-mirrored tomorrow night.
     *
     * <p>{@code value} is back-quoted. It is not a MariaDB reserved word today and the column
     * is spelled that way in V604, but it is close enough to {@code VALUES} that quoting it
     * costs nothing and removes the question.
     */
    private static final String PERSON_KEPT_BY_TRUSTLINK = """
            exists (select 1
                      from account_person_identity i
                      join trustlink_connection t
                        on t.client_uuid = i.client_uuid and t.person_id = i.`value`
                     where i.person_uuid = p.uuid and i.kind = 'TRUSTLINK')
            """;

    /** Calendar PII rows include unresolved candidates and their review decisions (V621). */
    private static final String COUNT_MEETING_ATTENDEES = """
            select client_uuid, count(*) from (
                select m.client_uuid from account_meeting_attendee a
                  join account_meeting m on m.uuid=a.meeting_uuid
                union all select client_uuid from account_calendar_candidate
                union all select client_uuid from account_calendar_review
            ) calendar_pii group by client_uuid
            """;

    private static final String COUNT_SIGNALS = """
            select client_uuid, count(*)
              from account_signal
             where person_name is not null
                or person_role is not null
                or relation_text is not null
                or signal_text <> :redacted
             group by client_uuid
            """;

    private static final String COUNT_SLACK_MENTIONS = """
            select client_uuid, count(*)
              from account_slack_mention
             group by client_uuid
            """;

    // The break before the predicate is load-bearing: a text block strips trailing white
    // space from every line, so "where not " followed by a concatenation would read
    // "where notexists".
    private static final String COUNT_PEOPLE = """
            select p.client_uuid, count(*)
              from account_person p
             where not
            """ + PERSON_KEPT_BY_TRUSTLINK + """
             group by p.client_uuid
            """;

    private static final String COUNT_STAKEHOLDERS = """
            select client_uuid, count(*)
              from client_plan_stakeholder
             where name is not null or person_uuid is not null
             group by client_uuid
            """;

    /**
     * The fallback clock, for an account {@code lastActivityForAll()} has never heard of.
     *
     * <p>Meeting attendees ride on {@code account_meeting}, which is the CALENDAR source;
     * signals are the SIGNAL source; mentions are a SLACK source — an account holding any of
     * those already has a last-activity date. A registry person can exist without any of them,
     * and then the row's own date is the only clock there is.
     *
     * <p>{@code client_plan_stakeholder} used to have a fallback entry here too. It does not
     * need one any more: {@link #HUMAN_CLOCK_STAKEHOLDERS} reads the same table at a strictly
     * newer date ({@code greatest(created_at, validated_at)} ≥ {@code created_at}) and at a
     * higher priority, so anything the fallback could have contributed the human clock has
     * already contributed.
     */
    private static final String CLOCK_PEOPLE = """
            select client_uuid, max(last_seen_at)
              from account_person
             group by client_uuid
            """;

    // ------------------------------------------------------------------------
    // The human clock
    //
    // THE BUG THESE TWO QUERIES FIX, written out because it erased data every night:
    // lastActivityForAll() unions ten sources and NEITHER of the two tables this cut lets
    // people write BY HAND is one of them. account_relation_claim is invisible to it because
    // AccountRelationshipClaimService logs a claim under entity_type = 'CLIENT' with a field
    // name of its own, and the RELATIONSHIP source selects field_name = 'type' specifically
    // (that filter is deliberate — see that service's javadoc — so the answer is not to widen
    // it and start rendering "Became a customer" for every claim). client_plan_stakeholder is
    // invisible to it because there is no plan source at all. Both used to reach eligibility
    // only as a NULL-ONLY FALLBACK, consulted when the account had no activity of any kind —
    // which is precisely never for the accounts this matters on. So on an account whose last
    // SOURCE activity was more than 24 months ago, a colleague could file a claim or star a
    // person this afternoon and the 03:30 sweep would delete it tonight, and again tomorrow
    // night, and the colleague would have no way to tell why their work kept vanishing.
    //
    // These are folded in with max() against lastActivityForAll(), not behind it: a human
    // touching the account IS activity on the account, and it is the kind the retention clock
    // exists to respect.
    //
    // GREATEST RETURNS NULL IF ANY ARGUMENT IS NULL in MariaDB, which on a two-column
    // greatest() would turn one NULL date into "this account has no human clock". Both column
    // pairs are NOT NULL by DDL today (V605, V586), so the coalesce pairs below are belt and
    // braces — but they are the cheap kind, and the expensive kind of wrong here is an
    // irreversible delete. coalesce(a,b)/coalesce(b,a) yields the non-null one when exactly
    // one is set, and NULL only when both are, which max() then skips.
    // ------------------------------------------------------------------------

    /**
     * When somebody last said out loud that they know a person at this account.
     *
     * <p>{@code updated_at} as well as {@code claimed_at}: a claim moved from "met once" to
     * "trusted" this morning is today's work even though the row was first written in 2023.
     */
    static final String HUMAN_CLOCK_CLAIMS = """
            select client_uuid,
                   max(greatest(coalesce(claimed_at, updated_at), coalesce(updated_at, claimed_at)))
              from account_relation_claim
             group by client_uuid
            """;

    /**
     * When somebody last put a person in the account plan, or confirmed the seat is still right.
     *
     * <p>{@code validated_at} is the one that moves — the plan's whole point is that a
     * stakeholder row is re-confirmed rather than left to rot — so a clock that read only
     * {@code created_at} would call a seat somebody validated last week two years stale.
     */
    static final String HUMAN_CLOCK_STAKEHOLDERS = """
            select client_uuid,
                   max(greatest(coalesce(created_at, validated_at), coalesce(validated_at, created_at)))
              from client_plan_stakeholder
             group by client_uuid
            """;

    private static final String COUNT_STALE_TRUSTLINK = """
            select count(*) from trustlink_connection where last_seen_at < :cutoff
            """;

    /**
     * Attendees only. The {@code account_meeting} rows stay: a dated count with no third
     * party left in it is not personal data, and keeping it is what stops the retention
     * clock walking backwards (see the class javadoc).
     */
    private static final String DELETE_MEETING_ATTENDEES = """
            delete from account_meeting_attendee
             where meeting_uuid in (select uuid from account_meeting where client_uuid = :clientUuid)
            """;

    /**
     * All four person-bearing columns, not just the obvious one.
     *
     * <p>{@code person_name} is where a reader looks, but the same human is named again in
     * {@code relation_text} ("Hans knows Benny Hoffmann from school") and a third time in the
     * verbatim {@code signal_text}. Nulling only the first leaves the name in the row twice.
     * {@code account_signal_colleague} is deliberately untouched — those are our own people,
     * not third parties.
     */
    private static final String REDACT_SIGNALS = """
            update account_signal
               set person_name = null,
                   person_role = null,
                   relation_text = null,
                   signal_text = :redacted
             where client_uuid = :clientUuid
               and (person_name is not null
                    or person_role is not null
                    or relation_text is not null
                    or signal_text <> :redacted)
            """;

    /** Participants follow on the foreign key's cascade — one delete here, not two. */
    private static final String DELETE_SLACK_MENTIONS = """
            delete from account_slack_mention where client_uuid = :clientUuid
            """;

    /** Identities and claims follow on their cascades. */
    private static final String DELETE_PEOPLE = """
            delete p from account_person p
             where p.client_uuid = :clientUuid
               and not
            """ + PERSON_KEPT_BY_TRUSTLINK;

    /**
     * The plan keeps the seat, loses the person.
     *
     * <p>{@code person_uuid} has no foreign key (the staging sync carries
     * {@code client_plan_stakeholder} and not {@code account_person}, and an FK between them
     * would fail that sync on every run), so nothing nulls it for us when the person row goes.
     * This statement is the only thing that does, which is why it names both columns.
     */
    private static final String CLEAR_STAKEHOLDERS = """
            update client_plan_stakeholder
               set name = null, person_uuid = null
             where client_uuid = :clientUuid
               and (name is not null or person_uuid is not null)
            """;

    /** Trustworkers follow on the cascade. */
    private static final String DELETE_STALE_TRUSTLINK = """
            delete from trustlink_connection where last_seen_at < :cutoff
            """;

    @Inject
    EntityManager em;

    @Inject
    AccountActivityService accountActivityService;

    @Inject
    CrmRetentionFeatureFlag featureFlag;

    @Inject
    CrmRetentionParameters parameters;

    @Inject
    ManagedExecutor managedExecutor;

    /** Held for the whole of a run, across the hand-off from the request thread to the worker. */
    private final AtomicBoolean running = new AtomicBoolean(false);

    // ------------------------------------------------------------------------
    // What the job and the preview both see
    // ------------------------------------------------------------------------

    /**
     * One account past the retention threshold, and what is still held about it.
     *
     * @param lastActivityOn the clock this account was judged by — the later of its newest
     *                       activity of any kind and the newest thing a colleague wrote about
     *                       it by hand (a claim, a plan stakeholder), or, when it has neither,
     *                       its own registry rows' dates. See {@link #lastActivityOf}.
     * @param purgeAfter     {@code lastActivityOn} + 24 months, i.e. the day it became eligible
     */
    public record PurgeCandidate(
            String clientUuid,
            LocalDate lastActivityOn,
            LocalDate purgeAfter,
            long meetingAttendees,
            long signals,
            long slackMentions,
            long people,
            long stakeholders) {

        /** Rows this account still holds that the purge would touch. */
        public long outstanding() {
            return meetingAttendees + signals + slackMentions + people + stakeholders;
        }
    }

    /**
     * What one run did, or would have done.
     *
     * @param disarmed the {@code app_settings} row was off: nothing was deleted and the run
     *                 closes {@link CrmPurgeStatus#STOPPED}
     */
    public record PurgeSummary(
            boolean disarmed,
            int accountsConsidered,
            int accountsPurged,
            int meetingAttendees,
            int signalsRedacted,
            int slackMentions,
            int peopleDeleted,
            int stakeholdersCleared,
            int trustlinkConnections,
            int failures,
            String failureCode) {

        /** The run fired while unarmed. Every counter zero, and the status says which zero it is. */
        static PurgeSummary switchedOff() {
            return new PurgeSummary(true, 0, 0, 0, 0, 0, 0, 0, 0, 0, null);
        }
    }

    /**
     * Every account whose third-party data is past the 24-month window and still has
     * something left to erase, oldest last-activity first.
     *
     * <p><b>One method, two callers</b> — the nightly job and the dry run both reach the
     * purge through it, so the preview is not a second implementation that can drift from
     * what the job would actually do.
     *
     * <p>Two rules are worth reading twice. First, the candidate set is built from the
     * <em>outstanding-row counts</em>, so an account that has already been swept drops out
     * and stops occupying a slot under the nightly cap — without that the same ten oldest
     * accounts would be re-swept every night and the eleventh would never be reached.
     * Second, an account whose clock cannot be established at all is <b>not</b> eligible: an
     * unknown date is not an old one, and erasure is irreversible.
     *
     * @param today the day to judge against, injected rather than read, so the arithmetic is
     *              testable without a clock
     */
    public List<PurgeCandidate> eligible(LocalDate today) {
        Map<String, Long> attendees = countByClient(COUNT_MEETING_ATTENDEES, false);
        Map<String, Long> signals = countByClient(COUNT_SIGNALS, true);
        Map<String, Long> mentions = countByClient(COUNT_SLACK_MENTIONS, false);
        Map<String, Long> people = countByClient(COUNT_PEOPLE, false);
        Map<String, Long> stakeholders = countByClient(COUNT_STAKEHOLDERS, false);

        Set<String> clients = new LinkedHashSet<>();
        clients.addAll(attendees.keySet());
        clients.addAll(signals.keySet());
        clients.addAll(mentions.keySet());
        clients.addAll(people.keySet());
        clients.addAll(stakeholders.keySet());
        if (clients.isEmpty()) {
            return List.of();
        }

        // All three clocks are read HERE, once, before purge() deletes anything — and none of
        // them is re-read while the sweep runs. That ordering is load-bearing: the claim clock
        // reads a table this job empties (claims cascade from account_person), so a clock
        // re-read mid-run would see the rows this run has just deleted, walk the account's date
        // backwards and make more accounts eligible inside the same run. It cannot feed on
        // itself ACROSS runs either, for the reason the candidate set is built from outstanding
        // counts rather than from dates: a swept account has nothing left to erase and drops
        // out before its now-older clock is ever consulted again.
        Map<String, LocalDate> activity = new HashMap<>(lastActivityDates());
        mergeNewest(activity, "select client_uuid, max(occurred_at) from account_calendar_candidate group by client_uuid");
        Map<String, LocalDate> human = humanClock();
        Map<String, LocalDate> fallback = fallbackClock();

        List<PurgeCandidate> candidates = new ArrayList<>();
        for (String clientUuid : clients) {
            LocalDate lastActivity = lastActivityOf(clientUuid, activity, human, fallback);
            if (!isPastRetention(lastActivity, today)) {
                continue;
            }
            candidates.add(new PurgeCandidate(
                    clientUuid,
                    lastActivity,
                    lastActivity.plusMonths(RETENTION_MONTHS),
                    attendees.getOrDefault(clientUuid, 0L),
                    signals.getOrDefault(clientUuid, 0L),
                    mentions.getOrDefault(clientUuid, 0L),
                    people.getOrDefault(clientUuid, 0L),
                    stakeholders.getOrDefault(clientUuid, 0L)));
        }
        candidates.sort(Comparator.comparing(PurgeCandidate::lastActivityOn)
                .thenComparing(PurgeCandidate::clientUuid));
        return candidates;
    }

    /**
     * One account's clock: the newest of everything that counts as the account being alive.
     *
     * <p><b>Read the two halves of this in the right order or the bug comes back.</b> The
     * source clock ({@code lastActivityForAll()}) and the human clock are folded together with
     * "whichever is later" — a claim or a stakeholder seat is <em>activity</em>, not a
     * consolation prize for an account that has none. Only when BOTH are silent does the
     * fallback (a registry row's own {@code last_seen_at}) get a say, and only then because
     * there is genuinely nothing else to go on.
     *
     * <p>The earlier shape put the human tables behind the source clock as a null-only
     * fallback, which reads almost the same and behaves completely differently: an account with
     * any activity at all — every account that has ever had a meeting — never reached the
     * fallback, so a claim filed today on a long-quiet account was invisible to the purge and
     * was deleted that night. A null-only fallback is the right shape for a clock that only
     * ever <em>substitutes</em>; it is the wrong shape for one that can be the newest thing
     * that happened.
     *
     * <p>Pure and static so the ordering above is provable in the fast tier without a database.
     */
    static LocalDate lastActivityOf(String clientUuid,
                                    Map<String, LocalDate> sourceClock,
                                    Map<String, LocalDate> humanClock,
                                    Map<String, LocalDate> fallbackClock) {
        LocalDate newest = newest(sourceClock.get(clientUuid), humanClock.get(clientUuid));
        return newest != null ? newest : fallbackClock.get(clientUuid);
    }

    /** The later of two dates, either of which may be absent. Absent loses; both absent is absent. */
    static LocalDate newest(LocalDate left, LocalDate right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left.isAfter(right) ? left : right;
    }

    /**
     * The day an account has to have gone quiet before to be eligible.
     *
     * <p>Strictly before: an account whose last activity is exactly 24 months ago today is
     * kept one more day. On an irreversible action the boundary belongs on the side of not
     * deleting.
     */
    static LocalDate cutoff(LocalDate today) {
        return today.minusMonths(RETENTION_MONTHS);
    }

    /** Whether one account's clock has run out. A null clock never has — see {@link #eligible}. */
    static boolean isPastRetention(LocalDate lastActivity, LocalDate today) {
        return lastActivity != null && lastActivity.isBefore(cutoff(today));
    }

    /**
     * The blast-radius cap, applied to an already oldest-first list.
     *
     * <p>A non-positive cap is treated as one rather than as "no limit". {@link
     * CrmRetentionParameters} never produces one, and if it somehow did, the reading that
     * erases the whole backlog is not the one to guess at.
     */
    static List<PurgeCandidate> withinCap(List<PurgeCandidate> eligible, int cap) {
        int safeCap = Math.max(1, cap);
        return eligible.size() <= safeCap ? eligible : eligible.subList(0, safeCap);
    }

    /**
     * The status a finished run gets.
     *
     * <p>{@code failures} being non-zero still closes a run {@link CrmPurgeStatus#DONE}: the
     * sweep reached the end, the accounts it could not erase are unchanged, and tomorrow
     * finds them again. The one ending that is not ordinary is the disarmed one, and a null
     * summary still closes the row — leaving it {@code RUNNING} would turn a run that did
     * nothing into one that looks like it is still going.
     */
    static CrmPurgeStatus statusOf(PurgeSummary summary) {
        return summary != null && summary.disarmed() ? CrmPurgeStatus.STOPPED : CrmPurgeStatus.DONE;
    }

    // ------------------------------------------------------------------------
    // Running
    // ------------------------------------------------------------------------

    /**
     * Whether a run holds the lock right now.
     *
     * <p>The cheap, racy answer — two callers a millisecond apart both read {@code false}.
     * Good enough for rendering a disabled button; {@link #runAsync} gives the race-free one,
     * which is why the resource's 409 comes from there and not from here.
     */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * The nightly sweep. Takes the lock, opens the row, works, closes the row.
     *
     * <p>A tick that finds the lock held stands down without opening a row: the run already
     * in flight is the one that will be recorded, and a second row saying "somebody else was
     * busy" would only be noise in a table read to answer whether the policy is being kept.
     */
    public void nightly() {
        if (!running.compareAndSet(false, true)) {
            log.info("CRM retention purge: a run is already in flight — the nightly tick stands down");
            return;
        }
        String runUuid = null;
        try {
            runUuid = start(CrmPurgeTrigger.SCHEDULED, null, false).getUuid();
            PurgeSummary summary = purge(false);
            close(runUuid, statusOf(summary), summary, summary.failureCode());
        } catch (RuntimeException e) {
            // The type, never the message: a SQL error here quotes the row being erased.
            log.errorf("CRM retention purge run %s failed: %s", runUuid, e.getClass().getSimpleName());
            if (runUuid != null) {
                close(runUuid, CrmPurgeStatus.FAILED, null, FAILURE_UNEXPECTED);
            }
        } finally {
            running.set(false);
        }
    }

    /**
     * Starts a purge in the background and hands back the row to poll.
     *
     * <p>Asynchronous because the ALB cuts a request at sixty seconds while a first armed run
     * over a real backlog is minutes of deletes — a synchronous trigger would report a
     * timeout for a run that went perfectly, and the caller could not tell that from one that
     * died half way through an irreversible sweep.
     *
     * <p>The lock is taken <b>before</b> the submit, so a double-click is refused while the
     * caller is still looking at it rather than discovered minutes later by a worker that
     * then silently does nothing. The returned record is a snapshot of the row as just
     * opened; only its uuid crosses to the worker.
     *
     * @return the opened run, or empty when a run was already in flight
     */
    public Optional<CrmRetentionPurgeRunDTO> runAsync(String actor) {
        if (!running.compareAndSet(false, true)) {
            log.info("CRM retention purge: a run is already in flight — the manual trigger was refused");
            return Optional.empty();
        }

        CrmRetentionPurgeRunDTO opened;
        String runUuid;
        try {
            CrmRetentionPurgeRun run = start(CrmPurgeTrigger.MANUAL, actor, false);
            runUuid = run.getUuid();
            opened = CrmRetentionPurgeRunDTO.from(run);
        } catch (RuntimeException e) {
            running.set(false);
            throw e;
        }

        try {
            // start()'s transaction has committed and closed by now. Submitting with one open
            // would carry the request thread's pooled connection onto the worker for the run.
            managedExecutor.submit(() -> {
                try {
                    PurgeSummary summary = purge(false);
                    close(runUuid, statusOf(summary), summary, summary.failureCode());
                } catch (RuntimeException e) {
                    log.errorf("CRM retention purge run %s failed: %s",
                            runUuid, e.getClass().getSimpleName());
                    close(runUuid, CrmPurgeStatus.FAILED, null, FAILURE_UNEXPECTED);
                } finally {
                    // After the row is closed, never before: the lock has to look held until
                    // there is nothing left RUNNING for the next caller to collide with.
                    running.set(false);
                }
            });
        } catch (RuntimeException e) {
            close(runUuid, CrmPurgeStatus.FAILED, null, FAILURE_SUBMIT_REJECTED);
            running.set(false);
            log.errorf("CRM retention purge could not be submitted to the executor: %s",
                    e.getClass().getSimpleName());
            throw e;
        }
        return Optional.of(opened);
    }

    /**
     * What a run would erase, computed now and returned now.
     *
     * <p>Synchronous because it is a handful of grouped counts rather than a sweep, and
     * because the answer is only useful in front of the person deciding whether to arm the
     * job. It writes its own run row and nothing else — {@code dry_run = 1} is on that row so
     * a later reader can never mistake a preview's counts for rows that are gone.
     *
     * <p><b>The arming flag is deliberately not consulted.</b> A preview is exactly what
     * somebody needs before arming, and refusing to produce one while disarmed would leave
     * the only way to find out what the purge does being to let it do it. It takes no lock
     * either: nothing it does can collide.
     */
    public CrmRetentionPurgeRunDTO dryRun(String actor) {
        CrmRetentionPurgeRun run = start(CrmPurgeTrigger.MANUAL, actor, true);
        PurgeSummary summary;
        try {
            summary = purge(true);
        } catch (RuntimeException e) {
            log.errorf("CRM retention purge dry run %s failed: %s",
                    run.getUuid(), e.getClass().getSimpleName());
            close(run.getUuid(), CrmPurgeStatus.FAILED, null, FAILURE_UNEXPECTED);
            throw e;
        }
        CrmRetentionPurgeRun closed = close(run.getUuid(), CrmPurgeStatus.DONE, summary, summary.failureCode());
        return CrmRetentionPurgeRunDTO.from(closed == null ? run : closed);
    }

    /**
     * The newest runs, newest first, for {@code GET /crm/retention/runs}.
     *
     * <p>No transaction: one indexed read off {@code idx_crm_retention_purge_run_started}.
     */
    public List<CrmRetentionPurgeRun> latest(int limit) {
        int capped = Math.min(Math.max(limit, 1), MAX_LIMIT);
        return CrmRetentionPurgeRun.find("order by startedAt desc")
                .page(0, capped)
                .list();
    }

    // ------------------------------------------------------------------------
    // The sweep
    // ------------------------------------------------------------------------

    /**
     * The work itself, for a real run and for a preview.
     *
     * <p>The two share every line above the point where one calls a delete and the other adds
     * up what the delete would have removed. That is the whole point: a preview assembled by
     * a second code path is a claim about an irreversible action that nobody has checked.
     */
    private PurgeSummary purge(boolean dryRun) {
        if (!dryRun && !featureFlag.isPurgeArmed()) {
            log.warn("CRM retention purge: crm.retention.purge.enabled is off — nothing was deleted");
            return PurgeSummary.switchedOff();
        }

        LocalDate today = LocalDate.now();
        List<PurgeCandidate> allEligible = eligible(today);
        List<PurgeCandidate> batch = withinCap(allEligible, parameters.nightlyAccountCap());

        int accountsPurged = 0;
        long attendees = 0;
        long signals = 0;
        long mentions = 0;
        long people = 0;
        long stakeholders = 0;
        int failures = 0;
        String failureCode = null;

        for (PurgeCandidate candidate : batch) {
            if (dryRun) {
                attendees += candidate.meetingAttendees();
                signals += candidate.signals();
                mentions += candidate.slackMentions();
                people += candidate.people();
                stakeholders += candidate.stakeholders();
                accountsPurged++;
                continue;
            }
            try {
                long[] erased = purgeAccount(candidate.clientUuid());
                attendees += erased[0];
                signals += erased[1];
                mentions += erased[2];
                people += erased[3];
                stakeholders += erased[4];
                accountsPurged++;
            } catch (RuntimeException e) {
                // Counted and skipped, never rethrown: one account must not abandon the rest,
                // and nothing about this account changed, so tomorrow's run finds it again.
                log.errorf("CRM retention purge: account %s failed and was skipped (%s)",
                        candidate.clientUuid(), e.getClass().getSimpleName());
                failures++;
                failureCode = FAILURE_ACCOUNT;
            }
        }

        long trustlink;
        try {
            trustlink = sweepStaleTrustLink(today, dryRun);
        } catch (RuntimeException e) {
            log.errorf("CRM retention purge: the TrustLink staleness sweep failed (%s)",
                    e.getClass().getSimpleName());
            trustlink = 0;
            failures++;
            failureCode = FAILURE_TRUSTLINK;
        }

        log.infof("CRM retention purge %s: considered=%d purged=%d attendees=%d signals=%d "
                        + "mentions=%d people=%d stakeholders=%d trustlink=%d failures=%d",
                dryRun ? "DRY RUN" : "swept", allEligible.size(), accountsPurged,
                attendees, signals, mentions, people, stakeholders, trustlink, failures);

        return new PurgeSummary(false, allEligible.size(), accountsPurged,
                toInt(attendees), toInt(signals), toInt(mentions), toInt(people),
                toInt(stakeholders), toInt(trustlink), failures, failureCode);
    }

    /**
     * Erases one account, in one transaction.
     *
     * <p>One subject, one commit: the five statements land together or not at all, which is
     * the rule the recruitment anonymiser follows. Half an account erased is a state nobody
     * can reason about afterwards, and the retry costs nothing because every statement here
     * is idempotent — the deletes have nothing left to match, and the two updates carry a
     * predicate that excludes rows already cleared.
     *
     * <p>Nothing sweeps a cascade child by hand. {@code account_meeting_attendee} is reached
     * through its meetings on purpose, but {@code account_slack_mention_participant},
     * {@code account_person_identity}, {@code account_relation_claim} and
     * {@code trustlink_connection_trustworker} all follow their parents on {@code ON DELETE
     * CASCADE} — V593 says in as many words that those foreign keys exist so this job does
     * not have to remember two tables.
     *
     * <p>Four tables are <b>not</b> here and each absence is deliberate. {@code client_note}
     * is out of scope: a note's third party is named inside free text with no structured
     * column, so the only erasure primitive that works on it is deleting the row, and
     * deleting the one hand-typed source in the account timeline is a decision this cut did
     * not take (spec §7, §12). {@code account_signal_colleague}, {@code
     * crm_colleague_client_email}, {@code user_calendar_consent}, the TrustLink aliases and
     * the trustworker map are our own people and our own configuration — employee data, never
     * purged by a third-party retention rule.
     *
     * @return the five counts, in the order the summary adds them up
     */
    private long[] purgeAccount(String clientUuid) {
        return QuarkusTransaction.requiringNew().call(() -> {
            long attendees = execute(DELETE_MEETING_ATTENDEES, Map.of("clientUuid", clientUuid));
            attendees += execute("delete from account_calendar_candidate where client_uuid=:clientUuid",
                    Map.of("clientUuid", clientUuid));
            attendees += execute("delete from account_calendar_review where client_uuid=:clientUuid",
                    Map.of("clientUuid", clientUuid));
            long signals = execute(REDACT_SIGNALS,
                    Map.of("clientUuid", clientUuid, "redacted", REDACTED_SIGNAL_TEXT));
            long mentions = execute(DELETE_SLACK_MENTIONS, Map.of("clientUuid", clientUuid));
            long people = execute(DELETE_PEOPLE, Map.of("clientUuid", clientUuid));
            long stakeholders = execute(CLEAR_STAKEHOLDERS, Map.of("clientUuid", clientUuid));
            log.infof("CRM retention purge: account %s — attendees=%d signals=%d mentions=%d "
                            + "people=%d stakeholders=%d",
                    clientUuid, attendees, signals, mentions, people, stakeholders);
            return new long[]{attendees, signals, mentions, people, stakeholders};
        });
    }

    /**
     * TrustLink connections nobody upstream is returning any more.
     *
     * <p><b>Staleness, not account inactivity</b>, and the difference is the whole reason this
     * sweep is outside the per-account loop. A connection deleted because its account went
     * quiet would simply be re-mirrored by the nightly TrustLink sync, so an account-based
     * rule here is not a policy, it is a nightly churn. {@code last_seen_at} older than 24
     * months means TrustLink itself has stopped listing the person under that company, which
     * is the moment our copy stops having a source. The trustworker edges follow on the
     * cascade.
     */
    private long sweepStaleTrustLink(LocalDate today, boolean dryRun) {
        LocalDateTime cutoff = cutoff(today).atStartOfDay();
        if (dryRun) {
            Query query = em.createNativeQuery(COUNT_STALE_TRUSTLINK);
            query.setParameter("cutoff", cutoff);
            return asLong(query.getSingleResult());
        }
        long deleted = QuarkusTransaction.requiringNew()
                .call(() -> execute(DELETE_STALE_TRUSTLINK, Map.of("cutoff", cutoff)));
        if (deleted > 0) {
            log.infof("CRM retention purge: deleted %d TrustLink connections last seen before %s",
                    deleted, cutoff.toLocalDate());
        }
        return deleted;
    }

    // ------------------------------------------------------------------------
    // The run row
    // ------------------------------------------------------------------------

    /**
     * Opens a run row before any work is done.
     *
     * <p>The returned entity is detached — the transaction that wrote it has committed. It is
     * a handle for {@link #close}, which re-reads by uuid; nothing should mutate it.
     */
    private CrmRetentionPurgeRun start(CrmPurgeTrigger trigger, String actor, boolean dryRun) {
        CrmRetentionPurgeRun run = new CrmRetentionPurgeRun();
        run.setUuid(UUID.randomUUID().toString());
        run.setTriggerKind(trigger);
        // Only a manual run has anybody behind it; a scheduled one is nobody's, and a
        // placeholder in the only column that records who asked would make it a liar.
        run.setStartedBy(trigger == CrmPurgeTrigger.MANUAL ? actor : null);
        run.setStartedAt(LocalDateTime.now());
        run.setStatus(CrmPurgeStatus.RUNNING);
        run.setDryRun(dryRun);
        QuarkusTransaction.requiringNew().run(run::persist);
        log.infof("CRM retention purge run %s opened: trigger=%s dryRun=%s",
                run.getUuid(), trigger, dryRun);
        return run;
    }

    /**
     * Writes the ending, then forgets the runs nobody will ask about again.
     *
     * <p>The row is re-read by uuid rather than the caller's handle being mutated: that handle
     * came out of a transaction that committed long ago, and on the manual path it crossed a
     * thread as well.
     *
     * @return the closed row, detached, or null when it had vanished
     */
    private CrmRetentionPurgeRun close(String runUuid, CrmPurgeStatus status,
                                       PurgeSummary summary, String failureCode) {
        CrmRetentionPurgeRun closed = QuarkusTransaction.requiringNew().call(() -> {
            CrmRetentionPurgeRun row = CrmRetentionPurgeRun.findById(runUuid);
            if (row == null) {
                return null;
            }
            row.setStatus(status);
            row.setFinishedAt(LocalDateTime.now());
            if (summary != null) {
                row.setAccountsConsidered(summary.accountsConsidered());
                row.setAccountsPurged(summary.accountsPurged());
                row.setMeetingAttendees(summary.meetingAttendees());
                row.setSignalsRedacted(summary.signalsRedacted());
                row.setSlackMentions(summary.slackMentions());
                row.setPeopleDeleted(summary.peopleDeleted());
                row.setStakeholdersCleared(summary.stakeholdersCleared());
                row.setTrustlinkConnections(summary.trustlinkConnections());
                row.setFailures(summary.failures());
            }
            row.setFailureCode(failureCode);
            row.persist();
            return row;
        });

        log.infof("CRM retention purge run %s closed: status=%s failureCode=%s", runUuid, status,
                failureCode == null ? "-" : failureCode);
        purgeRuns();
        return closed;
    }

    /**
     * Forgets runs older than the retention window.
     *
     * <p>{@code startedAt}, not {@code finishedAt}: a row that was never closed has no finish,
     * and those are precisely the rows — a process killed mid-run — that would otherwise
     * accumulate for ever.
     */
    void purgeRuns() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(RUN_RETENTION_DAYS);
        long purged = QuarkusTransaction.requiringNew()
                .call(() -> CrmRetentionPurgeRun.delete("startedAt < ?1", cutoff));
        if (purged > 0) {
            log.infof("CRM retention purge runs: purged %d rows older than %s",
                    purged, cutoff.toLocalDate());
        }
    }

    // ------------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------------

    /** {@code client_uuid -> count}, for one of the outstanding-row queries. */
    private Map<String, Long> countByClient(String sql, boolean needsRedactedParameter) {
        Query query = em.createNativeQuery(sql);
        if (needsRedactedParameter) {
            query.setParameter("redacted", REDACTED_SIGNAL_TEXT);
        }
        Map<String, Long> counts = new HashMap<>();
        for (Object[] row : rowsOf(query)) {
            String clientUuid = asString(row[0]);
            if (clientUuid == null) {
                continue;
            }
            counts.put(clientUuid, asLong(row[1]));
        }
        return counts;
    }

    /** The account clock, flattened out of the feed's own answer so the two cannot disagree. */
    private Map<String, LocalDate> lastActivityDates() {
        Map<String, LocalDate> dates = new HashMap<>();
        for (Map.Entry<String, AccountActivityDTO> entry : accountActivityService.lastActivityForAll().entrySet()) {
            AccountActivityDTO activity = entry.getValue();
            if (activity != null && activity.occurredAt() != null) {
                dates.put(entry.getKey(), activity.occurredAt());
            }
        }
        return dates;
    }

    /**
     * The two tables a colleague writes to by hand, which no source in the activity feed sees.
     *
     * <p>Folded into the clock with {@code max()}, never as a fallback — see
     * {@link #lastActivityOf} for what that distinction cost. Read once per run, before any
     * delete, and never again during it.
     */
    private Map<String, LocalDate> humanClock() {
        Map<String, LocalDate> dates = new HashMap<>();
        mergeNewest(dates, HUMAN_CLOCK_CLAIMS);
        mergeNewest(dates, HUMAN_CLOCK_STAKEHOLDERS);
        mergeNewest(dates, "select client_uuid, max(reviewed_at) from account_calendar_review group by client_uuid");
        return dates;
    }

    /** The newest of an account's own registry rows, for accounts nothing else dates at all. */
    private Map<String, LocalDate> fallbackClock() {
        Map<String, LocalDate> dates = new HashMap<>();
        mergeNewest(dates, CLOCK_PEOPLE);
        return dates;
    }

    private void mergeNewest(Map<String, LocalDate> into, String sql) {
        for (Object[] row : rowsOf(em.createNativeQuery(sql))) {
            String clientUuid = asString(row[0]);
            LocalDate when = toLocalDate(row[1]);
            if (clientUuid == null || when == null) {
                continue;
            }
            LocalDate existing = into.get(clientUuid);
            if (existing == null || existing.isBefore(when)) {
                into.put(clientUuid, when);
            }
        }
    }

    private long execute(String sql, Map<String, ?> bindings) {
        Query query = em.createNativeQuery(sql);
        bindings.forEach(query::setParameter);
        return query.executeUpdate();
    }

    @SuppressWarnings("unchecked")
    private static List<Object[]> rowsOf(Query query) {
        return query.getResultList();
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    /** The counters on the run row are {@code INT}; a count that large is not a real state. */
    private static int toInt(long value) {
        return (int) Math.min(value, Integer.MAX_VALUE);
    }

    /**
     * A driver date, whatever shape it arrives in.
     *
     * <p>The same ladder {@code AccountActivityService.toLocalDate} walks; it is copied rather
     * than shared because that one is package-private in the account package and this job has
     * no business widening it.
     */
    private static LocalDate toLocalDate(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime().toLocalDate();
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate();
        }
        if (value instanceof LocalDateTime dateTime) {
            return dateTime.toLocalDate();
        }
        if (value instanceof LocalDate date) {
            return date;
        }
        return null;
    }
}
