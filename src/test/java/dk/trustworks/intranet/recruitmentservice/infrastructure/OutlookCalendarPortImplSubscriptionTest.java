package dk.trustworks.intranet.recruitmentservice.infrastructure;

import com.microsoft.graph.models.Subscription;
import com.microsoft.graph.serviceclient.GraphServiceClient;
import com.microsoft.graph.subscriptions.SubscriptionsRequestBuilder;
import com.microsoft.graph.subscriptions.item.SubscriptionItemRequestBuilder;
import dk.trustworks.intranet.recruitmentservice.ports.outlook.GraphSubscriptionInfo;
import dk.trustworks.intranet.recruitmentservice.ports.outlook.SubscribeCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure-JUnit + Mockito tests for the subscription lifecycle methods on
 * {@link OutlookCalendarPortImpl}: create, renew, delete.
 *
 * <p>Verifies that the canonical Subscription payload (clientState, resource,
 * notificationUrl, expirationDateTime) is sent to Graph, that the returned
 * port DTO is built from Graph's response, and that PATCH/DELETE are wired
 * via {@code subscriptions().bySubscriptionId(id)}.
 */
class OutlookCalendarPortImplSubscriptionTest {

    private GraphServiceClient graph;
    private SubscriptionsRequestBuilder subsBuilder;
    private SubscriptionItemRequestBuilder subItemBuilder;
    private OutlookCalendarPortImpl port;

    private static final String SUB_ID = "sub-abc-123";
    private static final String CLIENT_STATE = "shhh-secret";
    private static final String NOTIFY_URL = "https://api.trustworks.dk/api/recruitment/integrations/graph/notifications";
    private static final String RESOURCE = "/users/tam@trustworks.dk/events";

    @BeforeEach
    void setUp() {
        graph = mock(GraphServiceClient.class);
        subsBuilder = mock(SubscriptionsRequestBuilder.class);
        subItemBuilder = mock(SubscriptionItemRequestBuilder.class);

        when(graph.subscriptions()).thenReturn(subsBuilder);
        when(subsBuilder.bySubscriptionId(SUB_ID)).thenReturn(subItemBuilder);

        port = new OutlookCalendarPortImpl();
        port.graph = graph;
    }

    @Test
    void createEventSubscription_posts_subscription_with_clientstate_and_returns_info() {
        Instant expiresAt = Instant.parse("2026-05-15T12:00:00Z");

        Subscription returned = new Subscription();
        returned.setId(SUB_ID);
        returned.setResource(RESOURCE);
        returned.setExpirationDateTime(expiresAt.atOffset(ZoneOffset.UTC));
        when(subsBuilder.post(any(Subscription.class))).thenReturn(returned);

        SubscribeCommand cmd = new SubscribeCommand(RESOURCE, NOTIFY_URL, CLIENT_STATE, expiresAt);

        GraphSubscriptionInfo info = port.createEventSubscription(cmd);

        assertEquals(SUB_ID, info.subscriptionId());
        assertEquals(RESOURCE, info.resource());
        assertEquals(expiresAt, info.expiresAt());

        ArgumentCaptor<Subscription> captor = ArgumentCaptor.forClass(Subscription.class);
        verify(subsBuilder).post(captor.capture());
        Subscription sent = captor.getValue();

        assertEquals("created,updated,deleted", sent.getChangeType());
        assertEquals(NOTIFY_URL, sent.getNotificationUrl());
        assertEquals(RESOURCE, sent.getResource());
        assertEquals(CLIENT_STATE, sent.getClientState());
        assertNotNull(sent.getExpirationDateTime());
        assertEquals(expiresAt, sent.getExpirationDateTime().toInstant());
    }

    @Test
    void renewSubscription_patches_expiration() {
        Instant newExp = Instant.parse("2026-05-16T12:00:00Z");

        Subscription returned = new Subscription();
        returned.setId(SUB_ID);
        returned.setResource(RESOURCE);
        returned.setExpirationDateTime(newExp.atOffset(ZoneOffset.UTC));
        when(subItemBuilder.patch(any(Subscription.class))).thenReturn(returned);

        GraphSubscriptionInfo info = port.renewSubscription(SUB_ID, newExp);

        assertEquals(SUB_ID, info.subscriptionId());
        assertEquals(RESOURCE, info.resource());
        assertEquals(newExp, info.expiresAt());

        ArgumentCaptor<Subscription> captor = ArgumentCaptor.forClass(Subscription.class);
        verify(subItemBuilder).patch(captor.capture());
        Subscription sent = captor.getValue();
        OffsetDateTime expected = newExp.atOffset(ZoneOffset.UTC);
        assertEquals(expected, sent.getExpirationDateTime());
    }

    @Test
    void deleteSubscription_calls_DELETE() {
        port.deleteSubscription(SUB_ID);
        verify(subItemBuilder).delete();
    }
}
