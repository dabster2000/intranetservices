package dk.trustworks.intranet.aggregates.crm.slack.services;

import com.slack.api.methods.SlackApiException;
import dk.trustworks.intranet.aggregates.crm.account.model.ClientAccount;
import dk.trustworks.intranet.aggregates.crm.slack.ai.AccountSlackDigestPrompts;
import dk.trustworks.intranet.aggregates.crm.slack.dto.SlackDigestContent;
import dk.trustworks.intranet.aggregates.crm.slack.model.AccountSlackDigest;
import dk.trustworks.intranet.aggregates.crm.slack.model.AccountSlackDigestParticipant;
import dk.trustworks.intranet.aggregates.crm.slack.model.SlackSyncRun;
import dk.trustworks.intranet.aggregates.crm.slack.model.enums.SlackSyncLane;
import dk.trustworks.intranet.aggregates.crm.slack.model.enums.SlackSyncTrigger;
import dk.trustworks.intranet.communicationsservice.services.SlackChannelAccessException;
import dk.trustworks.intranet.communicationsservice.services.SlackConfigurationException;
import dk.trustworks.intranet.communicationsservice.services.SlackService;
import dk.trustworks.intranet.communicationsservice.services.SlackService.SlackChannelMessage;
import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.domain.user.entity.User;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;

/**
 * Reads every linked account Slack space and turns each complete day into one digest
 * row (CRM spec §3.2, §4.9).
 *
 * <h2>Pull, not push</h2>
 * A scheduled OUTBOUND read of {@code conversations.history}. The reason this feature was
 * never built is written in {@code AccountActivityService}'s old javadoc: Slack inbound
 * has no staging signing secret. An outbound read has no signature to verify, so this is
 * fully testable in staging. The inbound chassis stays reserved for {@code /signal} and the
 * buttons, which are not in this cut.
 *
 * <h2>What it reads, and what it keeps</h2>
 * Human messages only: anything with a Slack {@code subtype} (joins, leaves, topic
 * changes, bot posts) is dropped before it is counted. Threads are opened: the discussion
 * in these channels lives in replies, and {@code conversations.history} does not return
 * those, so every thread parent with a reply newer than the cursor is read with
 * {@code conversations.replies} — including parents up to {@value #THREAD_LOOKBACK_DAYS}
 * days older than the cursor, because a reply today to a thread from last week is
 * today's activity. The text is rendered (mentions → first names, links → labels) and
 * handed to {@link AccountSlackDigestService} in memory. <b>Nothing here persists a
 * message.</b> What is persisted is the counts, the participants, the newest {@code ts}
 * as a cursor, and the model's validated reading.
 *
 * <h2>Whole days only</h2>
 * The job runs at 02:25. Messages from today (Copenhagen) are left unread and the cursor
 * stays before them, so a day is digested exactly once, complete, the night after it
 * happened. The spec's "18:00 summary" would cut the afternoon off; a nightly digest of
 * yesterday covers all of it.
 *
 * <h2>Name → id, by this job</h2>
 * {@code slack_space} is a name a person typed. A typo, a rename or a channel the bot has
 * not been invited to must not block saving the field and must not fail silently either
 * — so the job resolves the name, stores the id, and records {@code NOT_FOUND} /
 * {@code NOT_IN_CHANNEL} / {@code ARCHIVED} on the account for the header to show. One
 * {@code conversations.list} per run covers every unresolved account.
 *
 * <h2>Transactions and Slack</h2>
 * A Slack or model round trip is never made while a transaction is open — the §P9 M1
 * rule the calendar sync and the signal extractor enforce. Each account is: read (no
 * transaction) → digest (no transaction) → persist (its own short transaction, per day).
 *
 * <h2>Failure posture</h2>
 * A channel the bot cannot read is recorded on that account and the loop continues. A
 * transient Slack or model failure is counted and the loop continues; the cursor did not
 * move, so the next night re-reads the same window. A {@link SlackConfigurationException}
 * — a missing scope, a revoked token — is the same answer for every account, so the run
 * stops at the first one and says so at ERROR.
 *
 * <h2>What the run left behind</h2>
 * Every pass opens and closes a {@code crm_slack_sync_run} row through
 * {@link SlackSyncRunService}, which also holds the lane lock. The counters were only ever
 * a log line before that, and "did it go last night" is a question an admin asks in a
 * browser rather than in CloudWatch.
 */
@JBossLog
@ApplicationScoped
public class AccountSlackSyncService {

    static final ZoneId ZONE = ZoneId.of("Europe/Copenhagen");

    /** A channel with no digest yet is read this far back — enough for the lane to mean something on day one. */
    static final int FIRST_RUN_BACK_DAYS = 14;

    /** Thread parents this much older than the cursor are still opened for new replies. */
    static final int THREAD_LOOKBACK_DAYS = 7;

    /**
     * The most complete days digested per account per run. A channel whose job has not
     * run for a month is caught up over a few nights rather than in one run of thirty
     * model calls; the cursor advances with every day persisted, so nothing is lost.
     */
    static final int MAX_DAYS_PER_RUN = 14;

    static final String ERROR_NOT_FOUND = "NOT_FOUND";
    static final String ERROR_NOT_IN_CHANNEL = "NOT_IN_CHANNEL";
    static final String ERROR_ARCHIVED = "ARCHIVED";

    /** What a run that stopped on a misconfigured Slack app writes into {@code failure_code}. */
    static final String FAILURE_SLACK_CONFIGURATION = "SLACK_CONFIGURATION";

    private static final DateTimeFormatter WALL_CLOCK = DateTimeFormatter.ofPattern("HH:mm");

    @Inject
    SlackService slackService;

    @Inject
    AccountSlackDigestService digestService;

    @Inject
    AccountSlackFeatureFlag featureFlag;

    @Inject
    SlackSyncRunService runService;

    /**
     * What one run did, for the log line, for a manual trigger to report, and for the
     * {@code crm_slack_sync_run} row.
     *
     * <p>Both Slack lanes fill this same record, which is why two of its fields look idle
     * from here. {@code unmatched} is always zero on this lane — a client's own space is
     * already attached to a client, so there is no company left over to file as a hint — and
     * {@code failureCode} is only ever set alongside {@code stoppedOnConfiguration}. One
     * shape rather than two is what lets {@link SlackSyncRunService#finish} close either
     * lane's row without asking which lane it is looking at.
     *
     * <p>{@code accounts} is the lane's own word for what it set out to read: account spaces
     * here, listed source channels on the other lane. The column both land in is
     * {@code channels}.
     */
    public record SyncSummary(int accounts, int channelsRead, int daysDigested, int readings,
                              int unmatched, int linkErrors, int failures,
                              boolean stoppedOnConfiguration, String failureCode) {
        static SyncSummary nothing() {
            return new SyncSummary(0, 0, 0, 0, 0, 0, 0, false, null);
        }
    }

    /** An account with a Slack space, as read in one short transaction before any Slack call. */
    record LinkedAccount(String clientUuid, String clientName, String slackSpace, String channelId) {
        LinkedAccount withChannelId(String id) {
            return new LinkedAccount(clientUuid, clientName, slackSpace, id);
        }
    }

    /**
     * One full pass over every linked account, and the {@code crm_slack_sync_run} row that
     * records it.
     *
     * <p>The trigger and the actor are arguments rather than something the job knows on its
     * own because only one of the two kinds of run has a person behind it. {@code started_by}
     * is the only way to ask, a month later, who set a particular run going, and a run nobody
     * can attribute is a run nobody owns; the job passes {@link SlackSyncTrigger#SCHEDULED}
     * and no actor.
     *
     * <p>The lane is taken first. {@code ConcurrentExecution.SKIP} on the job keeps two
     * nightly runs apart and can do nothing at all about a run started through
     * {@code POST /crm/slack/sync/ACCOUNT_SPACES} at 02:26 — so a scheduled run that finds
     * the lane held stands down, which is precisely the case the scheduler cannot cover. A
     * manual run arrives here from {@link SlackSyncRunService#runAsync} with the lane already
     * taken on the request thread, and that is the one time a held lane is not a reason to
     * stop: the holder <em>is</em> this run, and it gives the lane back itself.
     *
     * <p>The flag is read before the run row is opened, so a switched-off lane leaves no row
     * — there is no run to record.
     */
    public SyncSummary syncAll(SlackSyncTrigger trigger, String actor) {
        boolean tookLane = runService.tryAcquire(SlackSyncLane.ACCOUNT_SPACES);
        if (!tookLane && trigger == SlackSyncTrigger.SCHEDULED) {
            log.info("Account Slack sync: a manual run holds this lane — the nightly job stands down");
            return SyncSummary.nothing();
        }
        try {
            boolean enabled = QuarkusTransaction.requiringNew().call(featureFlag::isEnabled);
            if (!enabled) {
                log.info("Account Slack sync is switched off (crm.slack.account-spaces.enabled=false)");
                return SyncSummary.nothing();
            }
            SlackSyncRun run = runService.start(SlackSyncLane.ACCOUNT_SPACES, trigger, actor);
            SyncSummary summary;
            try {
                summary = sync();
            } catch (RuntimeException e) {
                // The per-account handling below catches everything it can; anything that got
                // past it fell over the run itself, and the row says so rather than staying
                // RUNNING for ever.
                runService.fail(run, SlackSyncRunService.FAILURE_UNEXPECTED);
                throw e;
            }
            runService.finish(run, summary);
            return summary;
        } finally {
            if (tookLane) {
                runService.release(SlackSyncLane.ACCOUNT_SPACES);
            }
        }
    }

    /** The pass itself, once the flag, the lane and the run row have been dealt with. */
    private SyncSummary sync() {
        List<LinkedAccount> accounts = QuarkusTransaction.requiringNew().call(this::linkedAccounts);
        if (accounts.isEmpty()) {
            log.info("Account Slack sync: no account has a Slack space linked");
            return SyncSummary.nothing();
        }

        Map<String, String> channelIds = null; // listed once, lazily, only if something needs resolving
        int channelsRead = 0;
        int daysDigested = 0;
        int readings = 0;
        int linkErrors = 0;
        int failures = 0;
        boolean stopped = false;

        for (LinkedAccount account : accounts) {
            try {
                if (account.channelId() == null) {
                    if (channelIds == null) {
                        channelIds = slackService.listChannelIdsByName();
                    }
                    String id = channelIds.get(account.slackSpace().toLowerCase(Locale.ROOT));
                    if (id == null) {
                        recordLinkError(account.clientUuid(), ERROR_NOT_FOUND);
                        linkErrors++;
                        // The client uuid, not the typed name: the name is free text a person
                        // entered, and the module's discipline is ids and counts in the log.
                        log.infof("Account Slack sync: the Slack space linked to client %s was not found — recorded NOT_FOUND",
                                account.clientUuid());
                        continue;
                    }
                    recordChannelId(account.clientUuid(), id);
                    account = account.withChannelId(id);
                }
                AccountResult result = syncAccount(account);
                channelsRead++;
                daysDigested += result.days();
                readings += result.readings();
            } catch (SlackChannelAccessException e) {
                String code = switch (e.getSlackError()) {
                    case "is_archived" -> ERROR_ARCHIVED;
                    case "channel_not_found" -> ERROR_NOT_FOUND;
                    default -> ERROR_NOT_IN_CHANNEL;
                };
                recordLinkError(account.clientUuid(), code);
                linkErrors++;
                log.infof("Account Slack sync: channel %s for client %s answered %s — recorded %s",
                        account.channelId(), account.clientUuid(), e.getSlackError(), code);
            } catch (SlackConfigurationException e) {
                // The same answer for every account; say it once and stop.
                log.errorf("Account Slack sync stopped: the Slack app is misconfigured — %s", e.getMessage());
                stopped = true;
                break;
            } catch (IOException | SlackApiException | RuntimeException e) {
                failures++;
                // The message, not the stack, and never the payload: a Slack error body can
                // echo channel content.
                log.warnf("Account Slack sync failed for client %s: %s", account.clientUuid(), e.getMessage());
            }
        }

        log.infof("Account Slack sync done: accounts=%d channelsRead=%d days=%d readings=%d linkErrors=%d failures=%d%s",
                accounts.size(), channelsRead, daysDigested, readings, linkErrors, failures,
                stopped ? " STOPPED on configuration" : "");
        // No unmatched companies from this lane: see SyncSummary for why the field is here.
        return new SyncSummary(accounts.size(), channelsRead, daysDigested, readings, 0, linkErrors, failures,
                stopped, stopped ? FAILURE_SLACK_CONFIGURATION : null);
    }

    record AccountResult(int days, int readings) { }

    /**
     * One account: read the channel (no transaction), open its threads, group by day,
     * digest each complete day, persist each day in its own short transaction.
     */
    AccountResult syncAccount(LinkedAccount account) throws IOException, SlackApiException {
        String channelId = account.channelId();
        String cursor = QuarkusTransaction.requiringNew().call(() -> cursorFor(account.clientUuid()));

        long nowSec = Instant.now().getEpochSecond();
        long todayStartSec = LocalDate.now(ZONE).atStartOfDay(ZONE).toEpochSecond();
        double sinceSec = cursor == null ? nowSec - FIRST_RUN_BACK_DAYS * 86_400L : parseTs(cursor);

        List<SlackChannelMessage> history = slackService.readChannelHistory(channelId,
                formatTs(sinceSec - THREAD_LOOKBACK_DAYS * 86_400L), formatTs(todayStartSec));

        String sinceTs = formatTs(sinceSec);
        String latestTs = formatTs(todayStartSec);
        Map<LocalDate, DayBundle> days = collect(history, sinceSec, todayStartSec,
                parentTs -> slackService.readThreadReplies(channelId, parentTs, sinceTs, latestTs));

        // The read succeeded; a stale NOT_IN_CHANNEL from an earlier night is cleared even
        // when the window held nothing.
        LocalDateTime now = LocalDateTime.now();
        QuarkusTransaction.requiringNew().run(() -> markSynced(account.clientUuid(), now));

        int digested = 0;
        int readings = 0;
        for (DayBundle day : days.values()) {
            if (digested >= MAX_DAYS_PER_RUN) {
                log.infof("Account Slack sync: client %s has more than %d days pending — the rest wait for tomorrow",
                        account.clientUuid(), MAX_DAYS_PER_RUN);
                break;
            }
            Map<String, Colleague> colleagues = QuarkusTransaction.requiringNew()
                    .call(() -> resolveColleagues(day.slackUserIds()));
            List<String> participantNames = day.participantsByActivity().stream()
                    .map(colleagues::get)
                    .filter(c -> c != null)
                    .map(Colleague::firstName)
                    .toList();
            List<AccountSlackDigestPrompts.Line> lines = linesFor(day,
                    id -> colleagues.containsKey(id) ? colleagues.get(id).firstName() : null);

            Optional<SlackDigestContent> content = digestService.digest(
                    account.clientName(), account.slackSpace(), day.date(), participantNames, lines);
            if (content.isPresent()) {
                readings++;
            }
            String permalink = slackService.getPermalinkAsAdmin(channelId, day.lastTs());

            QuarkusTransaction.requiringNew().run(() ->
                    persistDay(account, day, colleagues, content.orElse(null), permalink, now));
            digested++;
        }
        return new AccountResult(digested, readings);
    }

    // ------------------------------------------------------------------------
    // Pure collection — package-private, tested in the fast tier
    // ------------------------------------------------------------------------

    /** How a thread's replies are fetched; a lambda over {@link SlackService} in production. */
    @FunctionalInterface
    interface ThreadReader {
        List<SlackChannelMessage> replies(String parentTs) throws IOException, SlackApiException;
    }

    /** One human message in the window, with its thread parent when it is a reply. */
    record Msg(String ts, String user, String text, boolean reply, SlackChannelMessage parent) {
        double seconds() {
            return parseTs(ts);
        }
    }

    /**
     * One Copenhagen day of a channel: the messages in order, and the bookkeeping the row
     * needs. {@code messages} is what goes to the model and is never persisted.
     */
    record DayBundle(LocalDate date, List<Msg> messages) {
        int topLevelCount() {
            return (int) messages.stream().filter(m -> !m.reply()).count();
        }

        int replyCount() {
            return (int) messages.stream().filter(Msg::reply).count();
        }

        String lastTs() {
            return messages.get(messages.size() - 1).ts();
        }

        Set<String> slackUserIds() {
            Set<String> ids = new HashSet<>();
            for (Msg m : messages) {
                if (m.user() != null) {
                    ids.add(m.user());
                }
            }
            return ids;
        }

        /** Slack user ids by message count, most active first. */
        List<String> participantsByActivity() {
            return messageCounts().entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                            .thenComparing(Map.Entry.comparingByKey()))
                    .map(Map.Entry::getKey)
                    .toList();
        }

        Map<String, Integer> messageCounts() {
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (Msg m : messages) {
                if (m.user() != null) {
                    counts.merge(m.user(), 1, Integer::sum);
                }
            }
            return counts;
        }
    }

    /**
     * Splits a history read into complete days after the cursor.
     *
     * <p>A top-level message is kept when {@code sinceSec < ts < todayStartSec}. A thread
     * parent — inside that window or up to the lookback before it — whose newest reply is
     * after the cursor has its replies fetched, and each reply is kept by the same rule.
     * Bot posts and subtyped events are dropped before either test.
     */
    static Map<LocalDate, DayBundle> collect(List<SlackChannelMessage> history, double sinceSec,
                                            long todayStartSec, ThreadReader threads)
            throws IOException, SlackApiException {
        List<Msg> window = new ArrayList<>();
        for (SlackChannelMessage message : history) {
            if (!isHuman(message) || message.ts() == null) {
                continue;
            }
            double ts = parseTs(message.ts());
            if (ts > sinceSec && ts < todayStartSec) {
                window.add(new Msg(message.ts(), message.user(), message.text(), false, null));
            }
            if (message.replyCount() > 0 && message.latestReply() != null
                    && parseTs(message.latestReply()) > sinceSec) {
                for (SlackChannelMessage reply : threads.replies(message.ts())) {
                    if (!isHuman(reply) || reply.ts() == null || reply.ts().equals(message.ts())) {
                        continue;
                    }
                    double rts = parseTs(reply.ts());
                    if (rts > sinceSec && rts < todayStartSec) {
                        window.add(new Msg(reply.ts(), reply.user(), reply.text(), true, message));
                    }
                }
            }
        }
        window.sort(Comparator.comparingDouble(Msg::seconds).thenComparing(Msg::ts));

        Map<LocalDate, List<Msg>> byDate = new TreeMap<>();
        for (Msg m : window) {
            byDate.computeIfAbsent(dateOf(m.seconds()), key -> new ArrayList<>()).add(m);
        }
        Map<LocalDate, DayBundle> days = new LinkedHashMap<>();
        for (Map.Entry<LocalDate, List<Msg>> entry : byDate.entrySet()) {
            days.put(entry.getKey(), new DayBundle(entry.getKey(), List.copyOf(entry.getValue())));
        }
        return days;
    }

    /**
     * The day as the model reads it. A reply whose parent is not itself one of the day's
     * messages gets the parent rendered once above it as {@code [earlier]} context, so
     * "Perfekt!!!" is a reply to something rather than a line on its own.
     */
    static List<AccountSlackDigestPrompts.Line> linesFor(DayBundle day, Function<String, String> firstNameBySlackId) {
        List<AccountSlackDigestPrompts.Line> lines = new ArrayList<>();
        Set<String> dayTs = new HashSet<>();
        for (Msg m : day.messages()) {
            dayTs.add(m.ts());
        }
        Set<String> contextShown = new HashSet<>();
        for (Msg m : day.messages()) {
            if (m.reply() && m.parent() != null && !dayTs.contains(m.parent().ts())
                    && contextShown.add(m.parent().ts())) {
                lines.add(new AccountSlackDigestPrompts.Line(null,
                        authorName(m.parent().user(), firstNameBySlackId),
                        AccountSlackDigestPrompts.renderSlackMarkup(m.parent().text(), firstNameBySlackId),
                        false, true));
            }
            lines.add(new AccountSlackDigestPrompts.Line(
                    wallClock(m.seconds()),
                    authorName(m.user(), firstNameBySlackId),
                    AccountSlackDigestPrompts.renderSlackMarkup(m.text(), firstNameBySlackId),
                    m.reply(), false));
        }
        return lines;
    }

    private static String authorName(String slackUserId, Function<String, String> firstNameBySlackId) {
        String name = slackUserId == null || firstNameBySlackId == null ? null : firstNameBySlackId.apply(slackUserId);
        return name == null || name.isBlank() ? "Colleague" : name;
    }

    /** Human means: no subtype at all. Joins, leaves, topic changes and bot posts all carry one. */
    static boolean isHuman(SlackChannelMessage message) {
        return message != null && message.subtype() == null && message.text() != null && !message.text().isBlank();
    }

    static double parseTs(String ts) {
        return Double.parseDouble(ts);
    }

    static String formatTs(double seconds) {
        return String.format(Locale.ROOT, "%.6f", seconds);
    }

    static LocalDate dateOf(double seconds) {
        return Instant.ofEpochSecond((long) seconds).atZone(ZONE).toLocalDate();
    }

    static String wallClock(double seconds) {
        return Instant.ofEpochSecond((long) seconds).atZone(ZONE).toLocalTime().format(WALL_CLOCK);
    }

    /**
     * A stable 36-character id for (client, day) — SHA-1 over the pair, formatted as a
     * uuid, exactly as {@code AccountCalendarSyncService.deterministicUuid} does for
     * (event, mailbox). A random uuid would make every re-sync insert a second row for a
     * day it has already digested.
     */
    static String digestUuid(String clientUuid, LocalDate date) {
        return deterministicUuid(clientUuid + "|" + date);
    }

    /**
     * The same kind of id for (client, channel, day) — the source-channel lane's key.
     *
     * <p>Three parts and not two, because that lane inverts the first one's arithmetic: one
     * general channel talks about many clients, and one client gets talked about in several
     * channels on the same day. {@link #digestUuid} hashes {@code clientUuid + "|" + date}
     * and has nowhere to put the channel, so handing it a mention would land two channels'
     * readings of the same client on one id and silently let the second overwrite the first.
     * A sibling that hashes the full key is the only thing the unique constraint will accept.
     */
    static String mentionUuid(String clientUuid, String channelId, LocalDate date) {
        return deterministicUuid(clientUuid + "|" + channelId + "|" + date);
    }

    /** SHA-1 over a natural key, worn as a uuid. Shared so the two keys cannot drift apart. */
    private static String deterministicUuid(String key) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(key.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 16; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16)
                    + "-" + hex.substring(16, 20) + "-" + hex.substring(20, 32);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 unavailable", e);
        }
    }

    // ------------------------------------------------------------------------
    // Persistence — every method below runs inside a short transaction opened by the caller
    // ------------------------------------------------------------------------

    /** A Trustworks person behind a Slack member id. */
    record Colleague(String uuid, String firstName) { }

    List<LinkedAccount> linkedAccounts() {
        List<ClientAccount> accounts = ClientAccount.list("slackSpace is not null order by clientUuid");
        List<LinkedAccount> linked = new ArrayList<>();
        for (ClientAccount account : accounts) {
            if (account.getSlackSpace() == null || account.getSlackSpace().isBlank()) {
                continue;
            }
            Client client = Client.findById(account.getClientUuid());
            String name = client == null || client.getName() == null ? "the client" : client.getName();
            linked.add(new LinkedAccount(account.getClientUuid(), name,
                    account.getSlackSpace().trim(), account.getSlackChannelId()));
        }
        return linked;
    }

    /** The newest {@code ts} already digested for the client, or null on the first run. */
    String cursorFor(String clientUuid) {
        AccountSlackDigest newest = AccountSlackDigest
                .find("clientUuid = ?1 order by digestDate desc", clientUuid)
                .firstResult();
        return newest == null ? null : newest.getLastMessageTs();
    }

    /**
     * Slack member id → colleague, through {@code user.slackusername} — the column
     * {@code SlackInboundDispatchService} resolves actors from. One query per day, not one
     * per message. An id that maps to nobody is simply absent from the map.
     */
    Map<String, Colleague> resolveColleagues(Set<String> slackUserIds) {
        Map<String, Colleague> byId = new HashMap<>();
        if (slackUserIds == null || slackUserIds.isEmpty()) {
            return byId;
        }
        List<User> users = User.list("slackusername in ?1", new ArrayList<>(slackUserIds));
        for (User user : users) {
            if (user.getSlackusername() == null) {
                continue;
            }
            String first = user.getFirstname() != null && !user.getFirstname().isBlank()
                    ? user.getFirstname().trim() : user.getUsername();
            byId.put(user.getSlackusername(), new Colleague(user.getUuid(), first));
        }
        return byId;
    }

    void recordChannelId(String clientUuid, String channelId) {
        QuarkusTransaction.requiringNew().run(() -> {
            ClientAccount account = ClientAccount.findById(clientUuid);
            if (account != null) {
                account.setSlackChannelId(channelId);
                account.setSlackLinkError(null);
            }
        });
    }

    void recordLinkError(String clientUuid, String code) {
        QuarkusTransaction.requiringNew().run(() -> {
            ClientAccount account = ClientAccount.findById(clientUuid);
            if (account != null) {
                account.setSlackLinkError(code);
            }
        });
    }

    void markSynced(String clientUuid, LocalDateTime now) {
        ClientAccount account = ClientAccount.findById(clientUuid);
        if (account != null) {
            account.setSlackLinkError(null);
            account.setSlackSyncedAt(now);
        }
    }

    /** Upsert one day: the deterministic uuid means a re-sync updates the row instead of adding one. */
    void persistDay(LinkedAccount account, DayBundle day, Map<String, Colleague> colleagues,
                    SlackDigestContent content, String permalink, LocalDateTime now) {
        String uuid = digestUuid(account.clientUuid(), day.date());
        AccountSlackDigest row = AccountSlackDigest.findById(uuid);
        if (row == null) {
            row = new AccountSlackDigest();
            row.setUuid(uuid);
            row.setClientUuid(account.clientUuid());
            row.setDigestDate(day.date());
        }
        row.setChannelId(account.channelId());
        row.setChannelName(account.slackSpace());
        row.setMessageCount(day.topLevelCount());
        row.setThreadReplyCount(day.replyCount());
        row.setLastMessageTs(day.lastTs());
        if (permalink != null) {
            row.setPermalink(permalink);
        }
        if (content != null) {
            row.setSignalType(content.signalType());
            row.setRelevance(content.relevance());
            row.setHeadline(content.headline());
            row.setDigestJson(digestService.toJson(content));
            row.setModel(digestService.model());
            row.setPromptVersion(AccountSlackDigestPrompts.PROMPT_VERSION);
        }
        row.setSyncedAt(now);
        row.persist();

        // Replaced wholesale, as the calendar sync replaces attendees: the set is small and
        // a person whose Slack link was fixed since must not stay missing forever.
        AccountSlackDigestParticipant.delete("digestUuid", uuid);
        Set<String> seenUsers = new HashSet<>();
        for (Map.Entry<String, Integer> entry : day.messageCounts().entrySet()) {
            Colleague colleague = colleagues.get(entry.getKey());
            if (colleague == null || !seenUsers.add(colleague.uuid())) {
                continue; // a Slack id that maps to nobody is dropped, never stored raw
            }
            AccountSlackDigestParticipant participant = new AccountSlackDigestParticipant();
            participant.setUuid(UUID.randomUUID().toString());
            participant.setDigestUuid(uuid);
            participant.setUserUuid(colleague.uuid());
            participant.setMessageCount(entry.getValue());
            participant.persist();
        }
    }
}
