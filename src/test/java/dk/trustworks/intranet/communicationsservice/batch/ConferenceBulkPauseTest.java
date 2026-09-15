package dk.trustworks.intranet.communicationsservice.batch;

import dk.trustworks.intranet.aggregates.conference.services.ConferenceMailDispatch;
import dk.trustworks.intranet.communicationsservice.model.*;
import io.quarkus.mailer.Mailer;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConferenceBulkPauseTest {
    @Test void disabledConferenceDispatchKeepsPendingRecipientsAndCountsWithoutTokenOrSmtp() {
        BulkEmailItemWriter writer = new BulkEmailItemWriter();
        writer.conferenceDispatch = mock(ConferenceMailDispatch.class);
        writer.mailer = mock(Mailer.class);
        BulkEmailJob job = new BulkEmailJob("job", "Subject", "<p>Body</p>");
        job.setMailOrigin("CONFERENCE"); job.setStatus(BulkEmailJob.BulkEmailJobStatus.POLICY_PENDING);
        BulkEmailRecipient recipient = new BulkEmailRecipient("job", "recipient@example.com");
        writer.sendEmailToRecipient(recipient, job, List.of());
        assertEquals(BulkEmailRecipient.RecipientStatus.PENDING, recipient.getStatus());
        assertEquals(BulkEmailJob.BulkEmailJobStatus.POLICY_PENDING, job.getStatus());
        assertEquals(0, job.getFailedCount()); assertEquals(0, job.getSkippedCount());
        verify(writer.conferenceDispatch).isEnabled();
        verifyNoMoreInteractions(writer.conferenceDispatch); verifyNoInteractions(writer.mailer);
    }
}
