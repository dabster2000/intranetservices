package dk.trustworks.intranet.aggregates.conference.services;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.ws.rs.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConferenceDownloadServiceTest {
    @Mock EntityManager entityManager;
    @Mock(answer = org.mockito.Answers.RETURNS_SELF) Query query;
    ConferenceDownloadService service;

    @BeforeEach
    void setUp() {
        service = new ConferenceDownloadService();
        service.entityManager = entityManager;
    }

    @ParameterizedTest
    @ValueSource(strings = {"keynote-human-code", "test-new-black", "fart-kontrol",
            "intention-handling", "vis-byg-tilpas"})
    void recordsOnlyTheConferenceAndPresentation(String presentationId) {
        when(entityManager.createNativeQuery(ConferenceDownloadService.INCREMENT_SQL)).thenReturn(query);
        service.recordRequest(ConferenceDownloadCatalog.CONFERENCE_UUID, presentationId);
        verify(query).setParameter("conference", ConferenceDownloadCatalog.CONFERENCE_UUID);
        verify(query).setParameter("presentation", presentationId);
        verify(query).executeUpdate();
        verifyNoMoreInteractions(query);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"all", "Alle", "unknown", "../test-new-black", "TEST-NEW-BLACK"})
    void rejectsUnknownPresentationWithoutCreatingRows(String presentationId) {
        assertThrows(NotFoundException.class,
                () -> service.recordRequest(ConferenceDownloadCatalog.CONFERENCE_UUID, presentationId));
        verifyNoInteractions(entityManager);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"other", "65c73bd8-55a3-4e01-8208-7f02c825e26f"})
    void rejectsOtherConferencesForBothOperations(String conferenceUuid) {
        assertThrows(NotFoundException.class, () -> service.recordRequest(conferenceUuid, "test-new-black"));
        assertThrows(NotFoundException.class, () -> service.counts(conferenceUuid));
        verifyNoInteractions(entityManager);
    }

    @Test
    void readsFiveStableCountersWithZeroDefaultsAndWithoutIncrementing() {
        when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of(
                new Object[]{"test-new-black", 4_000_000_001L},
                new Object[]{"vis-byg-tilpas", 17L},
                new Object[]{"unpublished-id", 7L}));

        var counts = service.counts(ConferenceDownloadCatalog.CONFERENCE_UUID);

        assertEquals(ConferenceDownloadCatalog.CONFERENCE_UUID, counts.conferenceUuid());
        assertEquals("download_requests", counts.metric());
        assertEquals(ConferenceDownloadCatalog.PRESENTATION_IDS,
                counts.presentations().stream().map(ConferenceDownloadService.PresentationCount::presentationId).toList());
        assertEquals(List.of(0L, 4_000_000_001L, 0L, 0L, 17L),
                counts.presentations().stream().map(ConferenceDownloadService.PresentationCount::downloadRequests).toList());
        verify(query, never()).executeUpdate();
    }

    @Test
    void returnsZerosBeforeFirstRequest() {
        when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of());
        assertTrue(service.counts(ConferenceDownloadCatalog.CONFERENCE_UUID).presentations().stream()
                .allMatch(presentation -> presentation.downloadRequests() == 0));
    }
}
