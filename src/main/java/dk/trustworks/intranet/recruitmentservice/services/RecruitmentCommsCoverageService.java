package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.recruitmentservice.dto.TemplateCoverageResponse;
import dk.trustworks.intranet.recruitmentservice.dto.TemplateCoverageResponse.MomentCoverage;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentEmailTemplate;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidatePoolStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentPendingEmailStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentRejectionReason;
import dk.trustworks.intranet.recruitmentservice.model.enums.RecruitmentStage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import lombok.extern.jbosslog.JBossLog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Computes the {@code email-templates/coverage} read model: for every
 * curated pipeline moment, whether a letter actually answers it, and how
 * often the moment has fired lately. Feeds the communications page's
 * Journey tab.
 *
 * <p>The judgment is made through the SAME chain the candidate mailer
 * resolves — {@link RecruitmentEmailService#rejectionKeyChain},
 * {@link RecruitmentEmailService#pooledKeyChain} and, one rung at a time,
 * {@link RecruitmentEmailService#findFirstActiveByTrigger}. That is the
 * whole point of the screen: a page that computed coverage its own way
 * would eventually tell TA a moment was covered while the runtime went on
 * sending nothing, and nobody would find out until a candidate did.</p>
 *
 * <p>The moment vocabulary is derived rather than written down twice: every
 * rejection and pool key here is {@code chain.get(0)} of the corresponding
 * chain builder, so the reason and bucket suffixes exist in exactly one
 * place in the codebase.</p>
 *
 * <p>The decision logic lives in the static {@link #build(CoverageContext)}
 * core over a plain {@link CoverageContext}, so the fast (DB-free) test
 * tier can exercise every outcome without a database; this bean only
 * gathers the context. Read-only and side-effect free.</p>
 */
@JBossLog
@ApplicationScoped
public class RecruitmentCommsCoverageService {

    // Outcome and group vocabulary — string constants rather than enums
    // because they only exist to travel: the FE union types mirror them 1:1.

    /** A letter of this moment's own, switched on. */
    public static final String OUTCOME_COVERED = "COVERED";
    /** No letter of its own; a less specific rung of its chain answers instead. */
    public static final String OUTCOME_FALLS_BACK = "FALLS_BACK";
    /** A letter exists for it but is switched off, so the moment is silent. */
    public static final String OUTCOME_INACTIVE = "INACTIVE";
    /** Nothing anywhere in the chain. The moment is silent and always was. */
    public static final String OUTCOME_NONE = "NONE";

    public static final String GROUP_APPLICATION = "APPLICATION";
    public static final String GROUP_STAGE = "STAGE";
    public static final String GROUP_REJECTION = "REJECTION";
    public static final String GROUP_POOL = "POOL";
    public static final String GROUP_GDPR = "GDPR";

    /**
     * The nightly rollup {@code RecruitmentCommsCoverageJob} writes. Read
     * defensively by name: the coverage page ships before the job does, and
     * has to work on a database where the table is empty — or absent.
     */
    static final String ROLLUP_TABLE = "recruitment_comms_coverage";

    /**
     * The stage a moment's rejection chain is built from. Only the
     * SCREENING-vs-later split matters to
     * {@link RecruitmentEmailService#rejectionKeyChain}, so the second one
     * is any interview stage — named by an example rather than by a second
     * copy of the bucket vocabulary.
     */
    private static final String SCREENING_STAGE = RecruitmentStage.SCREENING.name();
    private static final String POST_INTERVIEW_STAGE = RecruitmentStage.INTERVIEW_1.name();

    /** The interview stages that carry a letter of their own, plus the offer. */
    private static final List<RecruitmentStage> STAGE_MOMENTS = List.of(
            RecruitmentStage.INTERVIEW_1,
            RecruitmentStage.INTERVIEW_2,
            RecruitmentStage.INTERVIEW_3,
            RecruitmentStage.OFFER);

    @Inject
    EntityManager em;

    @Inject
    RecruitmentEmailService emailService;

    /** Rollup counts for one moment; zeroes until the nightly job has run. */
    public record Counts(int occurred, int emailed) {
        public static final Counts ZERO = new Counts(0, 0);
    }

    /** Review-queue history of one letter, counted live off a small table. */
    public record QueueCounts(int queued, int dismissed, int approved) {
        public static final QueueCounts ZERO = new QueueCounts(0, 0, 0);
    }

    /**
     * Everything the pure core needs, gathered up front. The two template
     * functions are the DB boundary and take ONE chain rung, never a chain:
     * walking the chain is the core's job, and keeping the rung rule on the
     * far side of the boundary is what lets the bean answer it with the
     * mailer's own {@link RecruitmentEmailService#findFirstActiveByTrigger}
     * rather than a second copy of its two SQL clauses.
     *
     * @param answeringRung  a rung → the ACTIVE letter answering it; null
     *                       when nothing sendable claims that rung
     * @param claimantOfRung a rung → the letter that would answer it in ANY
     *                       state; consulted only once the whole chain has
     *                       come up empty, to tell "switched off" apart from
     *                       "never written"
     * @param rollup         a moment key → its windowed counts
     * @param queue          a template key → its review-queue history
     */
    public record CoverageContext(
            Function<String, RecruitmentEmailTemplate> answeringRung,
            Function<String, RecruitmentEmailTemplate> claimantOfRung,
            Function<String, Counts> rollup,
            Function<String, QueueCounts> queue
    ) {
    }

    /**
     * The bean entry point: gather the live template table and both count
     * sources, delegate to the core.
     * <p>
     * Rung resolution is memoised for the duration of the call — the
     * generic rejection rungs sit at the bottom of seventeen chains, and
     * asking the database seventeen times for the same answer would be the
     * only expensive thing on this page.
     */
    public TemplateCoverageResponse coverage() {
        Map<String, RecruitmentEmailTemplate> answering = new HashMap<>();
        Map<String, RecruitmentEmailTemplate> claimants = claimantsByRung();
        Map<String, Counts> rollup = rollupCounts();
        Map<String, QueueCounts> queue = queueCounts();
        return build(new CoverageContext(
                rung -> {
                    // Not computeIfAbsent: "nothing answers this rung" is the
                    // answer worth remembering most, and that one is a null.
                    if (!answering.containsKey(rung)) {
                        answering.put(rung, emailService.findFirstActiveByTrigger(List.of(rung)));
                    }
                    return answering.get(rung);
                },
                claimants::get,
                key -> rollup.getOrDefault(key, Counts.ZERO),
                key -> queue.getOrDefault(key, QueueCounts.ZERO)));
    }

    // ------------------------------------------------------------------
    // Pure core — no CDI, no DB. Tested outcome-by-outcome in the fast tier.
    // ------------------------------------------------------------------

    public static TemplateCoverageResponse build(CoverageContext ctx) {
        return new TemplateCoverageResponse(curatedMoments().stream()
                .map(moment -> coverageOf(ctx, moment))
                .toList());
    }

    /**
     * One moment, judged as the mailer judges it: walk the chain most
     * specific rung first and take the first sendable letter — the loop
     * {@link RecruitmentEmailService#findFirstActiveByTrigger} runs over
     * the very same keys.
     * <p>
     * When nothing in the chain is sendable, the same walk runs again over
     * letters in any state. It answers a different question — not "what
     * will be sent" (nothing will) but "what is the actionable fact", and a
     * letter someone deliberately switched off is a far more useful thing
     * to show than a blank. This mirrors
     * {@code RecruitmentCommunicationPlanService}'s preference for
     * reporting an inactive row over a missing one.
     */
    private static MomentCoverage coverageOf(CoverageContext ctx, Moment moment) {
        String ownKey = moment.triggerKey();
        RecruitmentEmailTemplate letter = null;
        String answeredOn = null;
        for (String rung : moment.chain()) {
            RecruitmentEmailTemplate hit = ctx.answeringRung().apply(rung);
            if (hit != null) {
                letter = hit;
                answeredOn = rung;
                break;
            }
        }
        String outcome;
        if (letter != null) {
            outcome = ownKey.equals(answeredOn) ? OUTCOME_COVERED : OUTCOME_FALLS_BACK;
        } else {
            for (String rung : moment.chain()) {
                RecruitmentEmailTemplate off = ctx.claimantOfRung().apply(rung);
                if (off != null) {
                    letter = off;
                    answeredOn = rung;
                    break;
                }
            }
            outcome = letter == null ? OUTCOME_NONE : OUTCOME_INACTIVE;
        }
        Counts counts = ctx.rollup().apply(ownKey);
        QueueCounts queue = letter == null
                ? QueueCounts.ZERO : ctx.queue().apply(letter.getTemplateKey());
        return new MomentCoverage(
                ownKey,
                moment.group(),
                outcome,
                letter == null ? null : letter.getUuid(),
                letter == null ? null : letter.getTemplateKey(),
                letter == null ? null : letter.getName(),
                letter == null ? null : letter.isAutoSend(),
                ownKey.equals(answeredOn) ? null : answeredOn,
                counts.occurred(),
                counts.emailed(),
                queue.queued(),
                queue.dismissed(),
                queue.approved(),
                moment.specifics().stream().map(child -> coverageOf(ctx, child)).toList());
    }

    // ------------------------------------------------------------------
    // The curated moments
    // ------------------------------------------------------------------

    /**
     * One moment: the chain the mailer would resolve for it, most specific
     * rung first, and the narrower moments nested under it. The moment's own
     * key is the chain's first rung by construction — there is no way to
     * name a moment that its own chain does not lead with.
     */
    private record Moment(String group, List<String> chain, List<Moment> specifics) {

        String triggerKey() {
            return chain.get(0);
        }

        static Moment of(String group, List<String> chain) {
            return new Moment(group, chain, List.of());
        }
    }

    /**
     * The ten curated moments, in the order the Journey tab reads them.
     * <p>
     * Rejection is one moment carrying both generic rungs: the screening
     * letter is the moment's own key and the after-interviews letter is the
     * first of its specifics. That one is a sibling rung rather than a
     * narrower one — it sits there because the moment is a single row and a
     * row has a single key, and it reads correctly under a "Rejected"
     * heading because the frontend already labels it "Rejected after
     * interviews — any reason".
     */
    private static List<Moment> curatedMoments() {
        List<Moment> moments = new ArrayList<>();
        moments.add(Moment.of(GROUP_APPLICATION,
                List.of(RecruitmentEmailService.KEY_ACKNOWLEDGEMENT)));
        moments.add(Moment.of(GROUP_APPLICATION,
                List.of(RecruitmentEmailService.KEY_UNSOLICITED_ACKNOWLEDGEMENT)));
        moments.add(Moment.of(GROUP_APPLICATION,
                List.of(RecruitmentEmailService.KEY_DUPLICATE_APPLICATION_NOTICE)));
        for (RecruitmentStage stage : STAGE_MOMENTS) {
            moments.add(Moment.of(GROUP_STAGE,
                    List.of(RecruitmentEmailService.STAGE_KEY_PREFIX + stage.name())));
        }
        moments.add(rejectionMoment());
        moments.add(pooledMoment());
        // The consent renewal is job-owned: nothing may be pointed at it and
        // it may be pointed nowhere, so its chain is its own key alone — and
        // the two rungs then resolve exactly what RecruitmentGdprService's
        // findActiveByKey resolves, because such a row can never carry a
        // trigger assignment for the first rung to find.
        moments.add(Moment.of(GROUP_GDPR,
                List.of(RecruitmentGdprService.KEY_CONSENT_RENEWAL)));
        return List.copyOf(moments);
    }

    /** Rejected: both generic rungs, with the eight reasons × two stage buckets under them. */
    private static Moment rejectionMoment() {
        List<Moment> specifics = new ArrayList<>();
        specifics.add(Moment.of(GROUP_REJECTION,
                RecruitmentEmailService.rejectionKeyChain(null, POST_INTERVIEW_STAGE)));
        for (RecruitmentRejectionReason reason : RecruitmentRejectionReason.values()) {
            for (String stage : List.of(SCREENING_STAGE, POST_INTERVIEW_STAGE)) {
                specifics.add(Moment.of(GROUP_REJECTION,
                        RecruitmentEmailService.rejectionKeyChain(reason.name(), stage)));
            }
        }
        return new Moment(GROUP_REJECTION,
                RecruitmentEmailService.rejectionKeyChain(null, SCREENING_STAGE),
                List.copyOf(specifics));
    }

    /** Talent pool: the generic letter, with the five buckets under it. */
    private static Moment pooledMoment() {
        List<Moment> specifics = new ArrayList<>();
        for (CandidatePoolStatus status : CandidatePoolStatus.values()) {
            specifics.add(Moment.of(GROUP_POOL,
                    RecruitmentEmailService.pooledKeyChain(status.name())));
        }
        return new Moment(GROUP_POOL,
                RecruitmentEmailService.pooledKeyChain(null),
                List.copyOf(specifics));
    }

    // ------------------------------------------------------------------
    // The DB boundary
    // ------------------------------------------------------------------

    /**
     * Every letter indexed by the rung it would answer if it were switched
     * on. A row is judged by {@link RecruitmentEmailService#effectiveTrigger}
     * — the documented read-side mirror of the mailer's two rungs — widened
     * to the job-owned keys, which are not reserved triggers and so have no
     * effective trigger of their own while still answering their own key.
     * An explicit assignment outranks a letter merely sitting on its own key,
     * exactly as the mailer's rung order does.
     */
    private Map<String, RecruitmentEmailTemplate> claimantsByRung() {
        Map<String, RecruitmentEmailTemplate> claimants = new HashMap<>();
        for (RecruitmentEmailTemplate template : emailService.listTemplates()) {
            String rung = claimedRung(template);
            if (rung == null) {
                continue;
            }
            RecruitmentEmailTemplate incumbent = claimants.get(rung);
            if (incumbent == null
                    || (incumbent.getTriggerKey() == null && template.getTriggerKey() != null)) {
                claimants.put(rung, template);
            }
        }
        return claimants;
    }

    private static String claimedRung(RecruitmentEmailTemplate template) {
        String effective = RecruitmentEmailService.effectiveTrigger(template);
        if (effective != null) {
            return effective;
        }
        return template.getTriggerKey() == null ? template.getTemplateKey() : null;
    }

    /**
     * The nightly rollup, read defensively in both directions: the table may
     * not exist yet (this page ships ahead of the job) and, once it does, it
     * is empty until the first nightly run. Either way the page renders with
     * zeroes rather than failing — an empty count is a true statement about
     * a database nothing has counted yet.
     * <p>
     * Existence is checked rather than caught: a failed statement can leave
     * the session unusable for everything after it, and this method is not
     * the last thing the request does.
     */
    private Map<String, Counts> rollupCounts() {
        if (!rollupTableExists()) {
            log.debugf("%s does not exist yet — coverage reports zero counts", ROLLUP_TABLE);
            return Map.of();
        }
        Map<String, Counts> counts = new HashMap<>();
        for (Object[] row : rows("""
                SELECT trigger_key, occurred_count, emailed_count
                FROM recruitment_comms_coverage
                """)) {
            counts.put((String) row[0],
                    new Counts(((Number) row[1]).intValue(), ((Number) row[2]).intValue()));
        }
        return counts;
    }

    private boolean rollupTableExists() {
        return ((Number) em.createNativeQuery("""
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_name = :table
                """)
                .setParameter("table", ROLLUP_TABLE)
                .getSingleResult()).intValue() > 0;
    }

    /**
     * Review-queue history per letter, counted live: the queue is a small
     * table (it holds only what a recruiter has not yet resolved, plus its
     * resolved history), so it needs no rollup of its own. Keyed by
     * {@code template_key} — the queue row snapshots the letter's identity,
     * not the moment it was routed from.
     */
    private Map<String, QueueCounts> queueCounts() {
        Map<String, int[]> byKey = new HashMap<>(); // key -> [queued, dismissed, approved]
        for (Object[] row : rows("""
                SELECT template_key, status, COUNT(*)
                FROM recruitment_pending_emails
                GROUP BY 1, 2
                """)) {
            int[] counts = byKey.computeIfAbsent((String) row[0], key -> new int[3]);
            int count = ((Number) row[2]).intValue();
            counts[0] += count;
            if (RecruitmentPendingEmailStatus.DISMISSED.name().equals(row[1])) {
                counts[1] += count;
            } else if (RecruitmentPendingEmailStatus.APPROVED.name().equals(row[1])) {
                counts[2] += count;
            }
        }
        Map<String, QueueCounts> queue = new HashMap<>();
        byKey.forEach((key, counts) ->
                queue.put(key, new QueueCounts(counts[0], counts[1], counts[2])));
        return queue;
    }

    @SuppressWarnings("unchecked")
    private List<Object[]> rows(String sql) {
        return em.createNativeQuery(sql).getResultList();
    }
}
