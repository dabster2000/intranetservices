package dk.trustworks.intranet.communicationsservice.services;

import com.slack.api.methods.response.conversations.ConversationsMembersResponse;
import com.slack.api.methods.response.users.UsersInfoResponse;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The P24 DPO digest's channel-drift check ran for the first time in production
 * on 2026-08-31 and answered {@code missing_scope}: the admin bot token may post
 * to the private {@code recr-*} channels but may not read their membership, so a
 * GDPR access-control review silently verified nothing while the digest reported
 * itself healthy. Two things made that possible — the failure was indistinguishable
 * from a transient blip, and Slack's own {@code needed}/{@code provided} fields,
 * which name the exact missing scope, were thrown away with the response.
 * <p>
 * These tests pin both halves of the fix: permanent app/token faults become a
 * {@link SlackConfigurationException} that a caller can single out, transient
 * failures stay a plain {@link IOException}, and the diagnosis Slack hands back
 * survives into the message either way.
 *
 * @see SlackService#slackFailure(String, com.slack.api.methods.SlackApiTextResponse)
 */
class SlackServiceScopeFailureTest {

    private static ConversationsMembersResponse members(String error, String needed, String provided) {
        ConversationsMembersResponse response = new ConversationsMembersResponse();
        response.setOk(false);
        response.setError(error);
        response.setNeeded(needed);
        response.setProvided(provided);
        return response;
    }

    @Test
    void missingScope_isPermanentAndCarriesTheScopeDiagnosis() {
        // Exactly what production returned: the token can write, not read a private channel.
        IOException thrown = SlackService.slackFailure("Slack channel member listing failed",
                members("missing_scope", "groups:read", "chat:write,groups:write,users:read"));

        SlackConfigurationException permanent =
                assertInstanceOf(SlackConfigurationException.class, thrown,
                        "missing_scope is a permanent app configuration fault, not a blip");
        assertEquals("missing_scope", permanent.getSlackError());
        assertEquals("groups:read", permanent.getNeeded());
        assertEquals("chat:write,groups:write,users:read", permanent.getProvided());

        // The scope Slack asked for and the scopes it says the token has both have to
        // reach the log — that is the whole point of keeping needed/provided.
        assertTrue(permanent.getMessage().contains("needed=groups:read"), permanent.getMessage());
        assertTrue(permanent.getMessage().contains("provided=chat:write,groups:write,users:read"),
                permanent.getMessage());
    }

    @Test
    void message_keepsTheLegacyPrefixSoExistingLogSearchesStillMatch() {
        IOException thrown = SlackService.slackFailure("Slack channel member listing failed",
                members("missing_scope", "groups:read", "chat:write"));

        assertTrue(thrown.getMessage()
                        .startsWith("Slack channel member listing failed: missing_scope"),
                thrown.getMessage());
    }

    @Test
    void invalidAuth_isAlsoPermanent() {
        IOException thrown = SlackService.slackFailure("Slack user info lookup failed",
                members("invalid_auth", null, null));

        assertInstanceOf(SlackConfigurationException.class, thrown);
        // Slack sends no needed/provided for a dead token; the message must not invent them.
        assertFalse(thrown.getMessage().contains("needed="), thrown.getMessage());
        assertFalse(thrown.getMessage().contains("provided="), thrown.getMessage());
        assertNull(((SlackConfigurationException) thrown).getNeeded());
    }

    @Test
    void rateLimited_isTransientAndStaysAPlainIOException() {
        IOException thrown = SlackService.slackFailure("Slack channel member listing failed",
                members("ratelimited", null, null));

        assertEquals(IOException.class, thrown.getClass(),
                "a rate limit is retried away by the next run — it must not alarm as a "
                        + "configuration fault");
        assertFalse(thrown instanceof SlackConfigurationException);
    }

    @Test
    void channelNotFound_isNotTreatedAsAConfigurationFault() {
        // Ambiguous by nature: it is also what an un-invited bot sees, and the existing
        // self-heal path handles it. Classifying it as permanent would misroute that.
        IOException thrown = SlackService.slackFailure("Slack channel member listing failed",
                members("channel_not_found", null, null));

        assertEquals(IOException.class, thrown.getClass());
    }

    @Test
    void classificationIsIndependentOfTheResponseType() {
        // users.info fails the same way as conversations.members when a scope is absent;
        // the helper works off the shared SlackApiTextResponse contract, not the subtype.
        UsersInfoResponse info = new UsersInfoResponse();
        info.setOk(false);
        info.setError("missing_scope");
        info.setNeeded("users:read");
        info.setProvided("chat:write");

        IOException thrown = SlackService.slackFailure("Slack user info lookup failed", info);

        assertInstanceOf(SlackConfigurationException.class, thrown);
        assertEquals("users:read", ((SlackConfigurationException) thrown).getNeeded());
    }
}
