package dk.trustworks.intranet.recruitmentservice.ports.outlook;

import java.time.Instant;
import java.util.List;

public record CreateEventCommand(
        String interviewUuid,
        String organizerMailbox,
        String subject,
        String bodyHtml,
        Instant startUtc,
        Instant endUtc,
        List<String> attendeeEmails,
        boolean teamsEnabled
) {}
