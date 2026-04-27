package dk.trustworks.intranet.recruitmentservice.ports.outlook;

import java.time.Instant;

public record SubscribeCommand(
        String resource,
        String notificationUrl,
        String clientStateSecret,
        Instant expiresAt
) {}
