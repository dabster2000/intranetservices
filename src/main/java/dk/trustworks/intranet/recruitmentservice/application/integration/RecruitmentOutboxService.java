package dk.trustworks.intranet.recruitmentservice.application.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.recruitmentservice.domain.integration.OutboxKind;
import dk.trustworks.intranet.recruitmentservice.domain.integration.RecruitmentOutboxRow;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;
import org.jboss.logging.Logger;

/**
 * Enqueues outbox rows for asynchronous dispatch by {@code RecruitmentOutboxWorker}.
 *
 * <p>Idempotency-key dedup: if a row with the same key is already present we
 * skip silently — callers are expected to rebuild deterministic keys via
 * {@code OutboxIdempotencyKeys}, which means a duplicate enqueue is the result
 * of an at-least-once domain event being replayed.
 *
 * <p>{@link #countByIdempotencyKey(String)} and {@link #persistRow(RecruitmentOutboxRow)}
 * are package-private seams so unit tests can override them — Mockito cannot stub
 * Panache statics inherited from {@code PanacheEntityBase}.
 */
@ApplicationScoped
public class RecruitmentOutboxService {

    private static final Logger LOG = Logger.getLogger(RecruitmentOutboxService.class);

    private final ObjectMapper mapper;

    @Inject
    public RecruitmentOutboxService(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** Caller MUST already be inside a transaction. */
    @Transactional(TxType.MANDATORY)
    public void enqueue(OutboxKind kind, String idempotencyKey, String relatedUuid, Object payload) {
        if (countByIdempotencyKey(idempotencyKey) > 0) {
            LOG.debugf("Outbox: skipping duplicate enqueue kind=%s key=%s", kind, idempotencyKey);
            return;
        }
        String json;
        try {
            json = mapper.writeValueAsString(payload);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialise outbox payload kind=" + kind, ex);
        }
        persistRow(RecruitmentOutboxRow.create(kind, idempotencyKey, relatedUuid, json));
        LOG.debugf("Outbox: enqueued kind=%s key=%s related=%s", kind, idempotencyKey, relatedUuid);
    }

    long countByIdempotencyKey(String key) {
        return RecruitmentOutboxRow.count("idempotencyKey = ?1", key);
    }

    void persistRow(RecruitmentOutboxRow row) {
        RecruitmentOutboxRow.persist(row);
    }
}
