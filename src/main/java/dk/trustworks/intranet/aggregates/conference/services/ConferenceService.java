package dk.trustworks.intranet.aggregates.conference.services;

import dk.trustworks.intranet.aggregates.conference.dto.ReturningCountDTO;
import dk.trustworks.intranet.communicationsservice.resources.MailResource;
import dk.trustworks.intranet.knowledgeservice.model.Conference;
import dk.trustworks.intranet.knowledgeservice.model.ConferenceParticipant;
import dk.trustworks.intranet.knowledgeservice.model.ConferencePhase;
import lombok.extern.jbosslog.JBossLog;
import org.apache.commons.codec.binary.Base64;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.transaction.TransactionSynchronizationRegistry;
import jakarta.transaction.Synchronization;
import jakarta.transaction.Status;
import io.quarkus.narayana.jta.QuarkusTransaction;
import java.util.List;

@JBossLog
@ApplicationScoped
public class ConferenceService {

    @Inject
    MailResource mailResource;

    @Inject
    ConferenceMailService conferenceMailService;

    @Inject
    ConferenceRecipientResolver conferenceRecipientResolver;

    @Inject
    TransactionSynchronizationRegistry transactions;

    @Inject
    EntityManager entityManager;

    public List<Conference> findAllConferences() {
        log.debugf("ConferenceService.findAllConferences");
        return Conference.listAll();
    }

    public Conference findConferenceBySlug(String slug) {
        log.debugf("ConferenceService.findConferenceBySlug: {}", slug);
        return Conference.<Conference>find("slug", slug).stream().findAny().orElse(null);
    }

    @Transactional
    public void createConference(Conference conference) {
        log.infof("ConferenceService.createConference: {}", conference.getName());
        conference.persist();
    }

    @Transactional
    public List<ConferenceParticipant> findAllConferenceParticipants(String conferenceuuid) {
        String hql = "SELECT a FROM ConferenceParticipant a " +
                "LEFT JOIN ConferenceParticipant b " +
                "ON a.conferenceuuid = b.conferenceuuid AND a.participantuuid = b.participantuuid " +
                "AND (a.registered < b.registered OR (a.registered IS NULL AND b.registered IS NOT NULL) " +
                "OR ((a.registered = b.registered OR (a.registered IS NULL AND b.registered IS NULL)) AND a.uuid > b.uuid)) " +
                "WHERE b.participantuuid IS NULL AND a.conferenceuuid = ?1";

        return ConferenceParticipant.find(hql, conferenceuuid).list();
    }


    /*
    public List<ConferenceParticipant> findAllConferenceParticipants(String conferenceuuid) {
        return ConferenceParticipant.list("conferenceuuid", conferenceuuid);
        /*
        return ConferenceParticipant.getEntityManager().createNativeQuery("SELECT a.* " +
                "FROM twservices.conference_participants a " +
                "LEFT JOIN twservices.conference_participants b " +
                "ON a.conferenceuuid = b.conferenceuuid AND a.participantuuid = b.participantuuid " +
                "AND (a.registered < b.registered OR (a.registered IS NULL AND b.registered IS NOT NULL) " +
                "OR ((a.registered = b.registered OR (a.registered IS NULL AND b.registered IS NULL)) AND a.uuid > b.uuid)) " +
                "WHERE b.participantuuid IS NULL and a.conferenceuuid like '"+conferenceuuid+"'", ConferenceParticipant.class).getResultList();

    }
         */


    @Transactional
    public void createParticipant(ConferenceParticipant participant) {
        participant.setEmail(ConferenceUnsubscribeService.validatedEmail(participant.getEmail()));
        participant.persist();
        scheduleNotification(participant);
    }

    @Transactional
    public void updateParticipantData(ConferenceParticipant participant) {
        participant.setEmail(ConferenceUnsubscribeService.validatedEmail(participant.getEmail()));
        participant.persist();
    }

    @Transactional
    public void changeParticipantPhase(ConferenceParticipant participant) {
        changeParticipantPhase(participant, false);
    }

    @Transactional
    public void changeParticipantPhase(ConferenceParticipant participant, boolean notificationHandled) {
        participant.persist();
        if (!notificationHandled) scheduleNotification(participant);
    }

    private void scheduleNotification(ConferenceParticipant participant) {
        ConferencePhase phase = participant.getConferencePhase();
        // Event JSON intentionally omits attachments. Reload trusted phase metadata while its lazy collection is available.
        if (phase != null && phase.getUuid() != null) {
            phase = ConferencePhase.findById(phase.getUuid());
            if (phase == null || !participant.getConferenceuuid().equals(phase.getConferenceuuid())) {
                log.warnf("Phase notification skipped: participant=%s reason=INVALID_PHASE", participant.getParticipantuuid());
                return;
            }
        }
        if (phase == null || !phase.isUseMail()) return;
        // Capture immutable fields while attachments are initialized in the participant transaction.
        String listId = participant.getConferenceuuid();
        String participantId = participant.getParticipantuuid();
        String address = participant.getEmail();
        String subject = phase.getSubject();
        String body = phase.getMail();
        var footer = phase.getUnsubscribeFooter();
        var attachments = phase.getAttachments().stream()
                .map(dk.trustworks.intranet.knowledgeservice.model.ConferencePhaseAttachment::toEmailAttachment).toList();
        transactions.registerInterposedSynchronization(new Synchronization() {
            public void beforeCompletion() {}
            public void afterCompletion(int status) {
                if (status != Status.STATUS_COMMITTED) return;
                try {
                    // Lookup after commit detects ambiguous current snapshots, while the queued address stays fixed.
                    var current = conferenceRecipientResolver.resolveOne(listId, participantId);
                    if (!current.normalizedEmail().equals(ConferenceUnsubscribeService.normalizeEmail(address))) {
                        log.warnf("Phase notification skipped: participant=%s reason=STALE_RECIPIENT", participantId);
                        return;
                    }
                    QuarkusTransaction.requiringNew().run(() -> {
                        var outcome = conferenceMailService.queueAutomated(listId, current, subject,
                                new String(Base64.decodeBase64(body), java.nio.charset.StandardCharsets.UTF_8), footer, attachments);
                        if ("SKIPPED".equals(outcome.outcome()))
                            log.infof("Phase notification skipped: participant=%s reason=UNSUBSCRIBED", participantId);
                    });
                } catch (RuntimeException e) {
                    // No notification error can roll back the already committed history.
                    log.warnf("Phase notification admission failed: participant=%s", participantId);
                }
            }
        });
    }

    @Transactional
    public long deleteParticipant(String conferenceuuid, String participantuuid) {
        log.infof("ConferenceService.deleteParticipant: conferenceuuid=%s participantuuid=%s", conferenceuuid, participantuuid);
        return ConferenceParticipant.delete("conferenceuuid = ?1 and participantuuid = ?2", conferenceuuid, participantuuid);
    }

    public ConferencePhase findConferencePhase(String conferenceUUID, int phase) {
        return (ConferencePhase) ConferencePhase.find("conferenceuuid = ?1 and step = ?2", conferenceUUID, phase).firstResultOptional().orElseThrow();
    }

    public ReturningCountDTO getReturningParticipantCount(String conferenceUuid) {
        log.debugf("ConferenceService.getReturningParticipantCount: {}", conferenceUuid);

        Number totalResult = (Number) entityManager.createNativeQuery("""
                SELECT COUNT(DISTINCT email)
                FROM conference_participants
                WHERE conferenceuuid = :uuid
                """)
                .setParameter("uuid", conferenceUuid)
                .getSingleResult();

        long total = totalResult == null ? 0L : totalResult.longValue();
        if (total == 0L) {
            return new ReturningCountDTO(0L, 0L, 0L);
        }

        Number returningResult = (Number) entityManager.createNativeQuery("""
                SELECT COUNT(DISTINCT email)
                FROM conference_participants
                WHERE conferenceuuid = :uuid
                  AND email IN (
                      SELECT DISTINCT email
                      FROM conference_participants
                      WHERE conferenceuuid != :uuid
                  )
                """)
                .setParameter("uuid", conferenceUuid)
                .getSingleResult();

        long returning = returningResult == null ? 0L : returningResult.longValue();
        long newParticipants = total - returning;

        return new ReturningCountDTO(returning, newParticipants, total);
    }
}
