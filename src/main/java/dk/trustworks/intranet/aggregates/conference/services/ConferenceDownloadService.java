package dk.trustworks.intranet.aggregates.conference.services;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Aggregate download requests only: no participant, device, IP, or event history. */
@ApplicationScoped
public class ConferenceDownloadService {
    // A database increment remains correct across concurrent requests and ECS tasks.
    static final String INCREMENT_SQL = """
            INSERT INTO conference_presentation_downloads
                   (conference_uuid, presentation_id, download_requests)
            VALUES (:conference, :presentation, 1)
            ON DUPLICATE KEY UPDATE download_requests = download_requests + 1
            """;

    @Inject
    EntityManager entityManager;

    @Transactional
    public void recordRequest(String conferenceUuid, String presentationId) {
        ConferenceDownloadCatalog.requirePresentation(conferenceUuid, presentationId);
        entityManager.createNativeQuery(INCREMENT_SQL)
                .setParameter("conference", conferenceUuid)
                .setParameter("presentation", presentationId)
                .executeUpdate();
    }

    @Transactional
    public DownloadCounts counts(String conferenceUuid) {
        ConferenceDownloadCatalog.requireConference(conferenceUuid);
        List<?> rows = entityManager.createNativeQuery("""
                        SELECT presentation_id, download_requests
                          FROM conference_presentation_downloads
                         WHERE conference_uuid = :conference
                        """)
                .setParameter("conference", conferenceUuid)
                .getResultList();
        Map<String, Long> counts = new HashMap<>();
        for (Object row : rows) {
            Object[] columns = (Object[]) row;
            counts.put((String) columns[0], ((Number) columns[1]).longValue());
        }
        return new DownloadCounts(conferenceUuid, "download_requests",
                ConferenceDownloadCatalog.PRESENTATION_IDS.stream()
                        .map(id -> new PresentationCount(id, counts.getOrDefault(id, 0L)))
                        .toList());
    }

    public record DownloadCounts(String conferenceUuid, String metric,
                                 List<PresentationCount> presentations) {}

    public record PresentationCount(String presentationId, long downloadRequests) {}
}
