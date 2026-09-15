package dk.trustworks.intranet.aggregates.conference.dto;

import dk.trustworks.intranet.communicationsservice.model.EmailAttachment;
import java.util.List;

/** Explicit conference boundary. Copy recipients are rejected rather than silently ignored. */
public record ConferenceMailRequest(String uuid, String conferenceUuid, String participantUuid,
        String to, String subject, String body, UnsubscribeFooter footerDescriptor,
        List<EmailAttachment> attachments, String cc, String bcc) {}
