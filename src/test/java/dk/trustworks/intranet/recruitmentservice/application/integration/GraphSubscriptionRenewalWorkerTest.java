package dk.trustworks.intranet.recruitmentservice.application.integration;

import dk.trustworks.intranet.recruitmentservice.domain.integration.GraphSubscription;
import dk.trustworks.intranet.recruitmentservice.infrastructure.OutlookCalendarException;
import dk.trustworks.intranet.recruitmentservice.ports.OutlookCalendarPort;
import dk.trustworks.intranet.recruitmentservice.ports.outlook.GraphSubscriptionInfo;
import dk.trustworks.intranet.recruitmentservice.ports.outlook.SubscribeCommand;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link GraphSubscriptionRenewalWorker}. Subclasses the worker to
 * override the package-private Panache seams; mocks the {@link OutlookCalendarPort}
 * with Mockito.
 */
class GraphSubscriptionRenewalWorkerTest {

    @Test
    void renews_subscription_expiring_within_48h() {
        GraphSubscription g = new GraphSubscription();
        g.uuid = "sub-row-1";
        g.subscriptionId = "graph-sub-1";
        g.resource = "users/tam@x/events";
        g.expiresAt = LocalDateTime.now().plusHours(24); // expiring within 48h
        g.clientStateHmac = "hmac";
        g.createdAt = LocalDateTime.now().minusDays(2);

        Instant newExpiryInstant = Instant.now().plusSeconds(GraphSubscriptionRenewalWorker.MAX_LIFE_MINUTES * 60);
        OutlookCalendarPort outlook = mock(OutlookCalendarPort.class);
        when(outlook.renewSubscription(eq("graph-sub-1"), any(Instant.class)))
                .thenReturn(new GraphSubscriptionInfo("graph-sub-1", g.resource, newExpiryInstant));

        List<GraphSubscription> deleted = new ArrayList<>();
        List<GraphSubscription> persisted = new ArrayList<>();

        GraphSubscriptionRenewalWorker worker = new GraphSubscriptionRenewalWorker() {
            @Override
            List<GraphSubscription> findExpiringBy(LocalDateTime threshold) {
                assertTrue(threshold.isAfter(LocalDateTime.now().plusHours(47)),
                        "threshold must be ~48h ahead");
                return List.of(g);
            }
            @Override
            void deleteSubscription(GraphSubscription gs) { deleted.add(gs); }
            @Override
            void persistSubscription(GraphSubscription gs) { persisted.add(gs); }
        };
        worker.outlook = outlook;
        worker.notificationUrl = "https://intra.trustworks.dk/api/recruitment/graph/notifications";
        worker.clientStateSecret = "shhh";

        worker.renew();

        verify(outlook, times(1)).renewSubscription(eq("graph-sub-1"), any(Instant.class));
        verify(outlook, never()).createEventSubscription(any());

        // Row's expiresAt was updated from Graph's response.
        assertEquals(LocalDateTime.ofInstant(newExpiryInstant, ZoneOffset.UTC), g.expiresAt);

        // Happy path: no delete, no recreate-persist.
        assertTrue(deleted.isEmpty(), "should not delete on successful renewal");
        assertTrue(persisted.isEmpty(), "should not persist a new row on successful renewal");
    }

    @Test
    void recreates_subscription_on_terminal_failure() {
        GraphSubscription g = new GraphSubscription();
        g.uuid = "sub-row-2";
        g.subscriptionId = "graph-sub-gone";
        g.resource = "users/tam@x/events";
        g.expiresAt = LocalDateTime.now().plusHours(12);
        g.clientStateHmac = "stale-hmac";
        g.createdAt = LocalDateTime.now().minusDays(3);

        Instant graphReturnedExpiry = Instant.now().plusSeconds(GraphSubscriptionRenewalWorker.MAX_LIFE_MINUTES * 60);
        OutlookCalendarPort outlook = mock(OutlookCalendarPort.class);
        when(outlook.renewSubscription(eq("graph-sub-gone"), any(Instant.class)))
                .thenThrow(new OutlookCalendarException(false, "GRAPH_404", "subscription not found"));
        when(outlook.createEventSubscription(any(SubscribeCommand.class)))
                .thenReturn(new GraphSubscriptionInfo("graph-sub-NEW", g.resource, graphReturnedExpiry));

        List<GraphSubscription> deleted = new ArrayList<>();
        List<GraphSubscription> persisted = new ArrayList<>();

        GraphSubscriptionRenewalWorker worker = new GraphSubscriptionRenewalWorker() {
            @Override
            List<GraphSubscription> findExpiringBy(LocalDateTime threshold) { return List.of(g); }
            @Override
            void deleteSubscription(GraphSubscription gs) { deleted.add(gs); }
            @Override
            void persistSubscription(GraphSubscription gs) { persisted.add(gs); }
        };
        worker.outlook = outlook;
        worker.notificationUrl = "https://intra.trustworks.dk/api/recruitment/graph/notifications";
        worker.clientStateSecret = "shhh";

        worker.renew();

        // Verify the create was called with the right notification URL + resource carried over.
        ArgumentCaptor<SubscribeCommand> cmdCap = ArgumentCaptor.forClass(SubscribeCommand.class);
        verify(outlook).createEventSubscription(cmdCap.capture());
        SubscribeCommand cmd = cmdCap.getValue();
        assertEquals("users/tam@x/events", cmd.resource());
        assertEquals("https://intra.trustworks.dk/api/recruitment/graph/notifications", cmd.notificationUrl());
        assertEquals("shhh", cmd.clientStateSecret());

        // Old row deleted, new row persisted.
        assertEquals(1, deleted.size(), "old row should be deleted exactly once");
        assertEquals(g, deleted.get(0));
        assertEquals(1, persisted.size(), "new row should be persisted exactly once");
        GraphSubscription created = persisted.get(0);
        assertNotNull(created.uuid, "factory must mint a uuid");
        assertEquals("graph-sub-NEW", created.subscriptionId);
        assertEquals(LocalDateTime.ofInstant(graphReturnedExpiry, ZoneOffset.UTC), created.expiresAt);
        // Hmac is the SHA-256 of the secret — same input => same hex output.
        assertEquals(GraphSubscriptionRenewalWorker.sha256Hex("shhh"), created.clientStateHmac);
    }
}
