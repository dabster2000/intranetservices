package dk.trustworks.intranet.aggregates.practices.services;

import dk.trustworks.intranet.aggregates.practices.services.DeliveryEvidencePoll.DeliveryBounds;
import dk.trustworks.intranet.aggregates.practices.services.DeliveryEvidencePoll.DeliveryPollGateway;
import dk.trustworks.intranet.aggregates.practices.services.DeliveryEvidencePoll.DeliveryWatermark;
import dk.trustworks.intranet.aggregates.practices.services.PracticeRevenueDirtyMarker.WatermarkConflictException;
import jakarta.persistence.EntityManager;

import java.math.BigInteger;
import java.time.LocalDate;
import java.util.List;

/**
 * Production {@link DeliveryPollGateway} that binds the shared {@link DeliveryEvidencePoll} SQL to the
 * transaction-bound {@link EntityManager}. It holds no ordering logic; that lives in
 * {@link DeliveryEvidencePoll#poll}. The integration test supplies an equivalent raw-JDBC gateway that
 * runs the same SQL constants, so both drive identical statements.
 */
final class EntityManagerDeliveryPollGateway implements DeliveryPollGateway {

    private final EntityManager em;

    EntityManagerDeliveryPollGateway(EntityManager em) {
        this.em = em;
    }

    @Override
    public DeliveryWatermark snapshot() {
        Object[] row = (Object[]) em.createNativeQuery(DeliveryEvidencePoll.POLL_WATERMARK_SNAPSHOT_SQL)
                .getSingleResult();
        return watermark(row);
    }

    @Override
    public String lockPublicationStatus() {
        Object[] row = (Object[]) em.createNativeQuery(DeliveryEvidencePoll.PUBLICATION_LOCK_SQL)
                .getSingleResult();
        return String.valueOf(row[0]);
    }

    @Override
    public BigInteger maxLogId() {
        return integer(em.createNativeQuery(DeliveryEvidencePoll.LOG_MAX_SQL).getSingleResult());
    }

    @Override
    public BigInteger settleHorizon(BigInteger cursor, BigInteger max) {
        @SuppressWarnings("unchecked")
        List<Object> ids = em.createNativeQuery(DeliveryEvidencePoll.LOG_SETTLE_LOCK_SQL)
                .setParameter("cursor", cursor).setParameter("target", max).getResultList();
        return ids.isEmpty() ? cursor : integer(ids.getLast());
    }

    @Override
    public DeliveryWatermark lockWatermark() {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = em.createNativeQuery(DeliveryEvidencePoll.WATERMARK_LOCK_SQL).getResultList();
        if (rows.size() != 1) {
            throw new WatermarkConflictException("DELIVERY_EVIDENCE_WATERMARK_MISSING");
        }
        return watermark(rows.getFirst());
    }

    @Override
    public boolean failRetentionGap(BigInteger cursor, BigInteger pruned) {
        return em.createNativeQuery(DeliveryEvidencePoll.RETENTION_FAIL_SQL)
                .setParameter("cursor", cursor).setParameter("pruned", pruned).executeUpdate() == 1;
    }

    @Override
    public DeliveryBounds pollBounds(BigInteger cursor, BigInteger target) {
        Object[] row = (Object[]) em.createNativeQuery(DeliveryEvidencePoll.POLL_BOUNDS_SQL)
                .setParameter("cursor", cursor).setParameter("target", target).getSingleResult();
        return new DeliveryBounds(date(row[0]), date(row[1]));
    }

    @Override
    public boolean advance(BigInteger cursor, BigInteger target, LocalDate affectedStart,
                           LocalDate affectedEnd, boolean relevant) {
        return em.createNativeQuery(DeliveryEvidencePoll.ADVANCE_SQL)
                .setParameter("target", target).setParameter("cursor", cursor)
                .setParameter("relevant", relevant ? 1 : 0)
                .setParameter("affectedStart", affectedStart)
                .setParameter("affectedEnd", affectedEnd).executeUpdate() == 1;
    }

    private static DeliveryWatermark watermark(Object[] row) {
        return new DeliveryWatermark(integer(row[0]), integer(row[1]), String.valueOf(row[2]),
                row[3] != null, row[4] == null ? null : String.valueOf(row[4]));
    }

    private static BigInteger integer(Object value) {
        return value instanceof BigInteger i ? i : new BigInteger(value.toString());
    }

    private static LocalDate date(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDate date) {
            return date;
        }
        return ((java.sql.Date) value).toLocalDate();
    }
}
