package dk.trustworks.intranet.aggregates.conference.services;

import dk.trustworks.intranet.aggregates.conference.dto.*;
import dk.trustworks.intranet.communicationsservice.model.*;
import dk.trustworks.intranet.communicationsservice.resources.MailResource;
import dk.trustworks.intranet.communicationsservice.services.BulkEmailService;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConferenceMailAdmissionTest {
    private static final String LIST = "00000000-0000-0000-0000-000000000001";
    private static final String PARTICIPANT = "00000000-0000-0000-0000-000000000002";
    private final ConferenceMailService service = new ConferenceMailService();
    private final ConferenceRecipientResolver.ResolvedRecipient recipient = new ConferenceRecipientResolver.ResolvedRecipient(
            PARTICIPANT, "Recipient@Example.com", "recipient@example.com", "Name <safe>");
    ConferenceMailAdmissionTest() {
        service.recipients = mock(ConferenceRecipientResolver.class);
        service.policy = mock(ConferenceUnsubscribeService.class);
        service.dispatch = mock(ConferenceMailDispatch.class);
        service.mailResource = mock(MailResource.class);
        service.bulkEmailService = mock(BulkEmailService.class);
        when(service.recipients.resolveOne(LIST, PARTICIPANT)).thenReturn(recipient);
        when(service.recipients.resolve(LIST, List.of(PARTICIPANT))).thenReturn(List.of(recipient));
    }
    private ConferenceMailRequest request(String to, String cc, String bcc) {
        return new ConferenceMailRequest(null, LIST, PARTICIPANT, to, "Subject", "<p>Hello [name]</p>", null, List.of(), cc, bcc);
    }
    @Test void omittedTransitionalAddressUsesAuthoritativeParticipantAndFrozenContext() {
        assertEquals("QUEUED", service.send(request(null, null, null)).outcome());
        var captured = org.mockito.ArgumentCaptor.forClass(TrustworksMail.class);
        verify(service.mailResource).queueConference(captured.capture());
        var mail = captured.getValue();
        assertEquals(LIST, mail.getConferenceUuid()); assertEquals(PARTICIPANT, mail.getParticipantUuid());
        assertEquals("Recipient@Example.com", mail.getTo()); assertEquals("recipient@example.com", mail.getNormalizedEmail());
        assertEquals("<p>Hello Name &lt;safe&gt;</p>", mail.getBody());
        assertEquals(UnsubscribeFooter.defaults(), mail.getUnsubscribeFooter());
        assertFalse(mail.getBody().contains("token="));
    }
    @Test void staleRecipientCannotBeReroutedToCurrentAddress() {
        assertThrows(BadRequestException.class, () -> service.send(request("different@example.com", null, null)));
        verifyNoInteractions(service.mailResource);
    }
    @Test void copiesCannotBypassTheSingleRecipientPolicy() {
        assertThrows(BadRequestException.class, () -> service.send(request(null, "extra@example.com", null)));
        assertThrows(BadRequestException.class, () -> service.send(request(null, null, "extra@example.com")));
        verifyNoInteractions(service.mailResource);
    }
    @Test void suppressedSingleIsSkippedWithoutQueueOrTokenIssuance() {
        when(service.policy.isSuppressedFresh(LIST, recipient.email())).thenReturn(true);
        var result = service.send(request(recipient.email(), null, null));
        assertEquals("SKIPPED", result.outcome()); assertEquals("UNSUBSCRIBED", result.reason());
        verifyNoInteractions(service.mailResource); verify(service.policy, never()).issueToken(anyString(), anyString());
    }
    @Test void allExcludedBulkNeverCreatesAnEmptyJob() {
        when(service.policy.isSuppressedFresh(LIST, recipient.email())).thenReturn(true);
        var result = service.sendBulk(new ConferenceBulkMailRequest(LIST, List.of(PARTICIPANT), null,
                "Subject", "<p>Hello</p>", null, List.of(), null, null));
        assertNull(result.jobId()); assertEquals(0, result.queuedCount());
        assertEquals(1, result.excludedUnsubscribedCount()); assertEquals("NO_ELIGIBLE_RECIPIENTS", result.outcome());
        verifyNoInteractions(service.bulkEmailService);
    }
    @Test void automatedAttachmentSuppressionRaceReportsSkipped() {
        when(service.policy.isSuppressedFresh(LIST, recipient.email())).thenReturn(false, true);
        var attachment = new EmailAttachment();
        var result = service.queueAutomated(LIST, recipient, "Subject", "<p>Hello</p>", null, List.of(attachment));
        assertEquals("SKIPPED", result.outcome());
        verifyNoInteractions(service.bulkEmailService, service.mailResource);
    }
}
