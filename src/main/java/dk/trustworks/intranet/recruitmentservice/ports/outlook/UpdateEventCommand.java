package dk.trustworks.intranet.recruitmentservice.ports.outlook;

import java.time.Instant;
import java.util.List;

public record UpdateEventCommand(
        String interviewUuid,
        String organizerMailbox,
        String eventId,
        Instant startUtc,
        Instant endUtc,
        List<String> attendeeEmails
) {}
