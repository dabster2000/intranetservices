package dk.trustworks.intranet.recruitmentservice.slack;

import dk.trustworks.intranet.recruitmentservice.dto.SlackInboundRequest;
import dk.trustworks.intranet.recruitmentservice.dto.SlackInboundResponse;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Regression guard for the production warning
 * {@code Slack inbound dropped: no handler on the allowlist
 * (surface=interactions kind=block_actions key=recruitment_card_view)} —
 * five real clicks on 2026-08-11. The P22 living card's <b>View profile</b>
 * URL button was shipped without an allowlist entry, so every click landed
 * in the dispatcher's unknown-key drop path.
 * <p>
 * DB-free by construction (the fast tier is the tier CI gates on): the
 * dispatcher's {@code @PostConstruct} allowlist build is driven directly
 * with a stubbed CDI {@link Instance}, so nothing here touches Panache,
 * Flyway or the feature-flag table.
 */
class SlackCardViewButtonHandlerTest {

    private final SlackCardViewButtonHandler handler = new SlackCardViewButtonHandler();

    /**
     * The key is the {@code action_id} Slack puts on the wire.
     * {@code SlackCardReactor} now emits {@link SlackCardViewButtonHandler#KEY}
     * itself, so the compiler keeps the builder and the handler in step —
     * this pins the shared constant to the literal Slack actually sends,
     * which a rename would otherwise silently change on both sides at once.
     */
    @Test
    void keyIsTheActionIdOnTheWire() {
        assertEquals("recruitment_card_view", handler.key());
        assertEquals("recruitment_card_view", SlackCardViewButtonHandler.KEY);
    }

    /** A URL button's work already happened client-side; the ack is silent. */
    @Test
    void handleIsASilentNoOpAck() {
        SlackInboundResponse response = handler.handle(null, new SlackInboundRequest(
                "interactions", "trigger.recruitment_card_view", "U123", "T123",
                "block_actions", SlackCardViewButtonHandler.KEY, null, null));

        assertEquals(SlackInboundResponse.DISPOSITION_HANDLED, response.disposition());
        assertNull(response.ephemeralText(), "no ephemeral — the profile already opened");
        assertNull(response.responseAction(), "block_actions carries no synchronous response action");
    }

    /**
     * The assertion the incident was missing: with the bean present, the
     * dispatcher's allowlist resolves {@code recruitment_card_view} to it,
     * so {@code dispatch()} reaches {@code handler.handle(...)} instead of
     * falling through to {@code SlackInboundResponse.unknown()}.
     */
    @Test
    void dispatcherAllowlistResolvesTheCardViewKey() throws Exception {
        Map<String, SlackInboundHandler> allowlist = buildAllowlist(handler);

        SlackInboundHandler registered = allowlist.get("recruitment_card_view");
        assertNotNull(registered, "recruitment_card_view must be on the inbound allowlist");
        assertSame(handler, registered);
    }

    /**
     * Both URL-button no-ops must coexist: {@code buildAllowlist()} throws
     * on a duplicate key, so a copy-paste of the triage handler's key would
     * take the whole application down at startup rather than just losing
     * one button.
     */
    @Test
    void cardAndTriageViewNoOpsRegisterUnderDistinctKeys() throws Exception {
        SlackTriageViewButtonHandler triage = new SlackTriageViewButtonHandler();
        Map<String, SlackInboundHandler> allowlist = buildAllowlist(handler, triage);

        assertSame(handler, allowlist.get(SlackCardViewButtonHandler.KEY));
        assertSame(triage, allowlist.get(SlackTriageViewButtonHandler.KEY));
        assertNotEquals(SlackCardViewButtonHandler.KEY, SlackTriageViewButtonHandler.KEY,
                "the two view buttons must own distinct allowlist keys");
    }

    /** Drive the real {@code @PostConstruct} with a stubbed CDI Instance. */
    @SuppressWarnings("unchecked")
    private static Map<String, SlackInboundHandler> buildAllowlist(SlackInboundHandler... beans)
            throws Exception {
        SlackInboundDispatchService service = new SlackInboundDispatchService();
        Instance<SlackInboundHandler> instance = mock(Instance.class);
        when(instance.iterator()).thenReturn(List.of(beans).iterator());
        service.handlers = instance;

        service.buildAllowlist();

        Field field = SlackInboundDispatchService.class.getDeclaredField("allowlist");
        field.setAccessible(true);
        return (Map<String, SlackInboundHandler>) field.get(service);
    }
}
