package dk.trustworks.intranet.aggregates.crm.slack.services;

import dk.trustworks.intranet.communicationsservice.services.SlackChannelAccessException;
import dk.trustworks.intranet.communicationsservice.services.SlackService;
import dk.trustworks.intranet.communicationsservice.services.SlackService.SlackChannelInfo;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What may be listed as a source channel, and what a channel the bot cannot get into is
 * answered with.
 *
 * <p>The rule worth locking is the one that is easy to get backwards: {@code NOT_IN_CHANNEL}
 * and {@code ARCHIVED} are verdicts recorded on a row that IS saved, because the fix for the
 * first is an invitation issued in Slack by somebody who may not be the person adding the
 * channel, and the nightly run re-checks the verdict and clears it by itself. Only
 * {@code NOT_FOUND} refuses the save, and it can never come from a channel Slack was willing
 * to describe — it arrives as an exception instead.
 *
 * <p>The three words matter as words: the BFF rebuilds every 4xx body from a closed allow-list,
 * so the verdict travels in {@code code} and the browser keys its label off exactly these.
 *
 * <p>Fast tier — no Quarkus boot, no Slack, no database.
 */
class SlackSourceChannelRulesTest {

    private static final String ACTOR = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";

    private SlackSourceChannelService service;

    /**
     * A {@link SlackService} that fails the test if it is asked anything. Every refusal below
     * is checked before the round trip on purpose: there is no point asking Slack about a
     * channel that cannot be listed whatever it answers.
     */
    private static final class NeverAskedSlack extends SlackService {

        @Override
        public SlackChannelInfo describeChannel(String idOrName) {
            throw new AssertionError("Slack was asked about a request that was already refusable");
        }
    }

    private static SlackChannelInfo channel(boolean archived, boolean member) {
        return new SlackChannelInfo("C0AD6RSD3UG", "ledelse", false, archived, member);
    }

    @BeforeEach
    void setUp() {
        service = new SlackSourceChannelService();
        service.slackService = new NeverAskedSlack();
    }

    private WebApplicationException refusalOf(String idOrName, String actor) {
        return assertThrows(WebApplicationException.class, () -> service.add(idOrName, actor));
    }

    // ------------------------------------------------------------------------
    // A channel the bot is not in is a verdict, not a refusal
    // ------------------------------------------------------------------------

    @Test
    void aReadableChannelCarriesNoVerdict() {
        assertNull(SlackSourceChannelService.linkErrorOf(channel(false, true)));
    }

    @Test
    void aChannelTheBotIsNotInIsSavedCarryingNotInChannel() {
        assertEquals("NOT_IN_CHANNEL", SlackSourceChannelService.linkErrorOf(channel(false, false)),
                "the row is kept; the fix is an invitation in Slack and the next run clears the verdict");
    }

    /**
     * Archived is checked before membership because it is the more final of the two: the bot
     * may well still be a member of a channel nobody will ever write in again, and "invite the
     * bot" would be useless advice.
     */
    @Test
    void archivedOutranksMembership() {
        assertEquals("ARCHIVED", SlackSourceChannelService.linkErrorOf(channel(true, true)));
        assertEquals("ARCHIVED", SlackSourceChannelService.linkErrorOf(channel(true, false)));
    }

    @Test
    void aChannelSlackDescribedCanNeverBeNotFound() {
        assertNotEquals("NOT_FOUND", SlackSourceChannelService.linkErrorOf(channel(true, false)));
        assertNotEquals("NOT_FOUND", SlackSourceChannelService.linkErrorOf(channel(false, false)),
                "NOT_FOUND means there is no id and no name to save — the one verdict a row cannot hold");
    }

    /**
     * The other half of the same mapping, for the channel Slack refused to describe. A private
     * channel the bot has never been invited to answers {@code channel_not_found}, so the
     * remedy shown for NOT_FOUND has to be the same as the one shown for NOT_IN_CHANNEL — and
     * an error nobody has mapped reads as "invite the bot" rather than as "it does not exist",
     * which is the safer of the two things to tell somebody.
     */
    @Test
    void aRefusedChannelMapsToOneOfTheThreeWordsTheBrowserKnows() {
        assertEquals("ARCHIVED", SlackSourceChannelService.linkErrorOf(
                new SlackChannelAccessException("x", "is_archived")));
        assertEquals("NOT_FOUND", SlackSourceChannelService.linkErrorOf(
                new SlackChannelAccessException("x", "channel_not_found")));
        assertEquals("NOT_IN_CHANNEL", SlackSourceChannelService.linkErrorOf(
                new SlackChannelAccessException("x", "not_in_channel")));
        assertEquals("NOT_IN_CHANNEL", SlackSourceChannelService.linkErrorOf(
                new SlackChannelAccessException("x", "ratelimited")));
    }

    // ------------------------------------------------------------------------
    // What is refused before Slack is asked
    // ------------------------------------------------------------------------

    @Test
    void aChannelNobodyIsAttributedToIsRefused() {
        WebApplicationException missing = refusalOf("#ledelse", null);

        assertEquals(400, missing.getResponse().getStatus());
        assertTrue(missing.getMessage().contains("X-Requested-By"), "a listed channel records who added it");
        assertEquals(400, refusalOf("#ledelse", "   ").getResponse().getStatus());
    }

    @Test
    void aBlankChannelIsRefused() {
        WebApplicationException blank = refusalOf(null, ACTOR);

        assertEquals(400, blank.getResponse().getStatus());
        assertEquals("Type a channel id or a #name", blank.getMessage());
        assertEquals(400, refusalOf("   ", ACTOR).getResponse().getStatus());
    }

    /**
     * A hundred characters is room for a {@code #}, the longest name Slack allows, and a paste
     * that went slightly wrong; past that it is not a channel reference at all and the round
     * trip would be spent for nothing.
     */
    @Test
    void somethingTooLongToBeAChannelReferenceIsRefused() {
        WebApplicationException refusal = refusalOf("#" + "x".repeat(100), ACTOR);

        assertEquals(400, refusal.getResponse().getStatus());
        assertEquals("That is not a Slack channel id or name", refusal.getMessage());
    }

    // ------------------------------------------------------------------------
    // The cap
    // ------------------------------------------------------------------------

    /**
     * Twenty-five channels is already fourteen model calls a night each in the worst case, and
     * the number exists to make somebody think before turning the whole workspace into a CRM
     * feed. It is refused with a message rather than silently ignored, because a cap that
     * dropped the row the admin just added would be indistinguishable from a bug — but the
     * refusal counts the listed rows, so only the number it quotes is reachable from here.
     */
    @Test
    void theListIsFullAtTwentyFiveChannels() {
        assertEquals(25, SlackSourceChannelService.MAX_SOURCE_CHANNELS);
    }
}
