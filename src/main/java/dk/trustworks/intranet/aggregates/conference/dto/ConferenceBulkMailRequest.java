package dk.trustworks.intranet.aggregates.conference.dto;

import dk.trustworks.intranet.communicationsservice.model.EmailAttachment;
import java.util.List;

public record ConferenceBulkMailRequest(String conferenceUuid, List<String> participantUuids,
        List<String> recipients, String subject, String body, UnsubscribeFooter footerDescriptor,
        List<EmailAttachment> attachments, String cc, String bcc) {}
