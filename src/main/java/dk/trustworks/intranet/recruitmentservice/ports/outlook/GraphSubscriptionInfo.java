package dk.trustworks.intranet.recruitmentservice.ports.outlook;

import java.time.Instant;

public record GraphSubscriptionInfo(String subscriptionId, String resource, Instant expiresAt) {}
