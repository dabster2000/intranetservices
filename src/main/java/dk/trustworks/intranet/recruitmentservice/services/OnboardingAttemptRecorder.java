package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.recruitmentservice.model.OnboardingDocumentType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;

import java.util.List;
import java.util.UUID;

/**
 * Server-side tally of AI document-gate rejections, per
 * {@code (token, document_type)}. Backs the "submit anyway" escape hatch on
 * the public onboarding upload page (table created in V561).
 *
 * <h3>Why the count has to live here</h3>
 * <p>{@code POST /onboarding/tokens/{token}/upload} is {@code @PermitAll} —
 * the token UUID is the only credential. If the page were allowed to assert
 * "I have been rejected twice, let me through", then a single hand-written
 * request would bypass the document gate entirely for anyone holding a link.
 * So the override is offered only against rejections <b>this service wrote
 * itself</b>, and a {@code forceReview} flag that is not backed by such a
 * count is ignored rather than rejected — a forged request simply gets the
 * normal AI validation it was trying to skip.</p>
 *
 * <h3>Why it is in the database and not in memory</h3>
 * <p>Two attempts by the same candidate can land on two different ECS tasks,
 * and tasks restart. An in-process counter would then never reach the
 * threshold, which is the same dead end the override exists to remove.</p>
 *
 * <h3>Concurrency</h3>
 * <p>{@link #recordRejection} is a single {@code INSERT … ON DUPLICATE KEY
 * UPDATE} against {@code uk_oua_token_doctype}, so two uploads racing on the
 * same type cannot both read 1 and both write 2. The read-back runs in the
 * same transaction and therefore sees the row this call just wrote.</p>
 *
 * <p>Native SQL rather than a Panache entity on purpose: the atomic increment
 * has no Panache expression, and nothing else in the domain needs the row as
 * an object. PII boundary: only the token UUID, the document type and a
 * count are touched — never a filename, the bytes, or the model's reason.</p>
 */
@JBossLog
@ApplicationScoped
public class OnboardingAttemptRecorder {

    /**
     * AI rejections required before the page offers "submit anyway".
     *
     * <p>Two, not one: a first refusal is very often the honest answer to a
     * genuinely bad photo (a thumb over the card, a dark room), and the
     * retry-with-a-better-picture loop is the one that should happen. A
     * second refusal of the same document type is the point where insisting
     * stops being useful to the candidate — and, as the gpt-4o-mini outage
     * showed, the point where the fault is more likely ours than theirs.</p>
     */
    public static final int REJECTIONS_BEFORE_OVERRIDE = 2;

    @Inject
    EntityManager entityManager;

    /**
     * Record one AI rejection and return the running total for this
     * {@code (token, type)} pair.
     */
    @Transactional
    public int recordRejection(String tokenUuid, OnboardingDocumentType type) {
        entityManager.createNativeQuery("""
                        INSERT INTO onboarding_upload_attempts
                              (uuid, token_uuid, document_type, ai_rejection_count, last_rejected_at)
                        VALUES (:uuid, :token, :type, 1, CURRENT_TIMESTAMP(6))
                        ON DUPLICATE KEY UPDATE
                              ai_rejection_count = ai_rejection_count + 1,
                              last_rejected_at   = CURRENT_TIMESTAMP(6)
                        """)
                .setParameter("uuid", UUID.randomUUID().toString())
                .setParameter("token", tokenUuid)
                .setParameter("type", type.name())
                .executeUpdate();
        return rejectionCount(tokenUuid, type);
    }

    /** Rejections recorded so far for this {@code (token, type)}; 0 if none. */
    @Transactional
    public int rejectionCount(String tokenUuid, OnboardingDocumentType type) {
        List<?> rows = entityManager.createNativeQuery("""
                        SELECT ai_rejection_count
                          FROM onboarding_upload_attempts
                         WHERE token_uuid = :token AND document_type = :type
                        """)
                .setParameter("token", tokenUuid)
                .setParameter("type", type.name())
                .getResultList();
        if (rows.isEmpty() || rows.get(0) == null) return 0;
        return ((Number) rows.get(0)).intValue();
    }
}
