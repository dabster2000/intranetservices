package dk.trustworks.intranet.recruitmentservice.services;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventType;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentApplication;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentEmailTemplate;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentCommsCoverageService.Counts;
import dk.trustworks.intranet.scheduling.SchedulerShutdownGuard;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * The nightly rollup behind the communications Journey tab: how often each
 * pipeline moment fired over the last {@link #WINDOW_DAYS} days, and how
 * many letters actually went out for it.
 *
 * <p>Coverage — <em>does a letter answer this moment</em> — is decided live
 * in {@link RecruitmentCommsCoverageService}. This job answers the other
 * half, <em>did it matter</em>: a moment that fired 40 times with zero
 * letters is the silence the page exists to reveal, and a moment nothing
 * ever reaches is not worth a recruiter's afternoon. That count is a 90-day
 * scan of the event stream with a JSON payload predicate per event type,
 * which is far too much work to do inside a page request and pointless to
 * redo per viewer — the answer moves once a day at most.</p>
 *
 * <h3>The mapping is the reactor's, not a second opinion</h3>
 * Which trigger an event would fire is decided through the same
 * {@link RecruitmentEmailService#rejectionKeyChain} /
 * {@link RecruitmentEmailService#pooledKeyChain} the candidate mailer
 * resolves, and which trigger a sent letter belonged to through
 * {@link RecruitmentEmailService#effectiveTrigger}. A rollup that derived
 * either itself would eventually count moments the runtime does not have.
 *
 * <h3>One rejection, two rows</h3>
 * {@code APPLICATION_REJECTED} increments the generic rung
 * <em>and</em> the reason-coded one, because the Journey shows both: the
 * "Rejected" moment and, nested under it, "Rejected — too junior, at
 * screening". Both statements are true of the same rejection, and neither
 * row would read correctly with the other's number.
 *
 * <h3>Recomputed, never accumulated</h3>
 * Every run recounts the whole window and REPLACES what it wrote, inside
 * one transaction; keys that fell out of the window are deleted. A retry, a
 * second ECS task or a manual ops run therefore lands on the same numbers
 * instead of doubling them. This is deliberately not the additive idiom
 * {@code RecruitmentReportingProjector} uses — that projection sees each
 * event exactly once, this one sees the same 90 days again every night.
 *
 * <h3>Deliberately not feature-flag gated</h3>
 * Same posture as {@code RecruitmentReportingProjector}: the flags gate the
 * comms <em>surface</em>, not this bookkeeping. The job has no external side
 * effects, and gating it would mean the Journey opened blank for a day after
 * anyone flipped a flag.
 *
 * <p>The counting core is the static {@link #count(RollupContext,
 * LocalDateTime)} over a plain {@link RollupContext}, so the fast (DB-free)
 * tier exercises every mapping and the window boundary without a database;
 * the bean only gathers the context and writes the result.</p>
 */
@JBossLog
@ApplicationScoped
public class RecruitmentCommsCoverageJob {

    /** The rolling window, matching {@code recruitment_comms_coverage.window_days}. */
    public static final int WINDOW_DAYS = 90;

    /** Payload key carrying the letter's identity on an {@code EMAIL_SENT}. */
    static final String PAYLOAD_TEMPLATE_KEY = "template_key";

    private static final String PAYLOAD_ORIGIN = "origin";
    private static final String PAYLOAD_DIRECTION = "direction";
    private static final String PAYLOAD_TO_STAGE = "to";
    private static final String PAYLOAD_REASON_CODE = "reason_code";
    private static final String PAYLOAD_FROM_STAGE = "from_stage";
    private static final String PAYLOAD_ENTERED_POOL = "entered_pool";
    private static final String PAYLOAD_POOL_STATUS = "pool_status";

    /**
     * A stage entry mails the candidate when it moves them onwards. The
     * mailer spells this as "not BACK"; over a two-valued
     * {@link RecruitmentApplication.MoveDirection} that is the same set of
     * events, and naming FORWARD says what is being counted.
     */
    private static final String DIRECTION_FORWARD =
            RecruitmentApplication.MoveDirection.FORWARD.name();

    /**
     * The only event types this job reads. Everything else in the stream is
     * irrelevant to candidate comms and is left in the database — the query
     * below is the one expensive thing the job does.
     */
    static final List<RecruitmentEventType> COUNTED_TYPES = List.of(
            RecruitmentEventType.APPLICATION_CREATED,
            RecruitmentEventType.APPLICATION_STAGE_CHANGED,
            RecruitmentEventType.APPLICATION_REJECTED,
            RecruitmentEventType.UNSOLICITED_APPLICATION_RECEIVED,
            RecruitmentEventType.DUPLICATE_APPLICATION_RECEIVED,
            RecruitmentEventType.CANDIDATE_POOLED,
            RecruitmentEventType.EMAIL_SENT);

    /**
     * Assignment, not accumulation — {@code = VALUES(...)}, never
     * {@code = occurred_count + VALUES(...)}. The whole window is recounted
     * every run, so an additive upsert (the sibling
     * {@code recruitment_fact_monthly} idiom, which is correct for a
     * once-per-event projection) would multiply the same 90 days by the
     * number of nights the job has run. Pinned by
     * {@code RecruitmentCommsCoverageJobTest}.
     */
    static final String UPSERT = """
            INSERT INTO recruitment_comms_coverage
                (trigger_key, occurred_count, emailed_count, window_days, computed_at)
            VALUES (:key, :occurred, :emailed, :windowDays, :computedAt)
            ON DUPLICATE KEY UPDATE
                occurred_count = VALUES(occurred_count),
                emailed_count  = VALUES(emailed_count),
                window_days    = VALUES(window_days),
                computed_at    = VALUES(computed_at)
            """;

    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {
    };

    @Inject
    EntityManager em;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    RecruitmentEmailService emailService;

    /**
     * One event, reduced to the three facts the rollup reads. Never carries
     * the event's {@code pii} column — the counts are structural and the
     * loader below does not select it.
     */
    public record CountedEvent(RecruitmentEventType eventType,
                               LocalDateTime occurredAt,
                               Map<String, Object> payload) {
    }

    /**
     * Everything the pure core needs, gathered up front.
     *
     * @param events              the window's candidate-comms events, any order
     * @param triggerOfTemplateKey a sent letter's {@code template_key} → the
     *                             moment it answers; null when the letter
     *                             answers no moment at all
     */
    public record RollupContext(List<CountedEvent> events,
                                Function<String, String> triggerOfTemplateKey) {
    }

    // ------------------------------------------------------------------
    // The schedule
    // ------------------------------------------------------------------

    /**
     * 03:50 UTC — after the pipeline's day is settled and clear of the
     * neighbouring nightly jobs.
     */
    @Scheduled(cron = "0 50 3 * * ?", identity = "recruitment-comms-coverage",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
            skipExecutionIf = SchedulerShutdownGuard.class)
    void nightlyRollup() {
        try {
            int keys = QuarkusTransaction.requiringNew()
                    .call(() -> rollup(LocalDateTime.now(ZoneOffset.UTC)));
            // Logged unconditionally: a quiet night and a night the job never
            // ran look identical in CloudWatch otherwise, and "did it run?" is
            // the first question anyone asks when the Journey looks wrong.
            log.infof("Recruitment comms coverage: %d trigger key(s) counted over %d days",
                    keys, WINDOW_DAYS);
        } catch (RuntimeException e) {
            log.errorf(e, "Recruitment comms coverage rollup failed");
        }
    }

    /**
     * One run: recount the window and replace the table with the result.
     * Public for ops and tests.
     * <p>
     * Runs in the CALLER's transaction — the read and both writes have to
     * commit together, or a reader can catch the table mid-replace and show
     * a Journey whose moments disagree about which night they came from.
     *
     * @param now the window's upper edge, UTC
     * @return how many trigger keys the run wrote
     */
    public int rollup(LocalDateTime now) {
        Map<String, Counts> counts = count(
                new RollupContext(loadEvents(windowStart(now)), triggerResolver()), now);
        prune(counts.keySet());
        counts.forEach((key, value) -> em.createNativeQuery(UPSERT)
                .setParameter("key", key)
                .setParameter("occurred", value.occurred())
                .setParameter("emailed", value.emailed())
                .setParameter("windowDays", WINDOW_DAYS)
                .setParameter("computedAt", now)
                .executeUpdate());
        return counts.size();
    }

    // ------------------------------------------------------------------
    // Pure core — no CDI, no DB. Tested mapping-by-mapping in the fast tier.
    // ------------------------------------------------------------------

    /** The window's lower edge: {@link #WINDOW_DAYS} back from {@code now}. */
    public static LocalDateTime windowStart(LocalDateTime now) {
        return now.minusDays(WINDOW_DAYS);
    }

    /**
     * Whether an event still counts. The edge is inclusive, and the loader's
     * SQL narrows on the same {@code >=} — the predicate stays here so there
     * is one place that decides, and one place a test can ask.
     */
    static boolean inWindow(LocalDateTime occurredAt, LocalDateTime windowStart) {
        return occurredAt != null && !occurredAt.isBefore(windowStart);
    }

    /**
     * Fold the window into one row per trigger key. Sorted, so a log line or
     * a debugger shows the table in the same order every run.
     */
    public static Map<String, Counts> count(RollupContext ctx, LocalDateTime now) {
        LocalDateTime windowStart = windowStart(now);
        Map<String, int[]> tally = new TreeMap<>(); // key -> [occurred, emailed]
        for (CountedEvent event : ctx.events()) {
            if (!inWindow(event.occurredAt(), windowStart)) {
                continue;
            }
            if (event.eventType() == RecruitmentEventType.EMAIL_SENT) {
                String trigger = ctx.triggerOfTemplateKey()
                        .apply(asString(event.payload().get(PAYLOAD_TEMPLATE_KEY)));
                if (trigger != null) {
                    tally.computeIfAbsent(trigger, key -> new int[2])[1]++;
                }
                continue;
            }
            for (String key : occurredKeys(event.eventType(), event.payload())) {
                tally.computeIfAbsent(key, unused -> new int[2])[0]++;
            }
        }
        Map<String, Counts> counts = new LinkedHashMap<>();
        tally.forEach((key, value) -> counts.put(key, new Counts(value[0], value[1])));
        return counts;
    }

    /**
     * The moments one pipeline event makes true — the trigger the candidate
     * mailer would look for, reduced to the rungs the Journey actually shows.
     * Empty when the event does not reach the candidate at all (a manually
     * attached application, a back-move, a re-bucketing).
     */
    public static List<String> occurredKeys(RecruitmentEventType type, Map<String, Object> payload) {
        return switch (type) {
            // Public submissions only: a recruiter attaching a candidate by
            // hand is not an application receipt.
            case APPLICATION_CREATED -> PublicApplyService.ORIGIN_PUBLIC_FORM
                    .equals(payload.get(PAYLOAD_ORIGIN))
                    ? List.of(RecruitmentEmailService.KEY_ACKNOWLEDGEMENT)
                    : List.of();
            case APPLICATION_STAGE_CHANGED -> stageKeys(payload);
            // Both rungs, and deliberately not the middle one: the chain's
            // REJECTION_<reason> rung is a routing convenience the Journey
            // does not show a row for.
            case APPLICATION_REJECTED -> endsOf(RecruitmentEmailService.rejectionKeyChain(
                    asString(payload.get(PAYLOAD_REASON_CODE)),
                    asString(payload.get(PAYLOAD_FROM_STAGE))));
            case UNSOLICITED_APPLICATION_RECEIVED ->
                    List.of(RecruitmentEmailService.KEY_UNSOLICITED_ACKNOWLEDGEMENT);
            case DUPLICATE_APPLICATION_RECEIVED ->
                    List.of(RecruitmentEmailService.KEY_DUPLICATE_APPLICATION_NOTICE);
            case CANDIDATE_POOLED -> pooledKeys(payload);
            default -> List.of();
        };
    }

    /** Forward entries only; a back-move never mails the candidate. */
    private static List<String> stageKeys(Map<String, Object> payload) {
        if (!DIRECTION_FORWARD.equals(payload.get(PAYLOAD_DIRECTION))) {
            return List.of();
        }
        String to = asString(payload.get(PAYLOAD_TO_STAGE));
        return to == null ? List.of() : List.of(RecruitmentEmailService.STAGE_KEY_PREFIX + to);
    }

    /**
     * Entering the pool is a moment; re-bucketing someone already in it is
     * not — the same test {@code CandidateMailerReactor.pooledKeys} makes,
     * including its reading of an absent flag as "entered".
     */
    private static List<String> pooledKeys(Map<String, Object> payload) {
        if (Boolean.FALSE.equals(payload.get(PAYLOAD_ENTERED_POOL))) {
            return List.of();
        }
        return endsOf(RecruitmentEmailService.pooledKeyChain(
                asString(payload.get(PAYLOAD_POOL_STATUS))));
    }

    /**
     * The rungs of a chain the Journey has a row for: the most specific one
     * and the generic one it falls back to. The chain is already deduplicated
     * by its builder, so a one-rung chain is returned whole.
     */
    private static List<String> endsOf(List<String> chain) {
        return chain.size() < 2 ? chain : List.of(chain.getFirst(), chain.getLast());
    }

    private static String asString(Object value) {
        return value instanceof String s && !s.isBlank() ? s : null;
    }

    // ------------------------------------------------------------------
    // The DB boundary
    // ------------------------------------------------------------------

    /**
     * The window's candidate-comms events, as a projection rather than
     * entities: the rollup needs three columns and the event's {@code pii}
     * column is not one of them, so personal data never enters this job's
     * memory. Bounded by 90 days of six event types — thousands of rows at
     * production volume, counted once a night.
     */
    private List<CountedEvent> loadEvents(LocalDateTime windowStart) {
        return em.createQuery("""
                        select e.eventType, e.occurredAt, e.payload
                        from RecruitmentEvent e
                        where e.eventType in :types and e.occurredAt >= :since
                        order by e.seq
                        """, Object[].class)
                .setParameter("types", COUNTED_TYPES)
                .setParameter("since", windowStart)
                .getResultList().stream()
                .map(row -> new CountedEvent((RecruitmentEventType) row[0],
                        (LocalDateTime) row[1], parse((String) row[2])))
                .toList();
    }

    /**
     * A sent letter's identity key → the moment it answers.
     * <p>
     * Judged by {@link RecruitmentEmailService#effectiveTrigger}, so a letter
     * TA has pointed at a different moment counts under THAT moment — which
     * is the whole point of the trigger/identity split, and the reason this
     * cannot be a prefix test on the key itself. A key with no row left (the
     * letter was deleted after it sent) falls back to the key when the key is
     * itself a trigger: the best statement still available about a letter
     * nobody can look up any more.
     */
    private Function<String, String> triggerResolver() {
        Map<String, String> byTemplateKey = new HashMap<>();
        for (RecruitmentEmailTemplate template : emailService.listTemplates()) {
            byTemplateKey.put(template.getTemplateKey(),
                    RecruitmentEmailService.effectiveTrigger(template));
        }
        return key -> {
            if (key == null) {
                return null;
            }
            if (byTemplateKey.containsKey(key)) {
                return byTemplateKey.get(key);
            }
            return RecruitmentEmailService.isTriggerKey(key) ? key : null;
        };
    }

    /**
     * Drop the keys this run did not produce. Without it a moment that
     * stopped happening would keep its last count forever, and the Journey
     * would report activity that aged out of the window months ago.
     */
    private void prune(Set<String> keep) {
        if (keep.isEmpty()) {
            em.createNativeQuery("DELETE FROM recruitment_comms_coverage").executeUpdate();
            return;
        }
        em.createNativeQuery(
                        "DELETE FROM recruitment_comms_coverage WHERE trigger_key NOT IN (:keys)")
                .setParameter("keys", keep)
                .executeUpdate();
    }

    private Map<String, Object> parse(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, JSON_OBJECT);
        } catch (Exception e) {
            // A payload we cannot read is one event's worth of counting lost,
            // never a failed rollup: the row is still on the timeline and the
            // next night tries again.
            log.warnf("Recruitment comms coverage: unreadable event payload skipped (%s)",
                    e.getMessage());
            return Map.of();
        }
    }
}
