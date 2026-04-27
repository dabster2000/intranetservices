package dk.trustworks.intranet.recruitmentservice.ports.slack;

public record SendDmCommand(
        String recipientUserUuid,
        String headline,
        String bodyMarkdown,
        String deepLinkUrl,
        String idempotencyKey
) {}
