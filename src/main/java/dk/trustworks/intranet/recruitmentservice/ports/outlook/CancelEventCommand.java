package dk.trustworks.intranet.recruitmentservice.ports.outlook;

public record CancelEventCommand(
        String interviewUuid,
        String organizerMailbox,
        String eventId,
        String reason
) {}
