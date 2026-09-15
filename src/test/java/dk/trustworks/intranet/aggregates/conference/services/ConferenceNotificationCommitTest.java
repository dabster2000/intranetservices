package dk.trustworks.intranet.aggregates.conference.services;

import dk.trustworks.intranet.knowledgeservice.model.ConferenceParticipant;
import dk.trustworks.intranet.knowledgeservice.model.ConferencePhase;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.mockito.Mockito.*;

class ConferenceNotificationCommitTest {
    @Test void registrationPersistsBeforeNotificationAndRollbackNeverSends() {
        ConferenceService service = new ConferenceService();
        service.transactions = mock(TransactionSynchronizationRegistry.class);
        service.conferenceMailService = mock(ConferenceMailService.class);
        service.conferenceRecipientResolver = mock(ConferenceRecipientResolver.class);
        ConferenceParticipant participant = mock(ConferenceParticipant.class);
        ConferencePhase phase = mock(ConferencePhase.class);
        when(participant.getEmail()).thenReturn("recipient@example.com");
        when(participant.getConferencePhase()).thenReturn(phase);
        when(phase.isUseMail()).thenReturn(true);
        when(phase.getAttachments()).thenReturn(List.of());
        service.createParticipant(participant);
        var ordered = inOrder(participant, service.transactions);
        ordered.verify(participant).persist();
        var capture = org.mockito.ArgumentCaptor.forClass(Synchronization.class);
        ordered.verify(service.transactions).registerInterposedSynchronization(capture.capture());
        verifyNoInteractions(service.conferenceMailService, service.conferenceRecipientResolver);
        capture.getValue().afterCompletion(Status.STATUS_ROLLEDBACK);
        verifyNoInteractions(service.conferenceMailService, service.conferenceRecipientResolver);
    }
    @Test void batchHandledNotificationNeverPreventsPhasePersistence() {
        ConferenceService service = new ConferenceService();
        service.conferenceMailService = mock(ConferenceMailService.class);
        service.conferenceRecipientResolver = mock(ConferenceRecipientResolver.class);
        ConferenceParticipant participant = mock(ConferenceParticipant.class);
        service.changeParticipantPhase(participant, true);
        verify(participant).persist();
        verifyNoInteractions(service.conferenceMailService, service.conferenceRecipientResolver);
    }
}
