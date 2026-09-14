package dk.trustworks.intranet.aggregates.crm.slack.services;

import com.slack.api.methods.SlackApiException;
import dk.trustworks.intranet.aggregates.crm.slack.model.SlackSourceChannel;
import dk.trustworks.intranet.communicationsservice.services.SlackChannelAccessException;
import dk.trustworks.intranet.communicationsservice.services.SlackService;
import dk.trustworks.intranet.communicationsservice.services.SlackService.SlackChannelInfo;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.panache.common.Sort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The list of general Slack channels the CRM reads every night (source-channel spec §4.1,
 * §5.1, §6).
 *
 * <p>This is configuration, not a lane: four operations on a handful of rows, with the only
 * interesting decision being what to do about a channel the bot cannot get into.
 *
 * <h2>Resolved while the admin is still looking</h2>
 * A channel is added by typing an id or a {@code #name}, and it is resolved against Slack
 * on the spot (D5) rather than at 02:40 the next morning. A typo is then a red line under
 * the box instead of a channel that silently produces nothing for a week. What is stored is
 * the id — names get renamed — with the name beside it for the screens to show; that name is
 * captured here and then kept current by {@link SlackSourceSyncService}, which refreshes it
 * from Slack on every successful read.
 *
 * <h2>A channel the bot is not in is SAVED, not refused</h2>
 * {@code NOT_IN_CHANNEL} and {@code ARCHIVED} are recorded on the row and the row is kept.
 * The fix for the first is an invitation issued in Slack by somebody who may not be the
 * person adding the channel, and the nightly run re-checks the verdict and clears it by
 * itself; refusing the save would mean coming back here afterwards and typing it again.
 * {@code NOT_FOUND} is the one verdict that cannot be saved, because there is no id and no
 * name to save — it leaves as {@link SlackChannelAccessException} for the resource to turn
 * into its 422.
 *
 * <h2>Removing and disabling are different things (D15)</h2>
 * Disabling keeps the cursor, so a channel switched back on continues where it stopped
 * instead of re-reading a month. Removing the row leaves every mention it produced on the
 * accounts it produced them for: those readings were true when they were written, they name
 * their channel in a denormalised column precisely so they survive this, and a configuration
 * change is not a reason to rewrite somebody's timeline.
 *
 * <h2>Transactions and Slack</h2>
 * {@link SlackService#describeChannel} is called with no transaction open — the §P9 M1 rule
 * the whole module follows — and only the insert that follows it is wrapped, which is why
 * {@link #add} manages its own transaction while the two writes that touch nothing but the
 * database are plain {@code @Transactional} methods.
 */
@JBossLog
@ApplicationScoped
public class SlackSourceChannelService {

    /**
     * How many channels may be listed at once.
     *
     * <p>Twenty-five channels is already fourteen model calls a night each in the worst
     * case, and the number exists to make somebody think before turning the whole workspace
     * into a CRM feed — the lane is worth what it is worth because it reads the few channels
     * where client news actually turns up. It is refused with a message rather than silently
     * ignored, because a cap that drops the row the admin just added would be indistinguishable
     * from a bug.
     */
    public static final int MAX_SOURCE_CHANNELS = 25;

    /** The column is 80; Slack's own limit is the same. Guarded so a long name cannot abort the insert. */
    private static final int MAX_CHANNEL_NAME_CHARS = 80;

    /** Room for a {@code #}, the longest name Slack allows, and a paste that went slightly wrong. */
    private static final int MAX_TYPED_CHARS = 100;

    @Inject
    SlackService slackService;

    /** Every listed channel, enabled or not, in the order the settings tab shows them. */
    public List<SlackSourceChannel> list() {
        return SlackSourceChannel.listAll(Sort.ascending("channelName"));
    }

    /**
     * Resolves what was typed against Slack and lists the channel.
     *
     * <p>The resolution happens outside every transaction and the write is its own short one,
     * which is also why the "already listed" check can only run after the round trip: the id
     * is what a channel is identified by, and until Slack has answered, {@code #ledelse} and
     * {@code C0AD6RSD3UG} could be the same row or two different ones.
     *
     * <p>The returned row carries the verdict in {@code linkError}: null when the bot can read
     * the channel, {@code NOT_IN_CHANNEL} or {@code ARCHIVED} when it cannot. The row exists
     * either way — see the class javadoc — and the caller answers 201 or 422 by looking at
     * that field rather than by catching anything.
     *
     * @throws SlackChannelAccessException {@code channel_not_found}: nothing of that id or name
     *                                     is visible to the admin token, so there is nothing to
     *                                     save. Note that a private channel the bot has never
     *                                     been invited to answers exactly this, so the remedy
     *                                     shown must be the same as for NOT_IN_CHANNEL
     * @throws WebApplicationException     400 for a blank, over-long or unattributed request;
     *                                     409 when the channel is already listed or the cap is full
     */
    public SlackSourceChannel add(String idOrName, String actor) throws IOException, SlackApiException {
        if (actor == null || actor.isBlank()) {
            throw new WebApplicationException(
                    "X-Requested-By is required — a listed channel records who added it",
                    Response.Status.BAD_REQUEST);
        }
        if (idOrName == null || idOrName.isBlank()) {
            throw new WebApplicationException("Type a channel id or a #name", Response.Status.BAD_REQUEST);
        }
        String typed = idOrName.trim();
        if (typed.length() > MAX_TYPED_CHARS) {
            throw new WebApplicationException("That is not a Slack channel id or name",
                    Response.Status.BAD_REQUEST);
        }
        // Checked before the round trip rather than inside the write: there is no point asking
        // Slack about a channel that cannot be listed whatever it answers.
        if (SlackSourceChannel.count() >= MAX_SOURCE_CHANNELS) {
            throw new WebApplicationException(
                    "The list is full at " + MAX_SOURCE_CHANNELS + " channels — remove one first",
                    Response.Status.CONFLICT);
        }

        SlackChannelInfo info = slackService.describeChannel(typed);
        // Read outside the write, so the refusal is an ordinary 409 rather than something
        // thrown across a transaction boundary. Two admins adding the same channel in the
        // same second still meet the unique key, which is the real guard.
        if (SlackSourceChannel.count("channelId", info.id()) > 0) {
            throw new WebApplicationException("That channel is already listed", Response.Status.CONFLICT);
        }
        String linkError = linkErrorOf(info);
        LocalDateTime now = LocalDateTime.now();

        SlackSourceChannel saved = QuarkusTransaction.requiringNew().call(() -> {
            SlackSourceChannel row = new SlackSourceChannel();
            row.setUuid(UUID.randomUUID().toString());
            row.setChannelId(info.id());
            row.setChannelName(trimmedName(info.name()));
            row.setPrivateChannel(info.isPrivate());
            row.setEnabled(true);
            row.setLinkError(linkError);
            row.setCreatedAt(now);
            row.setCreatedBy(actor);
            row.persist();
            return row;
        });

        // The id and the verdict, never the name somebody typed.
        log.infof("Slack source channel added: channel=%s linkError=%s actor=%s",
                saved.getChannelId(), linkError == null ? "-" : linkError, actor);
        return saved;
    }

    /**
     * Pauses a channel, or starts it again.
     *
     * <p>The cursor is deliberately left alone (D15). A channel paused for a fortnight and
     * resumed carries on from where it stopped; one paused for longer than
     * {@code SlackSourceSyncService.STALE_CURSOR_DAYS} has its cursor reset by the run itself,
     * which is the only place that decision belongs.
     */
    @Transactional
    public SlackSourceChannel setEnabled(String uuid, boolean enabled) {
        SlackSourceChannel row = require(uuid);
        row.setEnabled(enabled);
        log.infof("Slack source channel %s: enabled=%s", row.getChannelId(), enabled);
        return row;
    }

    /**
     * Stops reading a channel and forgets it was ever listed.
     *
     * <p>The mentions it produced are NOT touched — see the class javadoc. The answer says
     * whether a row was actually there so the caller can distinguish a delete from a repeat,
     * but a DELETE of something already gone is not an error worth raising.
     */
    @Transactional
    public boolean remove(String uuid) {
        SlackSourceChannel row = SlackSourceChannel.findById(uuid);
        if (row == null) {
            return false;
        }
        log.infof("Slack source channel removed: channel=%s — the mentions it produced are kept",
                row.getChannelId());
        row.delete();
        return true;
    }

    /**
     * The verdict for a channel Slack was willing to describe.
     *
     * <p>Archived is checked before membership because it is the more final of the two: the
     * bot may well still be a member of a channel nobody will ever write in again, and
     * "invite the bot" would be useless advice.
     */
    static String linkErrorOf(SlackChannelInfo info) {
        if (info.isArchived()) {
            return AccountSlackSyncService.ERROR_ARCHIVED;
        }
        if (!info.isMember()) {
            return AccountSlackSyncService.ERROR_NOT_IN_CHANNEL;
        }
        return null;
    }

    /**
     * The verdict for a channel Slack refused to describe, as the code the browser keys its
     * label off.
     *
     * <p>Public because the resource needs the same mapping to build its 422 body and this is
     * the fourth place that would otherwise spell the switch out; the BFF rebuilds a 4xx body
     * from a closed allow-list, so the verdict has to travel in {@code code} and has to be one
     * of these three words.
     */
    public static String linkErrorOf(SlackChannelAccessException e) {
        return switch (e.getSlackError()) {
            case "is_archived" -> AccountSlackSyncService.ERROR_ARCHIVED;
            case "channel_not_found" -> AccountSlackSyncService.ERROR_NOT_FOUND;
            default -> AccountSlackSyncService.ERROR_NOT_IN_CHANNEL;
        };
    }

    private static SlackSourceChannel require(String uuid) {
        SlackSourceChannel row = SlackSourceChannel.findById(uuid);
        if (row == null) {
            throw new WebApplicationException("Unknown source channel", Response.Status.NOT_FOUND);
        }
        return row;
    }

    /**
     * A channel name as the 80-character column can hold it. Package-private rather than
     * private because the sync lane refreshes the stored name on every successful read and
     * must cut it exactly here — a second opinion about the column's width is how a name
     * that is 81 characters long becomes a nightly rename, or an aborted transaction.
     */
    static String trimmedName(String name) {
        if (name == null || name.isBlank()) {
            // conversations.info always answers a name for a channel; this is the belt that
            // keeps a NOT NULL column from being the thing that reports it did not.
            return "unknown";
        }
        String trimmed = name.trim();
        return trimmed.length() <= MAX_CHANNEL_NAME_CHARS
                ? trimmed : trimmed.substring(0, MAX_CHANNEL_NAME_CHARS);
    }
}
