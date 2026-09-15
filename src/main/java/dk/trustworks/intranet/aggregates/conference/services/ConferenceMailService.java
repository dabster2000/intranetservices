package dk.trustworks.intranet.aggregates.conference.services;

import dk.trustworks.intranet.aggregates.conference.dto.*;
import dk.trustworks.intranet.aggregates.conference.services.ConferenceRecipientResolver.ResolvedRecipient;
import dk.trustworks.intranet.communicationsservice.model.*;
import dk.trustworks.intranet.communicationsservice.resources.MailResource;
import dk.trustworks.intranet.communicationsservice.services.BulkEmailService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;

import java.util.*;

/** The sole typed conference admission boundary; bodies and recipients are frozen here. */
@ApplicationScoped
public class ConferenceMailService {
    @Inject ConferenceRecipientResolver recipients;
    @Inject ConferenceUnsubscribeService policy;
    @Inject ConferenceMailDispatch dispatch;
    @Inject MailResource mailResource;
    @Inject BulkEmailService bulkEmailService;

    public record SendOutcome(String outcome, String reason) {
        public static SendOutcome skipped() { return new SendOutcome("SKIPPED", "UNSUBSCRIBED"); }
    }
    public record BulkOutcome(String jobId, int queuedCount, int excludedUnsubscribedCount, String outcome) {}

    @Transactional
    public SendOutcome send(ConferenceMailRequest request) {
        if (request == null) throw new BadRequestException("Conference mail is required");
        rejectCopies(request.cc(), request.bcc());
        ConferenceMailRenderer.validateContent(request.subject(), request.body());
        ResolvedRecipient recipient = recipients.resolveOne(request.conferenceUuid(), request.participantUuid());
        if (request.to() != null && !ConferenceUnsubscribeService.normalizeEmail(request.to()).equals(recipient.normalizedEmail()))
            throw new BadRequestException("RECIPIENT_ADDRESS_MISMATCH");
        dispatch.requireEnabled();
        if (policy.isSuppressedFresh(request.conferenceUuid(), recipient.email())) return SendOutcome.skipped();
        TrustworksMail mail = scopedMail(request.conferenceUuid(), recipient, request.subject(), request.body(), request.footerDescriptor());
        mail.setAttachments(request.attachments());
        if (mail.hasAttachments()) {
            return mailResource.sendConferenceWithAttachments(mail) ? new SendOutcome("SENT", null) : SendOutcome.skipped();
        }
        mailResource.queueConference(mail);
        return new SendOutcome("QUEUED", null);
    }

    @Transactional
    public BulkOutcome sendBulk(ConferenceBulkMailRequest request) {
        if (request == null) throw new BadRequestException("Conference mail is required");
        rejectCopies(request.cc(), request.bcc());
        ConferenceMailRenderer.validateContent(request.subject(), request.body());
        if (request.participantUuids() == null || request.participantUuids().size() > 1000)
            throw new BadRequestException("Maximum 1000 participants per bulk message");
        List<ResolvedRecipient> resolved = recipients.resolve(request.conferenceUuid(), request.participantUuids());
        if (request.recipients() != null) {
            if (request.recipients().size() > 1000) throw new BadRequestException("Maximum 1000 recipient addresses per bulk message");
            Set<String> supplied = new HashSet<>();
            for (String address : request.recipients()) supplied.add(ConferenceUnsubscribeService.normalizeEmail(address));
            Set<String> actual = new HashSet<>();
            for (ResolvedRecipient recipient : resolved) actual.add(recipient.normalizedEmail());
            if (!supplied.equals(actual)) throw new BadRequestException("RECIPIENT_ADDRESS_MISMATCH");
        }
        return queueResolved(request.conferenceUuid(), resolved, request.subject(), request.body(),
                request.footerDescriptor(), request.attachments());
    }

    public BulkOutcome queueResolved(String conferenceUuid, List<ResolvedRecipient> resolved, String subject,
            String body, UnsubscribeFooter footer, List<EmailAttachment> attachments) {
        dispatch.requireEnabled();
        ConferenceMailRenderer.validateContent(subject, body);
        List<ResolvedRecipient> eligible = new ArrayList<>();
        for (ResolvedRecipient recipient : resolved) {
            if (!policy.isSuppressedFresh(conferenceUuid, recipient.email())) eligible.add(recipient);
        }
        if (eligible.isEmpty()) return new BulkOutcome(null, 0, resolved.size(), "NO_ELIGIBLE_RECIPIENTS");
        BulkEmailJob job = bulkEmailService.createConferenceJob(conferenceUuid, eligible, subject, body,
                UnsubscribeFooter.orDefault(footer), attachments);
        return new BulkOutcome(job.getUuid(), eligible.size(), resolved.size() - eligible.size(), "QUEUED");
    }

    public SendOutcome queueAutomated(String conferenceUuid, ResolvedRecipient recipient, String subject,
            String body, UnsubscribeFooter footer, List<EmailAttachment> attachments) {
        dispatch.requireEnabled();
        ConferenceMailRenderer.validateContent(subject, body);
        if (policy.isSuppressedFresh(conferenceUuid, recipient.email())) return SendOutcome.skipped();
        if (attachments != null && !attachments.isEmpty()) {
            BulkOutcome result = queueResolved(conferenceUuid, List.of(recipient), subject, body, footer, attachments);
            if (result.queuedCount() == 0) return SendOutcome.skipped();
        } else {
            mailResource.queueConference(scopedMail(conferenceUuid, recipient, subject, body, footer));
        }
        return new SendOutcome("QUEUED", null);
    }

    private static TrustworksMail scopedMail(String conferenceUuid, ResolvedRecipient recipient,
            String subject, String body, UnsubscribeFooter footer) {
        TrustworksMail mail = new TrustworksMail(UUID.randomUUID().toString(), recipient.email(), subject, ConferenceMailRenderer.personalize(body, recipient.name()));
        mail.setMailOrigin("CONFERENCE");
        mail.setConferenceUuid(conferenceUuid);
        mail.setParticipantUuid(recipient.participantUuid());
        mail.setNormalizedEmail(recipient.normalizedEmail());
        mail.setUnsubscribeFooter(UnsubscribeFooter.orDefault(footer));
        return mail;
    }
    private static void rejectCopies(String cc, String bcc) {
        if ((cc != null && !cc.isBlank()) || (bcc != null && !bcc.isBlank()))
            throw new BadRequestException("Conference mail does not allow cc or bcc");
    }
}
