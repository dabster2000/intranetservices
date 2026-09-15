package dk.trustworks.intranet.communicationsservice.resources;

import dk.trustworks.intranet.aggregates.conference.services.ConferenceMailDispatch;
import dk.trustworks.intranet.communicationsservice.model.EmailAttachment;
import dk.trustworks.intranet.communicationsservice.model.TrustworksMail;
import io.quarkus.mailer.Mail;
import io.quarkus.mailer.Mailer;
import org.junit.jupiter.api.Test;

import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual direct attachment dispatch entry point: final gate must precede the only SMTP call. */
class ConferenceMailDispatchBoundaryTest {
    private final MailResource resource = new MailResource();
    private final ConferenceMailDispatch policy = mock(ConferenceMailDispatch.class);
    private final Mailer smtp = mock(Mailer.class);
    private TrustworksMail mail() {
        resource.conferenceDispatch = policy; resource.mailer = smtp; resource.defaultFrom = "no-reply@trustworks.dk";
        TrustworksMail mail = new TrustworksMail("mail", "recipient@example.com", "Subject", "<p>Original body</p>");
        mail.setMailOrigin("CONFERENCE"); mail.setConferenceUuid("list");
        mail.setParticipantUuid("participant"); mail.setNormalizedEmail("recipient@example.com");
        return mail;
    }
    @Test void withdrawalAfterRenderingPreventsSmtpAndKeepsSourceImmutable() {
        TrustworksMail mail = mail();
        when(policy.prepare("list", mail.getTo(), mail.getBody(), null)).thenReturn("<p>Rendered recipient capability</p>");
        when(policy.allowedAtDispatch("list", mail.getTo())).thenReturn(false);
        assertFalse(resource.sendConferenceWithAttachments(mail));
        verifyNoInteractions(smtp);
        assertEquals("<p>Original body</p>", mail.getBody());
    }
    @Test void alreadySuppressedDoesNotReachFinalizerOrSmtp() {
        TrustworksMail mail = mail();
        when(policy.prepare("list", mail.getTo(), mail.getBody(), null)).thenReturn(null);
        assertFalse(resource.sendConferenceWithAttachments(mail));
        verify(policy, never()).allowedAtDispatch(anyString(), anyString());
        verifyNoInteractions(smtp);
    }
    @Test void tokenOrStorageFailureFailsClosed() {
        TrustworksMail mail = mail();
        when(policy.prepare("list", mail.getTo(), mail.getBody(), null)).thenThrow(new IllegalStateException("CONFERENCE_POLICY_UNAVAILABLE"));
        assertThrows(IllegalStateException.class, () -> resource.sendConferenceWithAttachments(mail));
        verifyNoInteractions(smtp);
    }
    @Test void validSendChecksAfterPreparationBeforeSmtp() {
        TrustworksMail mail = mail();
        when(policy.prepare("list", mail.getTo(), mail.getBody(), null)).thenReturn("<p>Final footer</p>");
        when(policy.allowedAtDispatch("list", mail.getTo())).thenReturn(true);
        assertTrue(resource.sendConferenceWithAttachments(mail));
        var ordered = inOrder(policy, smtp);
        ordered.verify(policy).prepare("list", mail.getTo(), mail.getBody(), null);
        ordered.verify(policy).allowedAtDispatch("list", mail.getTo());
        ordered.verify(smtp).send(any(Mail.class));
    }
    @Test void systemMailKeepsItsBehaviorForAnAddressSuppressedOnSomeList() {
        TrustworksMail mail = mail(); mail.setMailOrigin("SYSTEM"); mail.setConferenceUuid(null);
        resource.sendWithAttachments(mail);
        verifyNoInteractions(policy); verify(smtp).send(any(Mail.class));
    }
    @Test void typedPathRejectsCopyRecipientsAndMissingList() {
        TrustworksMail mail = mail(); mail.setBcc("other@example.com");
        assertThrows(IllegalArgumentException.class, () -> resource.sendConferenceWithAttachments(mail));
        verifyNoInteractions(policy, smtp);
        mail.setBcc(null); mail.setConferenceUuid(null);
        assertThrows(IllegalArgumentException.class, () -> resource.sendConferenceWithAttachments(mail));
    }
    @Test void genericAttachmentEntryPointCannotAcceptConferenceContext() {
        assertThrows(IllegalArgumentException.class, () -> resource.sendWithAttachments(mail()));
        verifyNoInteractions(policy, smtp);
    }
}
