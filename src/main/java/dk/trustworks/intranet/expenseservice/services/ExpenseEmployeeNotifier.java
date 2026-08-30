package dk.trustworks.intranet.expenseservice.services;

import com.slack.api.model.block.LayoutBlock;
import dk.trustworks.intranet.aggregates.users.services.UserService;
import dk.trustworks.intranet.communicationsservice.services.SlackService;
import dk.trustworks.intranet.domain.user.entity.User;
import dk.trustworks.intranet.expenseservice.model.Expense;
import dk.trustworks.intranet.expenseservice.model.ExpenseEmployeeNotification;
import dk.trustworks.intranet.expenseservice.model.ExpenseStateDeriver;
import dk.trustworks.intranet.expenseservice.model.ExpenseEmployeeDigestClaim;
import dk.trustworks.intranet.scheduling.SchedulerShutdownGuard;
import dk.trustworks.intranet.userservice.model.enums.ConsultantType;
import dk.trustworks.intranet.userservice.model.enums.StatusType;
import dk.trustworks.intranet.utils.DateUtils;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.vertx.ConsumeEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static com.slack.api.model.block.Blocks.context;
import static com.slack.api.model.block.Blocks.section;
import static com.slack.api.model.block.composition.BlockCompositions.markdownText;
import static com.slack.api.model.block.element.BlockElements.asContextElements;

/**
 * Tells the employee, in Slack, that one of their expenses is waiting on them.
 *
 * <p><b>The defect this closes.</b> When AI validation or Accounting hands an
 * expense back, the row goes to {@code NEEDS_ATTENTION / EMPLOYEE} and the
 * employee is told nowhere at all. Between 2026-05-18 and 2026-08-30 that
 * happened 334 times and the employee acted on 31 of them; the other 288 were
 * silently absorbed by Accounting overriding the block. The Profile → Expenses
 * tab has shown a perfectly good "N expenses need your attention" banner the
 * whole time — nobody had a reason to go and look at it.
 *
 * <h3>Trigger</h3>
 * Two call sites publish {@value #EVENT_ADDRESS} <em>after commit</em> (via
 * {@link ExpenseService#queueEmployeeAttentionNotification}):
 * {@code ExpenseCreatedConsumer} (AI block) and
 * {@code ExpenseReviewDecisionResource#sendBack} (Accounting send-back). Those
 * are the only two statements in the codebase that set
 * {@code attention_owner = EMPLOYEE}.
 *
 * <p><b>Not the entity hook.</b> {@code Expense.syncDerivedState}
 * ({@code @PrePersist}/{@code @PreUpdate}) has no before/after snapshot, so it
 * cannot tell <em>entering</em> the state from <em>already being in</em> it; it
 * fires at flush rather than commit, so a rolled-back send-back would already
 * have DM'd; and {@code ExpenseResource.updateOne} mutates the still
 * EMPLOYEE-owned entity for some thirty lines before reopening it for
 * revalidation, so a hook there would DM the person who is at that moment
 * fixing the expense.
 *
 * <h3>Idempotency — claim at the grain the message is SENT at</h3>
 * Every claim is an INSERT whose unique key does the work; production runs up to
 * five ECS tasks, so a read-then-write dedupe would race. There are two claims,
 * because there are two message grains:
 *
 * <ul>
 *   <li><b>Initial DM — one per expense.</b>
 *       {@code expense_employee_notification (expense_uuid, episode_at, reminder_seq)}.</li>
 *   <li><b>Weekly digest — one per PERSON.</b>
 *       {@code expense_employee_digest_claim (useruuid, week_key)}, taken
 *       <em>before</em> the per-expense rows. The per-expense claim alone is the
 *       wrong grain here and does not make the sweep safe: {@code @Scheduled} fires
 *       in every JVM and {@code concurrentExecution = SKIP} is per-JVM, so five
 *       tasks would split one person's expenses between them and each send its own
 *       partial digest stating a count that is wrong.</li>
 * </ul>
 *
 * A claim that fails on the unique key means "already sent" and is expected. A
 * claim that fails for any <em>other</em> reason is logged at WARN — reclassifying
 * a database failure as a duplicate would drop the notification with no signal
 * above DEBUG, which is off in every environment.
 *
 * <p>The episode key is the {@code occurred_at} of the decision-log row that
 * handed the expense over — <em>not</em> {@code expenses.attention_since}. That
 * column is set only when null and preserved for as long as the row stays in
 * {@code NEEDS_ATTENTION}, so the sequence <em>AI blocks → employee justifies →
 * Accounting sends back</em> keeps the first timestamp and would suppress the
 * second, entirely legitimate nudge.
 *
 * <h3>Language</h3>
 * Danish for anything a person reads, English for chrome — the convention
 * {@code CompetenceDueNotifier} and {@code HrLetterService} already follow.
 *
 * <h3>Environment</h3>
 * Ships dark ({@code enabled} defaults to false). It cannot be exercised on
 * staging at all: the nightly {@code sp_sync_prod_to_staging} nulls
 * {@code slackusername} for every user but one admin, and staging's Slack token
 * is byte-identical to production's — anything that did send would land in the
 * real workspace. Enable in production only.
 */
@JBossLog
@ApplicationScoped
public class ExpenseEmployeeNotifier {

    /** Vert.x address published after commit by {@link ExpenseService}. */
    public static final String EVENT_ADDRESS = "expense.employee.notify";

    /** Slack rejects the whole message with {@code invalid_blocks} above this per text object. */
    static final int SLACK_TEXT_OBJECT_MAX_CHARS = 3000;

    /** Free text (AI reason, accountant comment) is clamped well below the block limit. */
    static final int REASON_MAX_CHARS = 600;

    /** A reminder is due once an episode has been open this long, and every week after. */
    static final int REMINDER_INTERVAL_DAYS = 7;

    /** Reminder digests never list more than this many rows; the rest are counted. */
    static final int REMINDER_MAX_ROWS = 10;

    /**
     * Highest reminder sequence that is still sent. Past ~2 months the weekly nudge
     * has demonstrably not worked; repeating it forever is nagging, and Accounting
     * already owns the row via {@code EXPENSE_STALE_ALARM} from day 14.
     */
    static final int MAX_REMINDERS = 8;

    private static final String DAY_FORMAT = "dd-MM-yyyy";

    /**
     * Actions in {@code expense_decision_log} that hand an expense to the employee.
     * Bound as a parameter list, never interpolated — house rule, and it keeps the
     * two queries and the digest sweep provably in step.
     */
    private static final List<String> EPISODE_ACTIONS =
            List.of("AI_VALIDATED_REJECTED", "HR_SENT_BACK");

    @Inject
    SlackService slackService;

    @Inject
    UserService userService;

    @Inject
    EntityManager em;

    /**
     * Kill switch. Defaults to <b>false</b> so the change ships dark and is
     * enabled deliberately in production, which is the only environment where it
     * can work at all.
     */
    @ConfigProperty(name = "dk.trustworks.expense.employee-notifier.enabled", defaultValue = "false")
    boolean enabled;

    /**
     * The intranet's public base URL — the same property {@link SlackService}
     * and {@code CompetenceDueNotifier} build their deep links from, so expense
     * links cannot drift from the rest of the outbound Slack traffic.
     */
    @ConfigProperty(name = "quarkus.application.base-url")
    String applicationBaseUrl;

    // -----------------------------------------------------------------------
    // types
    // -----------------------------------------------------------------------

    /** Everything one DM needs, read in a single transaction. */
    record Facts(String uuid, String useruuid, double amount, String description,
                 java.time.LocalDate expenseDate, String attentionKind, String aiRuleId,
                 Double extractedAmountDkk, String reasonText, LocalDateTime episodeAt) {
    }

    /** One line of a reminder digest. */
    record ReminderItem(String uuid, double amount, String description,
                        java.time.LocalDate expenseDate, String attentionKind,
                        LocalDateTime episodeAt, int reminderSeq) {
    }

    /** Outcome of a reminder sweep — returned rather than only logged so the job is assertable. */
    public record SweepSummary(int people, int expenses, int failures) {
    }

    // -----------------------------------------------------------------------
    // initial DM
    // -----------------------------------------------------------------------

    @ConsumeEvent(value = EVENT_ADDRESS, blocking = true)
    public void onEmployeeAttention(String expenseUuid) {
        if (!enabled) {
            log.debugf("expense-employee-notifier disabled: skipping %s", expenseUuid);
            return;
        }
        try {
            Facts facts = inTx(() -> readFacts(expenseUuid));
            if (facts == null) {
                return;
            }
            User user = userService.findById(facts.useruuid(), true);
            if (user == null || user.getSlackusername() == null || user.getSlackusername().isBlank()) {
                // No Slack link, no bookkeeping: the banner on the profile is still
                // there, and a Slack id added later will be picked up by a reminder.
                log.infof("expense-employee-notifier: user %s has no Slack link — skipping expense %s",
                        facts.useruuid(), expenseUuid);
                return;
            }
            if (!claim(expenseUuid, facts.episodeAt(), ExpenseEmployeeNotification.SEQ_INITIAL)) {
                log.debugf("expense-employee-notifier: episode already notified for %s", expenseUuid);
                return;
            }
            try {
                slackService.sendMessage(user, fallbackText(facts), initialBlocks(facts));
                log.infof("expense-employee-notifier: DM sent to %s for expense %s (kind=%s)",
                        user.getUsername(), expenseUuid, facts.attentionKind());
            } catch (Exception sendFailure) {
                // Release the claim so the ledger stays honest: no row means the
                // employee was genuinely not told. It is NOT a retry mechanism — the
                // event does not re-fire, so a failed initial DM is picked up by the
                // Monday digest at day 7 (which queries `expenses`, not this ledger,
                // and claims under seq >= 1). Own transaction: the claim's has committed.
                releaseClaim(expenseUuid, facts.episodeAt(), ExpenseEmployeeNotification.SEQ_INITIAL);
                log.warnf(sendFailure, "expense-employee-notifier: DM to %s for expense %s failed",
                        user.getUsername(), expenseUuid);
            }
        } catch (Exception e) {
            // Never let a notification failure surface to the employee's own request.
            log.warnf(e, "expense-employee-notifier: notification for %s failed", expenseUuid);
        }
    }

    /**
     * Re-reads the expense and its episode. Returns null when the row is gone or
     * has already left {@code NEEDS_ATTENTION / EMPLOYEE} — the employee fixed it
     * between commit and consume, which is a race worth losing quietly.
     */
    Facts readFacts(String expenseUuid) {
        Expense e = Expense.findById(expenseUuid);
        if (e == null) {
            return null;
        }
        if (!ExpenseStateDeriver.NEEDS_ATTENTION.equals(e.getState())
                || !ExpenseStateDeriver.OWNER_EMPLOYEE.equals(e.getAttentionOwner())) {
            return null;
        }
        LocalDateTime episodeAt = latestEpisodeAt(expenseUuid);
        if (episodeAt == null) {
            // No decision-log row (should not happen — both call sites write one in
            // the same transaction). attention_since is a correct key for a FIRST
            // episode, which is the only case that can reach here.
            episodeAt = e.getAttentionSince();
        }
        if (episodeAt == null) {
            log.warnf("expense-employee-notifier: no episode timestamp for %s — skipping", expenseUuid);
            return null;
        }
        return new Facts(e.getUuid(), e.getUseruuid(), e.getAmount() == null ? 0d : e.getAmount(),
                e.getDescription(), e.getExpensedate(), e.getAttentionKind(), e.getAiRuleId(),
                e.getExtractedAmountDkk(), latestReasonText(expenseUuid), episodeAt);
    }

    private LocalDateTime latestEpisodeAt(String expenseUuid) {
        List<?> rows = em.createNativeQuery(
                        "SELECT occurred_at FROM expense_decision_log "
                                + "WHERE expense_uuid = :uuid AND action IN (:actions) "
                                + "ORDER BY occurred_at DESC LIMIT 1")
                .setParameter("uuid", expenseUuid)
                .setParameter("actions", EPISODE_ACTIONS)
                .getResultList();
        return rows.isEmpty() ? null : toLocalDateTime(rows.get(0));
    }

    private String latestReasonText(String expenseUuid) {
        List<?> rows = em.createNativeQuery(
                        "SELECT reason_text FROM expense_decision_log "
                                + "WHERE expense_uuid = :uuid AND action IN (:actions) "
                                + "ORDER BY occurred_at DESC LIMIT 1")
                .setParameter("uuid", expenseUuid)
                .setParameter("actions", EPISODE_ACTIONS)
                .getResultList();
        return rows.isEmpty() || rows.get(0) == null ? null : String.valueOf(rows.get(0));
    }

    private static LocalDateTime toLocalDateTime(Object raw) {
        if (raw instanceof LocalDateTime ldt) return ldt;
        if (raw instanceof java.sql.Timestamp ts) return ts.toLocalDateTime();
        return null;
    }

    /**
     * Coerces a native-query amount to a double.
     *
     * <p><b>{@code expenses.amount} is {@code VARCHAR(36)}, not a numeric column</b> —
     * the JPA entity casts it to {@code Double} at runtime, but a native query hands
     * back the raw {@code String}. Casting it straight to {@link Number} throws
     * {@link ClassCastException} and takes the whole reminder sweep with it, so this
     * accepts both shapes and treats an unparseable value as 0 rather than failing
     * the digest for everybody else in the same run.
     */
    static double toDouble(Object raw) {
        if (raw == null) return 0d;
        if (raw instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(raw.toString().trim());
        } catch (NumberFormatException e) {
            log.warnf("expense-employee-notifier: unparseable amount %s", raw);
            return 0d;
        }
    }

    /** Coerces a native-query date; drivers differ on {@code java.sql.Date} vs {@code LocalDate}. */
    static java.time.LocalDate toLocalDate(Object raw) {
        if (raw == null) return null;
        if (raw instanceof java.time.LocalDate ld) return ld;
        if (raw instanceof java.sql.Date d) return d.toLocalDate();
        if (raw instanceof java.sql.Timestamp ts) return ts.toLocalDateTime().toLocalDate();
        return null;
    }

    // -----------------------------------------------------------------------
    // claim
    // -----------------------------------------------------------------------

    /**
     * Atomically claims one (expense, episode, sequence) for this task.
     *
     * @return false when the unique key rejects the insert — somebody already sent it
     */
    boolean claim(String expenseUuid, LocalDateTime episodeAt, int reminderSeq) {
        try {
            QuarkusTransaction.requiringNew().run(() -> {
                ExpenseEmployeeNotification row = new ExpenseEmployeeNotification();
                row.uuid = UUID.randomUUID().toString();
                row.expenseUuid = expenseUuid;
                row.episodeAt = episodeAt;
                row.notifiedAt = LocalDateTime.now();
                row.channel = "SLACK_DM";
                row.reminderSeq = reminderSeq;
                row.persistAndFlush();
            });
            return true;
        } catch (Exception e) {
            if (isDuplicateKey(e)) {
                // The expected, benign case: another task already sent this one.
                log.debugf("expense-employee-notifier: claim already held for %s seq=%d",
                        expenseUuid, reminderSeq);
            } else {
                // NOT a duplicate — the database refused. Treating this as "already
                // sent" would lose the notification with no signal above DEBUG, which
                // is off in every environment. It must be visible.
                log.warnf(e, "expense-employee-notifier: claim FAILED (not a duplicate) for %s seq=%d",
                        expenseUuid, reminderSeq);
            }
            return false;
        }
    }

    /**
     * True when {@code t}'s cause chain is a unique-key violation.
     *
     * <p>The dedupe contract is specifically {@code ER_DUP_ENTRY}; every other
     * failure (pool exhaustion, failover, lock-wait timeout) must not be silently
     * reclassified as "somebody already sent it".
     */
    static boolean isDuplicateKey(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof java.sql.SQLIntegrityConstraintViolationException) return true;
            if (c instanceof org.hibernate.exception.ConstraintViolationException) return true;
            if (c instanceof java.sql.SQLException sql && sql.getErrorCode() == 1062) return true;
            if (c.getCause() == c) break;
        }
        return false;
    }

    /**
     * Claims the whole digest for one person for one ISO week.
     *
     * <p>This is the claim that matters for the reminder: the per-expense claim is
     * the wrong grain, because the digest is one message per PERSON and five ECS
     * tasks would otherwise split a person's rows and each send a partial DM
     * stating a wrong count.
     *
     * @return false when another task already owns this person's digest this week
     */
    boolean claimDigest(String useruuid, String weekKey) {
        try {
            QuarkusTransaction.requiringNew().run(() -> {
                ExpenseEmployeeDigestClaim row = new ExpenseEmployeeDigestClaim();
                row.uuid = UUID.randomUUID().toString();
                row.useruuid = useruuid;
                row.weekKey = weekKey;
                row.claimedAt = LocalDateTime.now();
                row.persistAndFlush();
            });
            return true;
        } catch (Exception e) {
            if (isDuplicateKey(e)) {
                log.debugf("expense-employee-notifier: digest for %s already claimed in %s",
                        useruuid, weekKey);
            } else {
                log.warnf(e, "expense-employee-notifier: digest claim FAILED for %s in %s",
                        useruuid, weekKey);
            }
            return false;
        }
    }

    /** ISO week key of a run, e.g. {@code 2026-W36}. */
    static String weekKeyOf(LocalDateTime now) {
        java.time.LocalDate d = now.toLocalDate();
        int week = d.get(java.time.temporal.WeekFields.ISO.weekOfWeekBasedYear());
        int year = d.get(java.time.temporal.WeekFields.ISO.weekBasedYear());
        return String.format("%04d-W%02d", year, week);
    }

    private void releaseClaim(String expenseUuid, LocalDateTime episodeAt, int reminderSeq) {
        try {
            QuarkusTransaction.requiringNew().run(() ->
                    ExpenseEmployeeNotification.delete(
                            "expenseUuid = ?1 AND episodeAt = ?2 AND reminderSeq = ?3",
                            expenseUuid, episodeAt, reminderSeq));
        } catch (Exception e) {
            log.warnf(e, "expense-employee-notifier: could not release claim for %s", expenseUuid);
        }
    }

    // -----------------------------------------------------------------------
    // weekly reminder
    // -----------------------------------------------------------------------

    /**
     * Monday 08:00 UTC: one digest DM per person whose expenses have been waiting
     * on them for at least {@value #REMINDER_INTERVAL_DAYS} days, repeating weekly
     * until they act.
     *
     * <p>Belted with {@link SchedulerShutdownGuard} — required, and build-enforced
     * by {@code SchedulerShutdownGuardCoverageTest}. Cross-task safety does not
     * come from {@code SKIP} (which is per-JVM) but from the per-expense claim.
     */
    @Scheduled(cron = "0 0 8 ? * MON",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP,
            skipExecutionIf = SchedulerShutdownGuard.class)
    void weeklyReminder() {
        if (!enabled) {
            log.debug("expense-employee-notifier reminder skipped: disabled");
            return;
        }
        try {
            SweepSummary summary = runReminderSweep(LocalDateTime.now());
            log.infof("expense-employee-notifier reminder: %d people, %d expenses, %d failures",
                    summary.people(), summary.expenses(), summary.failures());
        } catch (Exception e) {
            log.error("expense-employee-notifier reminder sweep failed", e);
        }
    }

    /**
     * One reminder pass. A function of its argument rather than of the wall clock,
     * so the whole sweep is reproducible and assertable.
     */
    public SweepSummary runReminderSweep(LocalDateTime now) {
        Map<String, List<ReminderItem>> byUser = inTx(() -> collectOverdue(now));
        String weekKey = weekKeyOf(now);

        // Only people still employed today are nudged. A leaver whose expense was
        // never closed would otherwise be DM'd every Monday forever — and because
        // findById(uuid, true) is a SHALLOW load that does not populate `statuses`,
        // the check is impossible on that object: it has to be a separate resolved
        // population, exactly as CompetenceDueNotifier.activeEmployees does it.
        Map<String, User> active = activeEmployeesByUuid(now.toLocalDate());

        int people = 0;
        int expenses = 0;
        int failures = 0;
        int skippedInactive = 0;

        for (Map.Entry<String, List<ReminderItem>> entry : byUser.entrySet()) {
            User user = active.get(entry.getKey());
            if (user == null) {
                skippedInactive++;
                continue;
            }
            if (user.getSlackusername() == null || user.getSlackusername().isBlank()) {
                continue;
            }
            // Claim the DIGEST first — this is the grain the message is sent at.
            // Without it, five ECS tasks split this person's rows between them on the
            // per-expense claim and each sends its own partial DM with a wrong count.
            if (!claimDigest(entry.getKey(), weekKey)) {
                continue;
            }
            // Then record each row, so the per-expense ledger stays truthful.
            List<ReminderItem> claimed = new ArrayList<>();
            for (ReminderItem item : entry.getValue()) {
                if (claim(item.uuid(), item.episodeAt(), item.reminderSeq())) {
                    claimed.add(item);
                }
            }
            if (claimed.isEmpty()) {
                continue;
            }
            try {
                slackService.sendMessage(user, reminderFallbackText(claimed), reminderBlocks(claimed));
                people++;
                expenses += claimed.size();
            } catch (Exception e) {
                failures++;
                for (ReminderItem item : claimed) {
                    releaseClaim(item.uuid(), item.episodeAt(), item.reminderSeq());
                }
                log.warnf(e, "expense-employee-notifier: reminder DM to %s failed", user.getUsername());
            }
        }
        // Logged at INFO: a sweep that reaches nobody must not look like a quiet one.
        log.infof("expense-employee-notifier reminder %s: %d candidates, %d inactive skipped, "
                        + "%d people DM'd, %d expenses, %d failures",
                weekKey, byUser.size(), skippedInactive, people, expenses, failures);
        return new SweepSummary(people, expenses, failures);
    }

    /**
     * Everybody still employed on {@code today}, by uuid.
     *
     * <p>"Still employed" is deliberately wider than {@code ACTIVE}: somebody on
     * parental or unpaid leave is coming back and their expense is still theirs to
     * fix, so filtering to ACTIVE alone would silently drop them. Only
     * {@code TERMINATED} and {@code PREBOARDING} are excluded — the former is the
     * whole point (a leaver keeps a populated {@code slackusername}, so nothing
     * else would stop the weekly nudge), the latter has no expenses yet.
     *
     * <p>Loaded <strong>deeply</strong> ({@code shallow = false}) on purpose:
     * {@code User.getUserStatus} synthesises TERMINATED from an empty transient
     * {@code statuses} collection, so a shallow load makes everyone look
     * terminated and the sweep quietly notifies nobody — the worst failure mode a
     * notifier has. {@code CompetenceDueNotifier.activeEmployees} carries the same
     * warning for the same reason.
     */
    Map<String, User> activeEmployeesByUuid(java.time.LocalDate today) {
        String[] statuses = {
                StatusType.ACTIVE.toString(),
                StatusType.PAID_LEAVE.toString(),
                StatusType.MATERNITY_LEAVE.toString(),
                StatusType.NON_PAY_LEAVE.toString(),
        };
        String[] types = java.util.Arrays.stream(ConsultantType.values())
                .map(Enum::toString).toArray(String[]::new);
        Map<String, User> byUuid = new LinkedHashMap<>();
        for (User u : userService.findUsersByDateAndStatusListAndTypes(today, statuses, types, false)) {
            byUuid.put(u.getUuid(), u);
        }
        return byUuid;
    }

    /**
     * Every still-open employee-owned expense whose episode is at least a week
     * old, grouped by user, with the reminder sequence for the current week.
     */
    Map<String, List<ReminderItem>> collectOverdue(LocalDateTime now) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(
                        "SELECT e.uuid, e.useruuid, e.amount, e.description, e.expensedate, "
                                + "       e.attention_kind, "
                                + "       (SELECT MAX(d.occurred_at) FROM expense_decision_log d "
                                + "         WHERE d.expense_uuid = e.uuid AND d.action IN (:actions)) "
                                + "  FROM expenses e "
                                + " WHERE e.state = :state AND e.attention_owner = :owner")
                .setParameter("state", ExpenseStateDeriver.NEEDS_ATTENTION)
                .setParameter("owner", ExpenseStateDeriver.OWNER_EMPLOYEE)
                .setParameter("actions", EPISODE_ACTIONS)
                .getResultList();

        Map<String, List<ReminderItem>> byUser = new LinkedHashMap<>();
        for (Object[] r : rows) {
            String useruuid = (String) r[1];
            if (useruuid == null) continue;
            LocalDateTime episodeAt = toLocalDateTime(r[6]);
            if (episodeAt == null) continue;

            // Calendar days, not Duration.toDays(): the latter truncates, so an
            // episode created at 08:01 on a Monday is "6 days" old at the next
            // Monday 08:00 tick and would silently wait a fortnight for its first
            // reminder rather than a week.
            long days = java.time.temporal.ChronoUnit.DAYS.between(
                    episodeAt.toLocalDate(), now.toLocalDate());
            if (days < REMINDER_INTERVAL_DAYS) continue;
            int seq = (int) (days / REMINDER_INTERVAL_DAYS);
            // Stop escalating eventually. Past this the nudge has demonstrably not
            // worked and repeating it weekly forever is nagging, not notifying —
            // Accounting already owns the row through EXPENSE_STALE_ALARM (14 days).
            if (seq > MAX_REMINDERS) continue;

            byUser.computeIfAbsent(useruuid, k -> new ArrayList<>()).add(new ReminderItem(
                    (String) r[0],
                    toDouble(r[2]),
                    (String) r[3],
                    toLocalDate(r[4]),
                    (String) r[5],
                    episodeAt,
                    seq));
        }
        return byUser;
    }

    // -----------------------------------------------------------------------
    // message composition
    // -----------------------------------------------------------------------

    /**
     * The problem, in Danish, in one line.
     *
     * <p>{@code AMOUNT_MISMATCH} is composed from the two numbers rather than from
     * the stored reason on purpose: {@code ai_validation_reason} on that path
     * holds the policy verdict's message, produced without sight of the entered
     * amount, so quoting it verbatim tells the employee something unrelated to
     * the actual problem. Do not "simplify" this back to the stored text.
     */
    String problemLine(String attentionKind, Double extracted, double declared, String reasonText) {
        if (ExpenseStateDeriver.KIND_AMOUNT_MISMATCH.equals(attentionKind)) {
            if (extracted != null && extracted > 0) {
                return "Kvitteringen ser ud til at lyde på *" + money(extracted)
                        + "*, men udlægget er oprettet med *" + money(declared) + "*.";
            }
            return "Beløbet på kvitteringen stemmer ikke med det beløb, udlægget er oprettet med.";
        }
        if (ExpenseStateDeriver.KIND_RECEIPT.equals(attentionKind)) {
            return "Kvitteringen kunne ikke læses. Der skal et nyt og tydeligt billede til.";
        }
        if (ExpenseStateDeriver.KIND_JUSTIFICATION.equals(attentionKind)
                || ExpenseStateDeriver.KIND_POLICY.equals(attentionKind)) {
            String reason = clamp(reasonText, REASON_MAX_CHARS);
            return reason == null || reason.isBlank()
                    ? "Der mangler en forretningsmæssig begrundelse for udlægget."
                    : "Der mangler en forretningsmæssig begrundelse for udlægget:\n> " + escape(reason);
        }
        String reason = clamp(reasonText, REASON_MAX_CHARS);
        return reason == null || reason.isBlank()
                ? "Udlægget afventer din handling."
                : escape(reason);
    }

    List<LayoutBlock> initialBlocks(Facts f) {
        String url = expenseUrl(f.uuid());
        StringBuilder body = new StringBuilder();
        body.append(":receipt: *Dit udlæg mangler din handling*\n\n")
            .append(describe(f.amount(), f.description(), f.expenseDate()))
            .append("\n\n")
            .append(problemLine(f.attentionKind(), f.extractedAmountDkk(), f.amount(), f.reasonText()));

        List<LayoutBlock> blocks = new ArrayList<>();
        blocks.add(section(s -> s.text(markdownText(clamp(body.toString(), SLACK_TEXT_OBJECT_MAX_CHARS)))));
        if (url != null) {
            blocks.add(context(c -> c.elements(asContextElements(
                    markdownText("<" + url + "|Ret udlægget på din profil>")))));
        } else {
            blocks.add(context(c -> c.elements(asContextElements(
                    markdownText("Ret udlægget under fanen *Expenses* på din profil.")))));
        }
        return blocks;
    }

    List<LayoutBlock> reminderBlocks(List<ReminderItem> items) {
        StringBuilder body = new StringBuilder();
        body.append(items.size() == 1
                        ? ":hourglass_flowing_sand: *Du har stadig et udlæg, der venter på dig*\n\n"
                        : ":hourglass_flowing_sand: *Du har stadig " + items.size()
                                + " udlæg, der venter på dig*\n\n");
        int shown = Math.min(items.size(), REMINDER_MAX_ROWS);
        for (int i = 0; i < shown; i++) {
            ReminderItem it = items.get(i);
            body.append("• ").append(describe(it.amount(), it.description(), it.expenseDate()))
                .append(" — ").append(kindLabel(it.attentionKind())).append('\n');
        }
        if (items.size() > shown) {
            body.append("• … og ").append(items.size() - shown).append(" mere\n");
        }

        String url = profileExpensesUrl();
        List<LayoutBlock> blocks = new ArrayList<>();
        blocks.add(section(s -> s.text(markdownText(clamp(body.toString(), SLACK_TEXT_OBJECT_MAX_CHARS)))));
        if (url != null) {
            blocks.add(context(c -> c.elements(asContextElements(
                    markdownText("<" + url + "|Se dem på din profil>")))));
        }
        return blocks;
    }

    /** Notification/preview text — also the whole message on clients that cannot render blocks. */
    String fallbackText(Facts f) {
        return "Dit udlæg på " + money(f.amount()) + " mangler din handling.";
    }

    String reminderFallbackText(List<ReminderItem> items) {
        return items.size() == 1
                ? "Du har et udlæg, der venter på dig."
                : "Du har " + items.size() + " udlæg, der venter på dig.";
    }

    private String describe(double amount, String description, java.time.LocalDate date) {
        String desc = clamp(description, 120);
        StringBuilder sb = new StringBuilder("*").append(money(amount)).append('*');
        if (desc != null && !desc.isBlank()) {
            sb.append(" — ").append(escape(desc));
        }
        if (date != null) {
            sb.append(" (").append(DateUtils.stringIt(date, DAY_FORMAT)).append(')');
        }
        return sb.toString();
    }

    private static String kindLabel(String attentionKind) {
        if (attentionKind == null) return "afventer din handling";
        return switch (attentionKind) {
            case ExpenseStateDeriver.KIND_AMOUNT_MISMATCH -> "beløbet skal tjekkes";
            case ExpenseStateDeriver.KIND_RECEIPT -> "kvitteringen skal uploades igen";
            case ExpenseStateDeriver.KIND_JUSTIFICATION, ExpenseStateDeriver.KIND_POLICY -> "begrundelse mangler";
            default -> "afventer din handling";
        };
    }

    private static String money(double amount) {
        return String.format(java.util.Locale.GERMAN, "%,.2f kr.", amount);
    }

    /**
     * Escapes the three characters Slack treats as markup control characters in
     * {@code mrkdwn}. Expense descriptions and accountant comments are free text.
     */
    private static String escape(String s) {
        return s == null ? null : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String clamp(String s, int maxChars) {
        if (s == null) return null;
        return s.length() <= maxChars ? s : s.substring(0, maxChars - 1) + "…";
    }

    // -----------------------------------------------------------------------
    // deep links
    // -----------------------------------------------------------------------

    /** {@code /profile?tab=expenses&expense=<uuid>} — opens the tab AND the fix dialog. */
    String expenseUrl(String expenseUuid) {
        String base = profileExpensesUrl();
        return base == null ? null : base + "&expense=" + expenseUuid;
    }

    /** {@code /profile?tab=expenses&segment=action} — the tab, filtered to what needs them. */
    String profileExpensesUrl() {
        if (applicationBaseUrl == null || applicationBaseUrl.isBlank()) {
            log.warn("expense-employee-notifier: no application base URL configured — link omitted");
            return null;
        }
        String base = applicationBaseUrl.trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/profile?tab=expenses&segment=action";
    }

    private <T> T inTx(Supplier<T> work) {
        return QuarkusTransaction.requiringNew().call(work::get);
    }
}
