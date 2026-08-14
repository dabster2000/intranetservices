package dk.trustworks.intranet.competenceservice.services;

import com.slack.api.model.block.LayoutBlock;
import dk.trustworks.intranet.aggregates.users.services.UserService;
import dk.trustworks.intranet.communicationsservice.services.SlackService;
import dk.trustworks.intranet.competenceservice.domain.CompetenceAudienceMatcher;
import dk.trustworks.intranet.competenceservice.domain.CompetenceAudienceMatcher.Subject;
import dk.trustworks.intranet.competenceservice.domain.CompetenceAudienceMatcher.Targeting;
import dk.trustworks.intranet.competenceservice.domain.CompetenceStatus;
import dk.trustworks.intranet.competenceservice.domain.CompetenceStatusCalculator.AttemptFact;
import dk.trustworks.intranet.competenceservice.domain.CompetenceStatusCalculator.CompletionFact;
import dk.trustworks.intranet.competenceservice.model.CompetenceRequirement;
import dk.trustworks.intranet.competenceservice.services.CompetenceStatusService.Snapshot;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.model.AppSetting;
import dk.trustworks.intranet.services.AppSettingService;
import dk.trustworks.intranet.userservice.model.enums.ConsultantType;
import dk.trustworks.intranet.userservice.model.enums.StatusType;
import dk.trustworks.intranet.utils.DateUtils;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static com.slack.api.model.block.Blocks.context;
import static com.slack.api.model.block.Blocks.header;
import static com.slack.api.model.block.Blocks.section;
import static com.slack.api.model.block.composition.BlockCompositions.markdownText;
import static com.slack.api.model.block.composition.BlockCompositions.plainText;
import static com.slack.api.model.block.element.BlockElements.asContextElements;

/**
 * The daily "your competence requirements need you" nudge (spec §13): one Slack DM per
 * person whose microcourse or test is <em>overdue</em>, or falls due within
 * {@link #DUE_SOON_DAYS} days.
 *
 * <p>Four properties carry the design.
 *
 * <ol>
 *   <li><strong>It names a requirement and a date, never a score.</strong> Spec §10.9: an
 *       attempt score is employee performance data whose audience is the matrix, not a
 *       chat client. The DM says <em>which</em> krav and <em>by when</em>, and stops
 *       there — pass, fail, correct count and threshold never appear.</li>
 *   <li><strong>"Falls due" is derived from the completion/pass timestamp, not from the
 *       status enum.</strong> The enum only distinguishes green from overdue, so a
 *       fourteen-day look-ahead is unanswerable from it. The timestamps already live on
 *       the {@link Snapshot} facts the matrix loads, so the look-ahead costs no extra
 *       query — see {@link #dueOn}. The pure
 *       {@code CompetenceStatusCalculator} stays untouched; the small amount of arithmetic
 *       this needs is here, and equally pure.</li>
 *   <li><strong>Slack's 3000-character limit is respected while assembling, not after.</strong>
 *       Slack rejects the <em>entire</em> message with {@code invalid_blocks} when a single
 *       text object overflows — a production failure mode this codebase has already paid
 *       for once — so a long list is cut with a "see the full list in the intranet" line
 *       rather than sent raw. See {@link #body}.</li>
 *   <li><strong>It is safe to run twice on the same day.</strong> {@code @Scheduled} can
 *       fire on the OLD task during an ECS Express cutover, which would otherwise DM
 *       everyone twice.</li>
 * </ol>
 *
 * <h3>Idempotency</h3>
 * No other daily job in this codebase has a "one message per person per day" shape to copy
 * — the recruitment sweeps derive idempotency from their own event log, which this module
 * does not have. So the simplest correct thing available: the run date is recorded in
 * {@code app_settings} under {@value #KEY_LAST_RUN} and a run whose date already matches
 * does nothing.
 *
 * <p>The date is claimed <strong>before</strong> the DMs go out, not after. During a
 * cutover both tasks fire within the same second, and claiming afterwards would leave the
 * whole send window open for the second one; claiming first narrows that window to the
 * milliseconds between the read and the write. The trade is explicit: a run that dies any
 * time after claiming the day — while planning, or half way through the DMs — sends
 * nothing more that day, and there is no compensating release. Tomorrow's run picks the
 * same people up regardless, because every condition is recomputed from the data rather
 * than remembered, so a missed nudge costs one day and a double nudge costs credibility.
 * Note this is a narrow window, not a lock — two truly simultaneous readers can still both
 * claim; {@code concurrentExecution = SKIP} on the scheduler covers the in-JVM half.
 *
 * <h3>Who is <em>not</em> nudged</h3>
 * Someone who has never completed the microcourse, or never passed the test, has no
 * completion timestamp and therefore no date — and a message that names a requirement and
 * no date is not the message spec §13 describes. Those people are red in the matrix and on
 * their own landing page from day one; they are deliberately out of this job's scope
 * rather than DM'd daily forever with nothing to say.
 *
 * <p>Language follows the rest of the module: Danish for anything a person reads, English
 * for chrome — identifiers, log tokens, code.
 */
@JBossLog
@ApplicationScoped
public class CompetenceDueNotifier {

    /**
     * {@code app_settings} key holding the ISO date of the last claimed run. Filed under
     * the module's own category so it travels with the rest of the competence settings;
     * it cannot leak into the settings screen or be edited through it, because
     * {@link CompetenceSettingsService} enumerates an explicit four-key allowlist for both
     * reads and writes.
     */
    static final String KEY_LAST_RUN = "competence.due-notifier.last-run";

    /** Spec §13: "overdue or falls due within 14 days". */
    static final int DUE_SOON_DAYS = 14;

    /** Slack rejects the whole message with {@code invalid_blocks} above this per text object. */
    static final int SLACK_TEXT_OBJECT_MAX_CHARS = 3000;

    /**
     * Head-room reserved so the truncation line itself can never be what overflows. The
     * line is ~50 characters; 200 leaves room for a long requirement name in the last
     * row that is written before the cut is decided.
     */
    static final int TRUNCATION_TAIL_BUDGET = 200;

    private static final String DAY_FORMAT = "dd-MM-yyyy";
    private static final String INTRO = "Følgende kompetencekrav kræver din handling.";
    private static final String HEADING_OVERDUE = "Overskredet";
    private static final String HEADING_SOON = "Forfalder inden for " + DUE_SOON_DAYS + " dage";

    @Inject
    CompetenceRequirementService requirementService;

    @Inject
    CompetenceStatusService statusService;

    @Inject
    CompetenceSettingsService settingsService;

    @Inject
    AppSettingService appSettingService;

    @Inject
    SlackService slackService;

    @Inject
    UserService userService;

    /**
     * The intranet's public base URL — the same property {@link SlackService} builds its
     * deep links from, so competence links cannot drift from the rest of the outbound
     * Slack traffic.
     */
    @ConfigProperty(name = "quarkus.application.base-url")
    String applicationBaseUrl;

    // -----------------------------------------------------------------------
    // types
    // -----------------------------------------------------------------------

    /**
     * One line in one person's DM.
     *
     * @param requirementUuid identity, for the distinct-requirement count in the log
     * @param kref            the SKI reference ({@code 7.b.3}), may be blank
     * @param name            the requirement name — the only free text in the message
     * @param dueOn           the day it falls (or fell) due
     * @param overdue         whether it is already past due
     */
    record DueItem(String requirementUuid, String kref, String name, LocalDate dueOn, boolean overdue) {

        String line() {
            String label = (kref == null || kref.isBlank()) ? name : kref + " " + name;
            return "• " + label + " — frist " + DateUtils.stringIt(dueOn, DAY_FORMAT);
        }
    }

    /** One person and everything they are being told about. */
    record Recipient(User user, List<DueItem> items) {
    }

    /** Everything the send loop needs, resolved in one database pass. */
    record Plan(List<Recipient> recipients, int requirementsNamed) {
    }

    /** An assembled message body and whether anything had to be cut to fit Slack's limit. */
    record Body(String text, boolean truncated) {
    }

    /** One rendered line, and whether it is a section heading rather than a requirement. */
    private record Line(String text, boolean heading) {
    }

    /**
     * The run outcome. Returned rather than only logged so the scheduler can stay a
     * five-line try/catch and so the whole job is assertable without a log capture.
     *
     * @param ran               false when the day was already claimed
     * @param people            how many people were actually DM'd
     * @param requirementsNamed how many distinct requirements were named across all DMs
     * @param truncated         whether any one DM was cut to fit Slack's 3000-char limit
     * @param failures          DMs that threw; the next day's run picks the person up again
     */
    public record NotifierSummary(boolean ran, int people, int requirementsNamed,
                                  boolean truncated, int failures) {
    }

    // -----------------------------------------------------------------------
    // run
    // -----------------------------------------------------------------------

    /**
     * One notification pass for {@code today}.
     *
     * <p>Deliberately a function of its argument rather than of the wall clock, so the
     * whole job is reproducible and testable. Statuses are evaluated at midday of that
     * date: the cadence rule counts whole elapsed days, so any fixed point inside the day
     * gives the same answer for every completion except ones timestamped within hours of
     * the run — and midday is the point that is not on either boundary.
     */
    public NotifierSummary run(LocalDate today) {
        if (!claimRun(today)) {
            log.debugf("competence-due-notifier skipped: already ran on %s", today);
            return new NotifierSummary(false, 0, 0, false, 0);
        }

        Plan plan = inTx(() -> plan(today));

        int people = 0;
        int failures = 0;
        boolean truncated = false;
        for (Recipient recipient : plan.recipients()) {
            User user = recipient.user();
            if (user.getSlackusername() == null || user.getSlackusername().isBlank()) {
                // No event, no bookkeeping: a Slack link added later is picked up by the
                // next run on its own.
                log.infof("competence-due-notifier: user %s has no Slack link — skipping",
                        user.getUuid());
                continue;
            }
            Body body = body(recipient.items());
            truncated |= body.truncated();
            try {
                slackService.sendMessage(user, fallbackText(recipient.items()), blocks(body));
                people++;
            } catch (Exception e) {
                failures++;
                log.warnf(e, "competence-due-notifier: DM to %s failed — continuing "
                        + "(tomorrow's run picks them up again)", user.getUuid());
            }
        }

        // The stable token the production log sweep filters on. Never the score, never the
        // requirement list — only the shape of the run.
        log.infof("COMPETENCE_DUE_NOTIFIER people=%d requirements=%d truncated=%b",
                people, plan.requirementsNamed(), truncated);
        if (failures > 0) {
            log.warnf("competence-due-notifier: %d DM(s) failed on %s", failures, today);
        }
        return new NotifierSummary(true, people, plan.requirementsNamed(), truncated, failures);
    }

    // -----------------------------------------------------------------------
    // idempotency
    // -----------------------------------------------------------------------

    /**
     * Claims {@code today} for this run.
     *
     * @return false when the day is already claimed — the second firing of an ECS Express
     *         cutover, which must send nothing
     */
    boolean claimRun(LocalDate today) {
        String stamp = DateUtils.stringIt(today);
        String recorded = inTx(() -> appSettingService.findByKey(KEY_LAST_RUN)
                .map(AppSetting::getSettingValue).orElse(null));
        if (stamp.equals(recorded == null ? null : recorded.trim())) {
            return false;
        }
        appSettingService.saveSetting(KEY_LAST_RUN, stamp, CompetenceSettingsService.CATEGORY, "SYSTEM");
        return true;
    }

    // -----------------------------------------------------------------------
    // planning
    // -----------------------------------------------------------------------

    /**
     * Resolves who is being told what, in one pass over the whole population.
     *
     * <p>Audience and status are resolved per requirement in batch, never per cell
     * (spec §10.10) — the population's whole competence picture is four queries via
     * {@link CompetenceStatusService#snapshot}, and audience membership needs two more
     * regardless of headcount.
     */
    Plan plan(LocalDate today) {
        List<CompetenceRequirement> requirements = CompetenceRequirement.listActive();
        if (requirements.isEmpty()) {
            return new Plan(List.of(), 0);
        }
        List<User> population = activeEmployees(today);
        if (population.isEmpty()) {
            return new Plan(List.of(), 0);
        }

        LocalDateTime now = today.atTime(LocalTime.NOON);
        Map<String, Subject> subjects = requirementService.subjectsOf(population);
        Snapshot snapshot = statusService.snapshot(population.stream().map(User::getUuid).toList());

        Map<String, List<DueItem>> byUser = new LinkedHashMap<>();
        Set<String> requirementsNamed = new HashSet<>();
        for (CompetenceRequirement requirement : requirements) {
            // Same rule as CompetenceRequirementService.isVisibleTo, read off the snapshot
            // instead of two queries per person: a half-published requirement is "Not
            // published yet" in the matrix and must never reach an employee at all.
            if (!snapshot.activeCourseLabels().containsKey(requirement.getUuid())
                    || !snapshot.activeTestLabels().containsKey(requirement.getUuid())) {
                continue;
            }
            Targeting targeting = requirementService.targetingOf(requirement);
            int cadenceDays = settingsService.effectiveCadenceDays(requirement);
            for (User user : population) {
                if (!CompetenceAudienceMatcher.inAudience(subjects.get(user.getUuid()), targeting)) {
                    continue;
                }
                DueItem item = dueItem(requirement, snapshot, user.getUuid(), cadenceDays, today, now);
                if (item == null) {
                    continue;
                }
                byUser.computeIfAbsent(user.getUuid(), k -> new ArrayList<>()).add(item);
                requirementsNamed.add(requirement.getUuid());
            }
        }

        List<Recipient> recipients = new ArrayList<>();
        for (User user : population) {
            List<DueItem> items = byUser.get(user.getUuid());
            if (items == null || items.isEmpty()) {
                continue;
            }
            // Overdue first, then by date: the most pressing thing is the first line, and
            // it is also the part that survives truncation.
            items.sort(Comparator.comparing(DueItem::overdue).reversed()
                    .thenComparing(DueItem::dueOn)
                    .thenComparing(DueItem::name, Comparator.nullsLast(Comparator.naturalOrder())));
            recipients.add(new Recipient(user, items));
        }
        return new Plan(recipients, requirementsNamed.size());
    }

    /**
     * The population the audience is resolved against: everybody with an ACTIVE employment
     * status today, of every consultant type.
     *
     * <p>Loaded <strong>deeply</strong> on purpose. {@code User.getUserStatus} reads the
     * transient {@code statuses} collection and synthesises TERMINATED when it is empty, so
     * a shallow load would make every person fail {@code isActiveEmployee} and the job
     * would quietly notify nobody — a silent no-op is the worst possible failure for a
     * compliance nudge.
     */
    List<User> activeEmployees(LocalDate today) {
        String[] statuses = {StatusType.ACTIVE.toString()};
        String[] types = Arrays.stream(ConsultantType.values()).map(Enum::toString).toArray(String[]::new);
        return userService.findUsersByDateAndStatusListAndTypes(today, statuses, types, false);
    }

    /**
     * The one line this person gets about this requirement, or {@code null} when the
     * requirement is not pressing for them.
     *
     * <p>A due date only exists where the person actually holds the evidence that expires,
     * so it is read from the track whose status says so — never from a track that is
     * "not started" (nothing to expire) or "retake required" (the old version's timestamp
     * would name a deadline that no longer means anything). Where both tracks have a date,
     * the earlier one wins: it is the one that decides when the person stops being
     * compliant.
     */
    DueItem dueItem(CompetenceRequirement requirement, Snapshot snapshot, String useruuid,
                    int cadenceDays, LocalDate today, LocalDateTime now) {
        CompetenceStatus.Course course = statusService.courseStatus(requirement, snapshot, useruuid, now);
        CompetenceStatus.Test test = statusService.testStatus(requirement, snapshot, useruuid, now);

        LocalDate courseDue = null;
        if (course == CompetenceStatus.Course.COMPLETED || course == CompetenceStatus.Course.OVERDUE) {
            courseDue = dueOn(latestCompletionAt(
                    snapshot.completionsFor(useruuid, requirement.getUuid())), cadenceDays);
        }
        LocalDate testDue = null;
        if (test == CompetenceStatus.Test.APPROVED
                || test == CompetenceStatus.Test.AWAITING_APPROVAL
                || test == CompetenceStatus.Test.OVERDUE) {
            testDue = dueOn(latestPassAt(
                    snapshot.attemptsFor(useruuid, requirement.getUuid())), cadenceDays);
        }

        LocalDate dueOn = earliest(courseDue, testDue);
        if (dueOn == null) {
            return null;
        }
        boolean overdue = course == CompetenceStatus.Course.OVERDUE
                || test == CompetenceStatus.Test.OVERDUE;
        if (!overdue && dueOn.isAfter(today.plusDays(DUE_SOON_DAYS))) {
            return null;
        }
        return new DueItem(requirement.getUuid(), requirement.getKref(), requirement.getName(),
                dueOn, overdue);
    }

    // -----------------------------------------------------------------------
    // pure arithmetic
    // -----------------------------------------------------------------------

    /**
     * The day a piece of evidence stops counting.
     *
     * <p>The cadence rule is timestamp-precision ({@code elapsed whole days > cadence});
     * this renders it at day precision because a deadline in a message is a date, not an
     * instant. The consequence is deliberate and harmless: someone can be nudged on the
     * morning of the day their evidence expires that evening.
     */
    static LocalDate dueOn(LocalDateTime at, int cadenceDays) {
        if (at == null || cadenceDays <= 0) {
            return null;
        }
        return at.toLocalDate().plusDays(cadenceDays);
    }

    /** Mirrors the calculator's completion selection, on the facts the snapshot already holds. */
    static LocalDateTime latestCompletionAt(List<CompletionFact> completions) {
        if (completions == null) {
            return null;
        }
        return completions.stream()
                .map(CompletionFact::completedAt)
                .filter(java.util.Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
    }

    /**
     * Mirrors the calculator's qualifying-pass selection. A revoked pass is not evidence,
     * so it cannot carry a due date either — {@code countsAsPass} is the same predicate the
     * status derivation uses.
     */
    static LocalDateTime latestPassAt(List<AttemptFact> attempts) {
        if (attempts == null) {
            return null;
        }
        return attempts.stream()
                .filter(a -> a.submittedAt() != null)
                .filter(AttemptFact::countsAsPass)
                .map(AttemptFact::submittedAt)
                .max(Comparator.naturalOrder())
                .orElse(null);
    }

    static LocalDate earliest(LocalDate a, LocalDate b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.isBefore(b) ? a : b;
    }

    // -----------------------------------------------------------------------
    // message
    // -----------------------------------------------------------------------

    /**
     * Assembles the message body inside Slack's per-text-object budget.
     *
     * <p>The budget is applied <em>while</em> appending, not by cutting the finished
     * string: a mid-word {@code substring} would land inside a requirement name and a
     * message that silently loses rows is worse than one that says it did. Everything past
     * the cut is replaced with a pointer to the intranet, which holds the full list.
     */
    Body body(List<DueItem> items) {
        List<Line> lines = new ArrayList<>();
        List<DueItem> overdue = items.stream().filter(DueItem::overdue).toList();
        List<DueItem> soon = items.stream().filter(i -> !i.overdue()).toList();
        if (!overdue.isEmpty()) {
            lines.add(new Line("\n\n*" + HEADING_OVERDUE + "*", true));
            overdue.forEach(i -> lines.add(new Line("\n" + i.line(), false)));
        }
        if (!soon.isEmpty()) {
            lines.add(new Line("\n\n*" + HEADING_SOON + "*", true));
            soon.forEach(i -> lines.add(new Line("\n" + i.line(), false)));
        }

        StringBuilder text = new StringBuilder(INTRO);
        int budget = SLACK_TEXT_OBJECT_MAX_CHARS - TRUNCATION_TAIL_BUDGET;
        int written = 0;
        for (Line line : lines) {
            if (text.length() + line.text().length() > budget) {
                text.append(tail(items.size() - written));
                return new Body(text.toString(), true);
            }
            text.append(line.text());
            if (!line.heading()) {
                written++;
            }
        }
        return new Body(text.toString(), false);
    }

    private String tail(int remaining) {
        return "\n\n… og " + Math.max(remaining, 1) + " mere. Se hele listen i intranettet.";
    }

    /**
     * The Block Kit layout. The final {@link StringUtils#abbreviate} is belt and braces —
     * {@link #body} already fits the budget, and a text object that overflows costs the
     * whole message, not just the block.
     */
    List<LayoutBlock> blocks(Body body) {
        List<LayoutBlock> blocks = new ArrayList<>();
        blocks.add(header(h -> h.text(plainText("Kompetencekrav"))));
        blocks.add(section(s -> s.text(markdownText(
                StringUtils.abbreviate(body.text(), SLACK_TEXT_OBJECT_MAX_CHARS)))));
        String url = competenceUrl();
        if (url != null) {
            blocks.add(context(c -> c.elements(asContextElements(
                    markdownText("<" + url + "|Åbn kompetencemodulet>")))));
        }
        return blocks;
    }

    /** Notification/preview text. Names a count, never a score. */
    String fallbackText(List<DueItem> items) {
        return items.size() + " kompetencekrav kræver din handling";
    }

    private String competenceUrl() {
        if (applicationBaseUrl == null || applicationBaseUrl.isBlank()) {
            log.warn("competence-due-notifier: no application base URL configured — "
                    + "sending the DM without a link");
            return null;
        }
        String base = applicationBaseUrl.trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/knowledge/competence";
    }

    // -----------------------------------------------------------------------

    /**
     * The scheduler calls this outside any request or transaction context, so every
     * database phase opens its own — the same shape the recruitment digests use.
     */
    private <T> T inTx(Supplier<T> work) {
        return QuarkusTransaction.requiringNew().call(work::get);
    }
}
