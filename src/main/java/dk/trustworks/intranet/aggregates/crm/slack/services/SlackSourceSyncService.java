package dk.trustworks.intranet.aggregates.crm.slack.services;

import com.slack.api.methods.SlackApiException;
import dk.trustworks.intranet.aggregates.crm.account.model.ClientAccount;
import dk.trustworks.intranet.aggregates.crm.account.model.enums.AccountBand;
import dk.trustworks.intranet.aggregates.crm.signal.services.AccountSignalService;
import dk.trustworks.intranet.aggregates.crm.slack.ai.AccountSlackDigestPrompts;
import dk.trustworks.intranet.aggregates.crm.slack.ai.SlackMentionPrompts;
import dk.trustworks.intranet.aggregates.crm.slack.dto.SlackDigestContent;
import dk.trustworks.intranet.aggregates.crm.slack.model.AccountSlackMention;
import dk.trustworks.intranet.aggregates.crm.slack.model.AccountSlackMentionParticipant;
import dk.trustworks.intranet.aggregates.crm.slack.model.SlackSourceChannel;
import dk.trustworks.intranet.aggregates.crm.slack.model.SlackSyncRun;
import dk.trustworks.intranet.aggregates.crm.slack.model.enums.SlackSyncLane;
import dk.trustworks.intranet.aggregates.crm.slack.model.enums.SlackSyncTrigger;
import dk.trustworks.intranet.aggregates.crm.slack.services.AccountSlackSyncService.Colleague;
import dk.trustworks.intranet.aggregates.crm.slack.services.AccountSlackSyncService.DayBundle;
import dk.trustworks.intranet.aggregates.crm.slack.services.AccountSlackSyncService.Msg;
import dk.trustworks.intranet.aggregates.crm.slack.services.AccountSlackSyncService.SyncSummary;
import dk.trustworks.intranet.aggregates.crm.slack.services.SlackMentionExtractionService.Extraction;
import dk.trustworks.intranet.aggregates.crm.slack.services.SlackUnmatchedCompanyService.UnmatchedCompanySighting;
import dk.trustworks.intranet.communicationsservice.services.SlackChannelAccessException;
import dk.trustworks.intranet.communicationsservice.services.SlackConfigurationException;
import dk.trustworks.intranet.communicationsservice.services.SlackService;
import dk.trustworks.intranet.communicationsservice.services.SlackService.SlackChannelMessage;
import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.dao.crm.services.ClientService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Reads the general Slack channels an admin listed and files what each complete day of
 * them said about a client account (source-channel spec §4.1, §4.3, §4.5).
 *
 * <h2>The account-space lane, pointed the other way round</h2>
 * {@link AccountSlackSyncService} reads a channel that is about one client and summarises
 * the day whole. A channel like {@code #ledelse} is about nothing in particular: leadership
 * chatter, a holiday photo, a colleague in A&amp;E, and in the middle of it two sentences
 * that belong on two accounts. So the pipeline is the same — read with no transaction,
 * extract with no transaction, persist in one short transaction per day — and only the
 * question put to the model changes, from "what happened on this account today" to "which
 * of these sentences are about an account at all".
 *
 * <p>Everything that can be shared with the first lane IS shared rather than copied:
 * {@code collect} splits the history into complete days, {@code linesFor} renders them,
 * {@code resolveColleagues} maps Slack member ids to people, and
 * {@code parseTs}/{@code formatTs}/{@code dateOf}/{@code ZONE} decide where a day begins
 * and ends. Two implementations of "which Copenhagen day is this message on" would be two
 * different answers on the last night of March.
 *
 * <h2>What is stored, and what is emphatically not</h2>
 * <b>No message text, anywhere, ever.</b> A general channel carries colleagues' health,
 * whereabouts, absences and jokes in between the sentences about accounts; all of it lives
 * in this JVM for the length of one model call and is then gone. What is written is the
 * validated reading, the colleagues who wrote the lines it cites, a count and one
 * permalink. A company the model names that no client matches becomes a hint row with a
 * name and numbers — never a sentence.
 *
 * <h2>Where the cursor lives, and why</h2>
 * On the channel row, not on what the run produced. A complete day may correctly produce no
 * mention at all — most days of most channels do — so a lane resuming from "the newest row
 * written" would re-read that day for ever. {@code cursor_ts} advances in the SAME
 * transaction as the day's rows, so a run killed half way resumes at the first day it did
 * not finish and a day already written is only ever re-upserted onto its deterministic id.
 *
 * <p>Because the cursor is one watermark and not a set of days, a day the model could not
 * be read for STOPS the channel for the night. Skipping it and closing the day after it
 * would move the watermark past the failed day, which no later run could ever get back
 * behind: the window is {@code ts > cursor}, so a single rate-limited call at 02:40 would
 * discard that day's mentions permanently, behind a run row still reporting DONE. Stopping
 * costs a night; advancing costs the day.
 *
 * <p>A cursor older than {@value #STALE_CURSOR_DAYS} days is reset rather than honoured
 * (D15). A channel switched off in June and back on in September is not a summer worth
 * reading at fourteen model calls a night; the gap is skipped, said out loud in the log,
 * and the channel starts again a fortnight back. That reset is also what keeps a day the
 * model will never manage to read from wedging a channel for good — a month of failing at
 * the same day and the channel starts again in front of it.
 *
 * <h2>Failure posture</h2>
 * The same three tiers as the account-space lane. A channel the bot cannot read gets its
 * verdict recorded on its own row and the loop continues — the fix is an invitation in
 * Slack and the next run re-checks it. A transient Slack or model failure is counted into
 * {@code failures} so the run row and the settings tab say so, the cursor does not move,
 * and the same window is re-read tomorrow. A {@link SlackConfigurationException} is the
 * same answer for every channel, so it is said once at ERROR and the run stops.
 */
@JBossLog
@ApplicationScoped
public class SlackSourceSyncService {

    /**
     * Who a hint created without a person is attributed to. Not null and not blank: the
     * decision columns exist so that a company appearing in the CRM can always be traced
     * to whoever put it there, and "the nightly job did" is an answer.
     */
    static final String SYSTEM_ACTOR = "system";

    /**
     * Deliberately the account-space lane's numbers rather than copies of them: the two
     * lanes read the same workspace with the same token and the same idea of a day, and a
     * lookback that differed between them would mean a thread reply reaching one lane and
     * not the other for no reason anybody could name.
     */
    static final int FIRST_RUN_BACK_DAYS = AccountSlackSyncService.FIRST_RUN_BACK_DAYS;
    static final int THREAD_LOOKBACK_DAYS = AccountSlackSyncService.THREAD_LOOKBACK_DAYS;
    static final int MAX_DAYS_PER_RUN = AccountSlackSyncService.MAX_DAYS_PER_RUN;

    /**
     * A cursor this old is not resumed from (D15). Past a month the channel was paused,
     * removed and re-added, or the job has been broken long enough that somebody has
     * noticed; in all three cases reading the gap costs a model call per day and buys
     * history nobody is waiting for.
     */
    static final int STALE_CURSOR_DAYS = 30;

    private static final long SECONDS_PER_DAY = 86_400L;

    @Inject
    SlackService slackService;

    @Inject
    AccountSlackFeatureFlag featureFlag;

    @Inject
    SlackSyncRunService runService;

    @Inject
    SlackMentionExtractionService extractionService;

    @Inject
    AccountSlackDigestService digestService;

    @Inject
    SlackUnmatchedCompanyService unmatchedService;

    @Inject
    ClientService clientService;

    /**
     * The colleague list, for the one rule that needs it: a company name that is really a
     * colleague's is thrown away rather than filed as a prospect. Injected rather than
     * re-queried because this list is already built, cached and bounded there, and a second
     * "who counts as a colleague" would be a second answer to a question V593 settled.
     */
    @Inject
    AccountSignalService signalService;

    /**
     * Reached only for {@code resolveColleagues}, which is an instance method needing an
     * open transaction. Everything else this lane borrows from the first one is static.
     */
    @Inject
    AccountSlackSyncService accountSlackSyncService;

    /** A listed channel, as read in one short transaction before any Slack call. */
    record SourceChannel(String uuid, String channelId, String channelName, String cursorTs) {
        /** The same channel under the name Slack spells it now — see {@link #renamedTo}. */
        SourceChannel withName(String name) {
            return new SourceChannel(uuid, channelId, name, cursorTs);
        }
    }

    /**
     * What one run may attribute a mention to, read once before any Slack or model call.
     *
     * <p>One record and one transaction because the three parts are one decision: which
     * accounts the model is allowed to name, which spellings resolve to which of them, and
     * which names are colleagues and therefore not companies at all. Reading them
     * separately would let a hint linked half way through a run change the answer between
     * two channels of the same night.
     *
     * @param accounts       ordered; only the first
     *                       {@link SlackMentionPrompts#MAX_ACCOUNTS_IN_PROMPT} reach the model
     * @param linkedAliases  {@code nameKey} → client uuid for every hint somebody has linked
     * @param colleagueNames every employed colleague's name
     */
    record Matching(List<SlackMentionPrompts.Account> accounts, Map<String, String> linkedAliases,
                    List<String> colleagueNames) { }

    /**
     * What one channel's pass yielded. {@code failures} is 0 or 1: a day the model could not
     * be read for ends the channel's night, because the cursor is one watermark.
     */
    record ChannelResult(int days, int mentions, int unmatched, int failures) { }

    /**
     * One full pass over every enabled source channel, and the {@code crm_slack_sync_run}
     * row that records it.
     *
     * <p>The shape is {@link AccountSlackSyncService#syncAll}'s, deliberately line for line:
     * the lane is taken first, the flag is read before the run row is opened so a
     * switched-off lane leaves no row behind, and a scheduled run that finds the lane held
     * stands down — which is precisely the overlap {@code ConcurrentExecution.SKIP} on the
     * job cannot see, because the other run was started from a browser rather than by the
     * scheduler. A manual run arrives here from {@link SlackSyncRunService#runAsync} holding
     * the lane already, and that is the one time a held lane is not a reason to stop.
     */
    public SyncSummary syncAll(SlackSyncTrigger trigger, String actor) {
        boolean tookLane = runService.tryAcquire(SlackSyncLane.SOURCE_CHANNELS);
        if (!tookLane && trigger == SlackSyncTrigger.SCHEDULED) {
            log.info("Slack source sync: a manual run holds this lane — the nightly job stands down");
            return SyncSummary.nothing();
        }
        try {
            boolean enabled = QuarkusTransaction.requiringNew().call(featureFlag::isSourceChannelsEnabled);
            if (!enabled) {
                log.info("Slack source sync is switched off (crm.slack.source-channels.enabled=false)");
                return SyncSummary.nothing();
            }
            SlackSyncRun run = runService.start(SlackSyncLane.SOURCE_CHANNELS, trigger, actor);
            SyncSummary summary;
            try {
                summary = sync();
            } catch (RuntimeException e) {
                // The per-channel handling below catches everything it can; anything that got
                // past it fell over the run itself, and the row says so rather than staying
                // RUNNING for ever.
                runService.fail(run, SlackSyncRunService.FAILURE_UNEXPECTED);
                throw e;
            }
            runService.finish(run, summary);
            return summary;
        } finally {
            if (tookLane) {
                runService.release(SlackSyncLane.SOURCE_CHANNELS);
            }
        }
    }

    /** The pass itself, once the flag, the lane and the run row have been dealt with. */
    private SyncSummary sync() {
        List<SourceChannel> channels = QuarkusTransaction.requiringNew().call(this::enabledChannels);
        if (channels.isEmpty()) {
            log.info("Slack source sync: no source channel is enabled");
            return SyncSummary.nothing();
        }

        // Before the first Slack call and the first model call, once for the whole run: the
        // block is then byte-identical in every prompt of the night, which is what lets the
        // provider's prefix cache cover it across the day loop.
        Matching matching = QuarkusTransaction.requiringNew().call(this::allowlist);

        int channelsRead = 0;
        int days = 0;
        int mentions = 0;
        int unmatched = 0;
        int linkErrors = 0;
        int failures = 0;
        boolean stopped = false;

        for (SourceChannel channel : channels) {
            try {
                ChannelResult result = syncChannel(channel, matching);
                channelsRead++;
                days += result.days();
                mentions += result.mentions();
                unmatched += result.unmatched();
                failures += result.failures();
            } catch (SlackChannelAccessException e) {
                String code = switch (e.getSlackError()) {
                    case "is_archived" -> AccountSlackSyncService.ERROR_ARCHIVED;
                    case "channel_not_found" -> AccountSlackSyncService.ERROR_NOT_FOUND;
                    default -> AccountSlackSyncService.ERROR_NOT_IN_CHANNEL;
                };
                recordLinkError(channel.uuid(), code);
                linkErrors++;
                log.infof("Slack source sync: channel %s answered %s — recorded %s",
                        channel.channelId(), e.getSlackError(), code);
            } catch (SlackConfigurationException e) {
                // The same answer for every channel; say it once and stop.
                log.errorf("Slack source sync stopped: the Slack app is misconfigured — %s", e.getMessage());
                stopped = true;
                break;
            } catch (IOException | SlackApiException | RuntimeException e) {
                failures++;
                // The message, not the stack, and never the payload: a Slack error body can
                // echo channel content.
                log.warnf("Slack source sync failed for channel %s: %s", channel.channelId(), e.getMessage());
            }
        }

        // The hint counts are recomputed from the sighting ledger once, after every channel
        // has written its sightings — never incremented as they arrive, because the lookback
        // window re-reads the same fortnight every night. The twelve-month purge rides along
        // inside it.
        unmatchedService.refreshAggregates();

        // AFTER the aggregates, because the decision reads the counts and the sighting
        // ledger the refresh just settled. Off by default (V613); when on, it creates only
        // the names the model graded HIGH that match no company we already have, and leaves
        // every other hint for a person. A failure here must not fail a run that has already
        // written its digests, so it is counted and swallowed like a channel failure.
        int prospects = 0;
        try {
            // SYSTEM_ACTOR even on a manual run: whoever pressed Run now asked for the
            // channels to be READ. Creating a company was this rule's decision, not
            // theirs, and attributing it to them would put their name on a judgement
            // they never made.
            prospects = unmatchedService.createProspectsFromConfidentHints(SYSTEM_ACTOR);
        } catch (RuntimeException e) {
            log.warnf("Slack source sync: creating prospects from hints failed (%s)", e.getMessage());
        }

        log.infof("Slack source sync done: channels=%d read=%d days=%d mentions=%d unmatched=%d "
                        + "linkErrors=%d failures=%d prospects=%d%s",
                channels.size(), channelsRead, days, mentions, unmatched, linkErrors, failures, prospects,
                stopped ? " STOPPED on configuration" : "");
        return new SyncSummary(channels.size(), channelsRead, days, mentions, unmatched, linkErrors, failures,
                stopped, stopped ? AccountSlackSyncService.FAILURE_SLACK_CONFIGURATION : null);
    }

    /**
     * One channel: read it (no transaction), open its threads, then take each complete day
     * to the model and persist what came back.
     */
    ChannelResult syncChannel(SourceChannel channel, Matching matching) throws IOException, SlackApiException {
        String channelId = channel.channelId();
        long nowSec = Instant.now().getEpochSecond();
        long todayStartSec = LocalDate.now(AccountSlackSyncService.ZONE)
                .atStartOfDay(AccountSlackSyncService.ZONE).toEpochSecond();
        double firstRunSec = nowSec - FIRST_RUN_BACK_DAYS * SECONDS_PER_DAY;

        double sinceSec = firstRunSec;
        boolean cursorReset = false;
        if (channel.cursorTs() != null) {
            double stored = AccountSlackSyncService.parseTs(channel.cursorTs());
            if (stored < nowSec - STALE_CURSOR_DAYS * SECONDS_PER_DAY) {
                // D15: the gap is skipped on purpose, and said out loud so nobody later reads
                // the missing fortnights as a bug.
                cursorReset = true;
                log.infof("Slack source sync: channel %s was last read more than %d days ago —"
                                + " the gap is skipped and the cursor reset to %d days back",
                        channelId, STALE_CURSOR_DAYS, FIRST_RUN_BACK_DAYS);
            } else {
                sinceSec = stored;
            }
        }

        List<SlackChannelMessage> history = slackService.readChannelHistory(channelId,
                AccountSlackSyncService.formatTs(sinceSec - THREAD_LOOKBACK_DAYS * SECONDS_PER_DAY),
                AccountSlackSyncService.formatTs(todayStartSec));

        String sinceTs = AccountSlackSyncService.formatTs(sinceSec);
        String latestTs = AccountSlackSyncService.formatTs(todayStartSec);
        Map<LocalDate, DayBundle> days = AccountSlackSyncService.collect(history, sinceSec, todayStartSec,
                parentTs -> slackService.readThreadReplies(channelId, parentTs, sinceTs, latestTs));

        // The read succeeded; a stale NOT_IN_CHANNEL from an earlier night is cleared even
        // when the window held nothing. A reset cursor is written here rather than left to
        // the first persisted day, so a channel that has genuinely gone quiet stops
        // announcing the same skip every night.
        LocalDateTime now = LocalDateTime.now();
        String resetCursor = cursorReset ? AccountSlackSyncService.formatTs(sinceSec) : null;
        String renamed = renamedTo(channel.channelName(), currentName(channelId));
        if (renamed != null) {
            // The id and the fact of the rename; neither spelling. This lane's log is ids and
            // counts, and a channel name is something an employee typed.
            log.infof("Slack source sync: channel %s has been renamed in Slack — the stored name is refreshed",
                    channelId);
        }
        QuarkusTransaction.requiringNew().run(() -> markRead(channel.uuid(), now, resetCursor, renamed));
        SourceChannel current = renamed == null ? channel : channel.withName(renamed);

        int read = 0;
        int mentions = 0;
        int unmatched = 0;
        for (DayBundle day : days.values()) {
            if (read >= MAX_DAYS_PER_RUN) {
                log.infof("Slack source sync: channel %s has more than %d days pending — the rest wait for tomorrow",
                        channelId, MAX_DAYS_PER_RUN);
                break;
            }
            DayResult result = syncDay(current, day, matching, now);
            if (result.failed()) {
                // Not "skip this day and carry on": the cursor is one watermark, so closing a
                // later day would put this one permanently outside every future window. The
                // night stops here and tomorrow starts where today did.
                log.warnf("Slack source sync: channel %s could not be read by the model for one day —"
                                + " the cursor stays where it is and the rest of the channel waits for tomorrow",
                        channelId);
                return new ChannelResult(read, mentions, unmatched, 1);
            }
            mentions += result.mentions();
            unmatched += result.unmatched();
            read++;
        }
        return new ChannelResult(read, mentions, unmatched, 0);
    }

    /**
     * What one day's pass yielded, or that it did not happen.
     *
     * <p>{@code failed} is never "the day held nothing about any account" — that is a correct
     * and common answer and closes the day like any other. It is only ever the model call
     * itself not happening, which {@link SlackMentionExtractionService} is the one place that
     * can tell apart, because {@code OpenAIService} reports both as emptiness.
     */
    record DayResult(int mentions, int unmatched, boolean failed) {
        /** Named for what it makes: {@code failed()} is already the component's accessor. */
        static DayResult failure() {
            return new DayResult(0, 0, true);
        }
    }

    /**
     * One complete day: resolve the colleagues, render the day, read it, then write the
     * whole of it in one transaction.
     *
     * <p>Every Slack and model round trip happens before that transaction opens — the §P9 M1
     * rule. The permalinks are the easy one to get wrong: they are one {@code chat.getPermalink}
     * per row and they are resolved up here, not while the rows are being written.
     *
     * <p>A day whose model call failed is written NOWHERE, cursor included. Filing the chunks
     * that did answer would leave a row carrying a fraction of the day and stamped
     * {@code synced_at}, which nothing downstream could ever tell from a complete one; the
     * day is re-read whole on the next run and re-upserted onto the same deterministic id.
     */
    private DayResult syncDay(SourceChannel channel, DayBundle day, Matching matching, LocalDateTime now) {
        // resolveColleagues is an instance method reading the user table, so it needs an open
        // transaction of its own — and it must be closed again before the model call.
        Map<String, Colleague> colleagues = QuarkusTransaction.requiringNew()
                .call(() -> accountSlackSyncService.resolveColleagues(day.slackUserIds()));

        List<String> participantNames = day.participantsByActivity().stream()
                .map(colleagues::get)
                .filter(c -> c != null)
                .map(Colleague::firstName)
                .toList();
        List<AccountSlackDigestPrompts.Line> lines = AccountSlackSyncService.linesFor(day,
                id -> colleagues.containsKey(id) ? colleagues.get(id).firstName() : null);

        Extraction extraction = extractionService.extract(channel.channelName(), day.date(),
                participantNames, matching.accounts(), matching.linkedAliases(),
                matching.colleagueNames(), sourceLines(day, lines));
        if (extraction.failed()) {
            return DayResult.failure();
        }
        if (extraction.droppedAsColleague() > 0) {
            // A count, never the names: the names are exactly what must not be written down.
            log.debugf("Slack source sync: channel %s dropped %d company names for being colleagues",
                    channel.channelId(), extraction.droppedAsColleague());
        }

        Map<String, String> userByTs = slackUserByTs(day);
        List<PreparedMention> prepared = prepareMentions(channel, extraction, colleagues, userByTs);
        List<UnmatchedCompanySighting> sightings =
                prepareSightings(channel, day.date(), extraction, colleagues, userByTs);

        QuarkusTransaction.requiringNew().run(() ->
                persistDay(channel, day.date(), day.lastTs(), prepared, sightings, now));
        return new DayResult(prepared.size(), sightings.size(), false);
    }

    /**
     * What Slack calls this channel now, or null when it would not say.
     *
     * <p>{@code conversations.history} does not carry the channel's name, so keeping the
     * promise the column comment and the entity both make — refreshed on every successful
     * read — costs one {@code conversations.info} per enabled channel per run, twenty-five a
     * night at the cap. It buys the thing the denormalised name on a mention row cannot buy
     * itself: history keeps the name it was written under, while everything being written
     * NOW carries the name the channel actually has. Without it a renamed channel keeps its
     * old name on the settings tab, in every hint's channel list, in the prompt header the
     * model reads, and — worst — stamped onto every new mention row, which is precisely the
     * opposite of what denormalising it was for.
     *
     * <p>The history read has already succeeded by the time this runs, so a name that cannot
     * be fetched is a blip and never a verdict about the channel: the run keeps the name it
     * has and carries on. Letting a second round trip decide the channel's fate would make a
     * Tier-3 hiccup look like a lost invitation.
     */
    private String currentName(String channelId) {
        try {
            return slackService.describeChannel(channelId).name();
        } catch (IOException | SlackApiException | RuntimeException e) {
            log.debugf("Slack source sync: channel %s kept its stored name — %s", channelId, e.getMessage());
            return null;
        }
    }

    /**
     * The name to store after a successful read, or null when there is nothing to change.
     *
     * <p>A blank answer is never a rename. {@code conversations.info} always answers a name,
     * so a blank one is a fault somewhere else, and overwriting a good name with a placeholder
     * would make the settings tab lie about a channel that is working. The comparison is made
     * on the trimmed name because that is what the 80-character column would have held anyway
     * — otherwise a name longer than the column would read as renamed every single night.
     */
    static String renamedTo(String stored, String fromSlack) {
        if (fromSlack == null || fromSlack.isBlank()) {
            return null;
        }
        String trimmed = SlackSourceChannelService.trimmedName(fromSlack);
        return trimmed.equals(stored) ? null : trimmed;
    }

    // ------------------------------------------------------------------------
    // Pure preparation — everything below happens outside every transaction
    // ------------------------------------------------------------------------

    /**
     * The day as the model reads it, with the two things
     * {@link AccountSlackDigestPrompts.Line} deliberately does not carry.
     *
     * <p>{@code linesFor} is reused rather than re-implemented — the rendering, the author
     * naming and the decision about which thread parents need an {@code [earlier]} line all
     * live there and must not have a second opinion. What it hands back is flat: the parent
     * becomes a line of its own and nothing carries a {@code ts}. This lane needs both. The
     * {@code ts} is what turns an evidence number back into a message, and so into a
     * permalink and into the colleague who wrote it; and the parent has to travel WITH its
     * reply, because a chunk boundary can fall between them and a chunk is read on its own.
     *
     * <p>Pairing is by position and not by content: {@code linesFor} emits the day's
     * messages in order, each optionally preceded by its context line, so the n-th line that
     * is not an {@code earlier} one is the n-th message of the day.
     *
     * <p><b>EVERY reply to an absent parent carries that parent, not just the first one.</b>
     * {@code linesFor} renders the {@code [earlier]} line once, above the first reply, which
     * is right for a prompt read whole and wrong for one read in chunks: the split is greedy
     * and a boundary between two replies to the same parent would leave the second chunk
     * opening on a bare "↳ Perfekt!!!" with no thread start above it, which the model would
     * attribute to whatever unrelated chatter preceded it — the very mis-attribution the
     * evidence machinery exists to prevent. Carrying the parent on all of them costs nothing,
     * because {@code renderBody} deduplicates context lines by value within a chunk and so
     * still writes it exactly once per chunk that needs it.
     */
    static List<SlackMentionPrompts.SourceLine> sourceLines(DayBundle day,
                                                            List<AccountSlackDigestPrompts.Line> lines) {
        Set<String> ownTs = new HashSet<>();
        for (Msg message : day.messages()) {
            ownTs.add(message.ts());
        }
        Map<String, AccountSlackDigestPrompts.Line> contextByParent = new HashMap<>();

        List<SlackMentionPrompts.SourceLine> out = new ArrayList<>();
        AccountSlackDigestPrompts.Line context = null;
        int index = 0;
        for (AccountSlackDigestPrompts.Line line : lines) {
            if (line.earlier()) {
                context = line;
                continue;
            }
            if (index >= day.messages().size()) {
                break;
            }
            Msg message = day.messages().get(index);
            String parentTs = message.reply() && message.parent() != null
                    && !ownTs.contains(message.parent().ts()) ? message.parent().ts() : null;
            if (parentTs != null && context != null) {
                contextByParent.putIfAbsent(parentTs, context);
            }
            out.add(new SlackMentionPrompts.SourceLine(message.ts(), line,
                    parentTs == null ? null : contextByParent.get(parentTs)));
            context = null;
            index++;
        }
        return out;
    }

    /** Slack {@code ts} → the member id that wrote it, for resolving evidence back to people. */
    private static Map<String, String> slackUserByTs(DayBundle day) {
        Map<String, String> byTs = new HashMap<>();
        for (Msg message : day.messages()) {
            if (message.ts() != null && message.user() != null) {
                byTs.put(message.ts(), message.user());
            }
        }
        return byTs;
    }

    /**
     * One mention ready to be written, with every Slack call and every serialisation already
     * done.
     *
     * @param digestJson     serialised up here because the column is NOT NULL and
     *                       {@code toJson} answers null on a serialisation failure — a row
     *                       that cannot carry its reading is dropped rather than allowed to
     *                       abort the whole day's transaction
     * @param messageCount   distinct cited lines about THIS client
     * @param citedLinesByUser colleague uuid → how many cited lines they wrote
     */
    record PreparedMention(String clientUuid, SlackDigestContent content, String digestJson,
                           int messageCount, String permalink,
                           Map<String, Integer> citedLinesByUser) { }

    private List<PreparedMention> prepareMentions(SourceChannel channel, Extraction extraction,
                                                  Map<String, Colleague> colleagues,
                                                  Map<String, String> userByTs) {
        List<PreparedMention> prepared = new ArrayList<>();
        for (SlackMentionExtractionService.Mention mention : extraction.mentions()) {
            String json = digestService.toJson(mention.content());
            if (json == null) {
                // Counted in the log and nowhere else; the reading is the row's whole point.
                log.warnf("Slack source sync: channel %s dropped a mention whose reading would not serialise",
                        channel.channelId());
                continue;
            }
            prepared.add(new PreparedMention(mention.clientUuid(), mention.content(), json,
                    mention.evidence().size(),
                    permalinkOf(channel.channelId(), mention.evidence(), extraction.tsByLine()),
                    citedLinesByUser(mention.evidence(), extraction.tsByLine(), userByTs, colleagues)));
        }
        return prepared;
    }

    private List<UnmatchedCompanySighting> prepareSightings(SourceChannel channel, LocalDate date,
                                                            Extraction extraction,
                                                            Map<String, Colleague> colleagues,
                                                            Map<String, String> userByTs) {
        List<UnmatchedCompanySighting> sightings = new ArrayList<>();
        for (SlackMentionExtractionService.UnmatchedSighting sighting : extraction.unmatched()) {
            Set<String> authors = citedLinesByUser(sighting.evidence(), extraction.tsByLine(),
                    userByTs, colleagues).keySet();
            sightings.add(new UnmatchedCompanySighting(sighting.nameKey(), sighting.displayName(),
                    channel.channelId(), date, sighting.evidence().size(), authors,
                    permalinkOf(channel.channelId(), sighting.evidence(), extraction.tsByLine()),
                    sighting.signalType(), sighting.headline()));
        }
        return sightings;
    }

    /**
     * The deep link to the FIRST cited message. Evidence arrives sorted, so "first" is the
     * earliest line of the day that mentioned this company — the one somebody following the
     * link wants to land on.
     */
    private String permalinkOf(String channelId, List<Integer> evidence, Map<Integer, String> tsByLine) {
        for (Integer line : evidence) {
            String ts = tsByLine.get(line);
            if (ts != null) {
                return slackService.getPermalinkAsAdmin(channelId, ts);
            }
        }
        return null;
    }

    /**
     * Colleague uuid → cited lines they wrote. A Slack member id that maps to nobody is
     * dropped here and never reaches the database, which is why no table in this lane has a
     * column that could hold one.
     */
    private static Map<String, Integer> citedLinesByUser(List<Integer> evidence, Map<Integer, String> tsByLine,
                                                         Map<String, String> userByTs,
                                                         Map<String, Colleague> colleagues) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Integer line : evidence) {
            String ts = tsByLine.get(line);
            if (ts == null) {
                continue;
            }
            Colleague colleague = colleagues.get(userByTs.get(ts));
            if (colleague == null) {
                continue;
            }
            counts.merge(colleague.uuid(), 1, Integer::sum);
        }
        return counts;
    }

    // ------------------------------------------------------------------------
    // The matching allowlist (§4.3)
    // ------------------------------------------------------------------------

    /**
     * Everything the model is allowed to name, built once per run inside one short
     * transaction.
     *
     * <p>Three sources, as §4.3 states them: every client with a name; the
     * {@code client_account.slack_space} value as an alias of its client, because
     * {@code a_e-nettet} is what the channel calls that account; and every LINKED hint's
     * name as an alias of the client somebody linked it to, which is the whole payoff of
     * taking a decision on the hints panel (D4) — the lane learns a spelling it had no way
     * of knowing and the mentions land on the account from the next run.
     *
     * <p><b>An alias that names more than one client is dropped for all of them, in both
     * places it would otherwise be used.</b> Two clients answering to "NN" is not a tie the
     * model can break and not one this code should break for it; the safe reading is that
     * neither is meant, and the warning carries the uuids rather than the alias, which is
     * somebody's typing or a model's reading of it. Taking it out of the ACCOUNTS block alone
     * would not do: {@link SlackMentionExtractionService#parse} promotes a company name found
     * in {@code linkedAliases} straight onto that client even when the model left
     * {@code clientId} null, so an ambiguous alias left in the map would be filed with no
     * model involvement at all, against a log line saying it had been dropped.
     *
     * <p>The order is the second half of the rule. {@code SlackMentionPrompts} cuts the block
     * at {@link SlackMentionPrompts#MAX_ACCOUNTS_IN_PROMPT}, and a cut through an
     * alphabetical list would decide which accounts this lane is blind to by accident, so the
     * accounts somebody is actually working — one with an owner, or a band above
     * {@link AccountBand#BACKLOG} — go first. Production holds roughly 300 clients against a
     * cap of 400, so this is headroom today; the count is logged the day it stops being.
     */
    Matching allowlist() {
        Map<String, ClientAccount> accountsByClient = new HashMap<>();
        Map<String, String> slackSpaceByClient = new LinkedHashMap<>();
        for (ClientAccount account : ClientAccount.<ClientAccount>listAll()) {
            accountsByClient.put(account.getClientUuid(), account);
            if (account.getSlackSpace() != null && !account.getSlackSpace().isBlank()) {
                slackSpaceByClient.put(account.getClientUuid(), account.getSlackSpace());
            }
        }
        Aliases aliases = resolveAliases(slackSpaceByClient, unmatchedService.linkedAliases());
        Map<String, List<String>> aliasesByClient = aliases.byClient();

        List<SlackMentionPrompts.Account> worked = new ArrayList<>();
        List<SlackMentionPrompts.Account> rest = new ArrayList<>();
        for (Client client : clientService.listAllClients()) {
            if (client == null || client.getUuid() == null
                    || client.getName() == null || client.getName().isBlank()) {
                continue;
            }
            SlackMentionPrompts.Account account = new SlackMentionPrompts.Account(client.getUuid(),
                    client.getName(), aliasesByClient.getOrDefault(client.getUuid(), List.of()));
            (isWorked(client, accountsByClient.get(client.getUuid())) ? worked : rest).add(account);
        }
        List<SlackMentionPrompts.Account> accounts = new ArrayList<>(worked);
        accounts.addAll(rest);
        if (accounts.size() > SlackMentionPrompts.MAX_ACCOUNTS_IN_PROMPT) {
            log.warnf("Slack source sync: %d clients but only %d reach the model — %d of them are worked"
                            + " accounts and go first",
                    accounts.size(), SlackMentionPrompts.MAX_ACCOUNTS_IN_PROMPT, worked.size());
        }

        List<String> colleagueNames = new ArrayList<>();
        for (String[] colleague : signalService.colleagueAllowlist()) {
            colleagueNames.add(colleague[1]);
        }
        return new Matching(List.copyOf(accounts), Map.copyOf(aliases.linked()), List.copyOf(colleagueNames));
    }

    /**
     * The spellings one run may match on: what goes in the prompt, and what the backend is
     * allowed to promote on its own.
     *
     * @param byClient client uuid → the alias texts to render beside that account
     * @param linked   {@code nameKey} → client uuid, the same rows minus the ambiguous ones
     */
    record Aliases(Map<String, List<String>> byClient, Map<String, String> linked) { }

    /**
     * Resolves both alias sources against each other and takes the ambiguous ones away from
     * everybody.
     *
     * <p>Static and pure so the rule the two halves of the lane have to agree on — which
     * spellings are ambiguous — can be exercised without a database. The order matters only
     * in that the first spelling claimed for a key is the one rendered: a {@code slack_space}
     * is what the channel itself calls the account, so it wins over a hint somebody linked.
     *
     * @param slackSpaceByClient client uuid → {@code client_account.slack_space} as typed
     * @param linkedAliases      {@code nameKey} → client uuid for every LINKED hint (D4); the
     *                           key is re-normalised here rather than trusted, because a key
     *                           written by an older normalisation would otherwise be rendered
     *                           into the prompt under one spelling and matched under another
     */
    static Aliases resolveAliases(Map<String, String> slackSpaceByClient, Map<String, String> linkedAliases) {
        Map<String, String> aliasText = new LinkedHashMap<>();
        Map<String, Set<String>> aliasOwners = new LinkedHashMap<>();
        slackSpaceByClient.forEach((clientUuid, space) -> {
            if (space == null || space.isBlank()) {
                return;
            }
            String typed = space.trim();
            claimAlias(aliasText, aliasOwners, typed.startsWith("#") ? typed.substring(1) : typed, clientUuid);
        });
        // The hint's key IS its display name lower-cased and whitespace-collapsed, and the
        // model matches on it just as well; asking for the original spelling would put the
        // "which rows are aliases" rule in a second place.
        linkedAliases.forEach((nameKey, clientUuid) -> claimAlias(aliasText, aliasOwners, nameKey, clientUuid));

        Map<String, List<String>> byClient = new HashMap<>();
        Set<String> ambiguous = new HashSet<>();
        for (Map.Entry<String, Set<String>> entry : aliasOwners.entrySet()) {
            Set<String> owners = entry.getValue();
            if (owners.size() > 1) {
                ambiguous.add(entry.getKey());
                log.warnf("Slack source sync: an alias resolves to %d clients (%s) — dropped for all of them,"
                                + " from the prompt and from the backend's own promotion alike",
                        owners.size(), String.join(", ", owners));
                continue;
            }
            byClient.computeIfAbsent(owners.iterator().next(), key -> new ArrayList<>())
                    .add(aliasText.get(entry.getKey()));
        }

        Map<String, String> linked = new LinkedHashMap<>();
        linkedAliases.forEach((nameKey, clientUuid) -> {
            String key = SlackMentionExtractionService.nameKey(nameKey);
            if (key.isEmpty() || clientUuid == null || ambiguous.contains(key)) {
                return;
            }
            linked.put(key, clientUuid);
        });
        return new Aliases(byClient, linked);
    }

    /**
     * An account somebody is actually working. The spec says "a band above PASSIVE"; the
     * bands are {@link AccountBand#STRATEGIC}, {@link AccountBand#ACTIVE} and
     * {@link AccountBand#BACKLOG}, and BACKLOG is the one that means "not prioritised" — so
     * that is the band this excludes. A client with no {@code client_account} row reads as
     * BACKLOG, which is the truth about the several hundred nobody has looked at.
     */
    private static boolean isWorked(Client client, ClientAccount account) {
        boolean owned = client.getAccountmanager() != null && !client.getAccountmanager().isBlank();
        return owned || (account != null && account.getBand() != null && account.getBand() != AccountBand.BACKLOG);
    }

    /** Records that one client answers to one alias, keyed by the matcher's own normalisation. */
    private static void claimAlias(Map<String, String> aliasText, Map<String, Set<String>> aliasOwners,
                                   String alias, String clientUuid) {
        String key = SlackMentionExtractionService.nameKey(alias);
        if (key.isEmpty() || clientUuid == null) {
            return;
        }
        aliasText.putIfAbsent(key, alias.trim());
        aliasOwners.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).add(clientUuid);
    }

    // ------------------------------------------------------------------------
    // Persistence — every method below runs inside a short transaction opened by the caller
    // ------------------------------------------------------------------------

    List<SourceChannel> enabledChannels() {
        List<SourceChannel> channels = new ArrayList<>();
        List<SlackSourceChannel> rows = SlackSourceChannel.list("enabled = ?1 order by channelName", true);
        for (SlackSourceChannel row : rows) {
            channels.add(new SourceChannel(row.getUuid(), row.getChannelId(),
                    row.getChannelName(), row.getCursorTs()));
        }
        return channels;
    }

    /**
     * The read succeeded: the verdict is cleared, the name is refreshed and the channel is
     * stamped.
     *
     * <p>{@code resetCursorTs} is written only when the stored cursor was too old to resume
     * from, and it is the only place in this lane that moves a cursor without a day's rows
     * beside it — which is safe in exactly that one case, because the window it names is the
     * window this run has just read.
     *
     * <p>{@code renamedTo} is null unless Slack answered a different name, so an unchanged
     * channel is not dirtied every night by a write of the value it already holds.
     */
    void markRead(String uuid, LocalDateTime now, String resetCursorTs, String renamedTo) {
        SlackSourceChannel channel = SlackSourceChannel.findById(uuid);
        if (channel == null) {
            return;
        }
        channel.setLinkError(null);
        channel.setSyncedAt(now);
        if (resetCursorTs != null) {
            channel.setCursorTs(resetCursorTs);
        }
        if (renamedTo != null) {
            channel.setChannelName(renamedTo);
        }
    }

    void recordLinkError(String uuid, String code) {
        QuarkusTransaction.requiringNew().run(() -> {
            SlackSourceChannel channel = SlackSourceChannel.findById(uuid);
            if (channel != null) {
                channel.setLinkError(code);
            }
        });
    }

    /**
     * One day, written whole: the mentions, their participants, the company hints, and the
     * cursor.
     *
     * <p>The cursor moves in THIS transaction and no other. That is what makes a run that is
     * killed half way resumable at the first day it did not finish rather than at the first
     * day it did — and what makes a day that is read twice a re-upsert onto the same
     * deterministic id rather than a second row.
     *
     * <p><b>{@code dismissed_by} and {@code dismissed_at} are never written here.</b> This is
     * the single most important line in the method. Somebody said this row was not about this
     * client; a re-read of the same day refreshes the reading and must leave that judgement
     * exactly where it was, or the lane would quietly resurrect every rejection the next
     * night and there would be no point in offering the button at all.
     */
    void persistDay(SourceChannel channel, LocalDate date, String lastTs,
                    List<PreparedMention> mentions, List<UnmatchedCompanySighting> sightings,
                    LocalDateTime now) {
        for (PreparedMention mention : mentions) {
            String uuid = AccountSlackSyncService.mentionUuid(mention.clientUuid(), channel.channelId(), date);
            AccountSlackMention row = AccountSlackMention.findById(uuid);
            if (row == null) {
                row = new AccountSlackMention();
                row.setUuid(uuid);
                row.setClientUuid(mention.clientUuid());
                row.setChannelId(channel.channelId());
                row.setMentionDate(date);
            }
            row.setChannelName(channel.channelName());
            row.setMessageCount(mention.messageCount());
            row.setSignalType(mention.content().signalType());
            row.setRelevance(mention.content().relevance());
            row.setHeadline(mention.content().headline());
            row.setDigestJson(mention.digestJson());
            if (mention.permalink() != null) {
                row.setPermalink(mention.permalink());
            }
            row.setModel(extractionService.model());
            row.setPromptVersion(SlackMentionPrompts.PROMPT_VERSION);
            row.setSyncedAt(now);
            row.persist();

            // Replaced wholesale, as the digest lane replaces its participants: the set is
            // small, and a colleague whose Slack link was fixed since must not stay missing
            // from a row that has been re-read.
            AccountSlackMentionParticipant.delete("mentionUuid", uuid);
            for (Map.Entry<String, Integer> entry : mention.citedLinesByUser().entrySet()) {
                AccountSlackMentionParticipant participant = new AccountSlackMentionParticipant();
                participant.setUuid(UUID.randomUUID().toString());
                participant.setMentionUuid(uuid);
                participant.setUserUuid(entry.getKey());
                participant.setMessageCount(entry.getValue());
                participant.persist();
            }
        }

        unmatchedService.record(sightings, now);

        SlackSourceChannel channelRow = SlackSourceChannel.findById(channel.uuid());
        if (channelRow != null) {
            channelRow.setCursorTs(lastTs);
        }
    }
}
