package dk.trustworks.intranet.aggregates.practices.services;

import dk.trustworks.intranet.aggregates.utilization.services.UtilizationCalculationHelper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

import static dk.trustworks.intranet.aggregates.cxo.CxoSqlSupport.CXO_QUERY_TIMEOUT_MS;

/**
 * Reports snapshot coverage and provides an idempotent application fallback.
 * Production/direct-SQL coverage is trigger-backed. The fallback preserves normal
 * UserService edits after staging table-copy refreshes, which do not copy triggers;
 * direct SQL staging edits after such a refresh remain a staging-only limitation.
 */
@ApplicationScoped
public class PracticeAttributionService {

    static final String CLOSE_PRIOR_INTERVAL_SQL = """
            UPDATE user_practice_history
            SET effective_to = CURRENT_DATE, recorded_at = CURRENT_TIMESTAMP(6)
            WHERE useruuid = :userUuid
              AND effective_to IS NULL
              AND effective_from < CURRENT_DATE
            """;
    static final String DELETE_CURRENT_INTERVAL_SQL = """
            DELETE FROM user_practice_history
            WHERE useruuid = :userUuid
              AND effective_to IS NULL
              AND effective_from = CURRENT_DATE
            """;
    static final String UPSERT_CURRENT_INTERVAL_SQL = """
            INSERT INTO user_practice_history
                (uuid, useruuid, practice, effective_from, effective_to, recorded_at, source)
            VALUES
                (:uuid, :userUuid, :practice, CURRENT_DATE, NULL, CURRENT_TIMESTAMP(6), :source)
            ON DUPLICATE KEY UPDATE
                source = CASE
                    WHEN practice <=> VALUES(practice) AND effective_to IS NULL THEN source
                    ELSE VALUES(source)
                END,
                practice = VALUES(practice),
                effective_to = NULL,
                recorded_at = VALUES(recorded_at)
            """;

    @Inject
    EntityManager em;

    public AttributionMetadata metadata() {
        Query query = em.createNativeQuery("SELECT MIN(effective_from) FROM user_practice_history");
        query.setHint("jakarta.persistence.query.timeout", CXO_QUERY_TIMEOUT_MS);
        Object value = query.getSingleResult();
        return metadataFor(value);
    }

    static AttributionMetadata metadataFor(Object value) {
        LocalDate coverageStart = value == null ? null : toLocalDate(value);
        return new AttributionMetadata(
                UtilizationCalculationHelper.PRACTICE_ATTRIBUTION_METHOD,
                coverageStart,
                UtilizationCalculationHelper.PRACTICE_ATTRIBUTION_NOTE
        );
    }

    /** Idempotent application fallback for environments where table-copy sync drops triggers. */
    public void recordUserCreated(String userUuid, Object practice) {
        String next = practiceValue(practice);
        if (userUuid == null || next == null) return;
        upsertCurrent(userUuid, next, "USER_SERVICE_CREATE_FALLBACK");
    }

    /** Mirrors the trigger's daily-grain close/delete/upsert semantics using DB CURRENT_DATE. */
    public void recordPracticeChange(String userUuid, Object previousPractice, Object newPractice) {
        String previous = practiceValue(previousPractice);
        String next = practiceValue(newPractice);
        if (userUuid == null || Objects.equals(previous, next)) return;

        Query closePrior = em.createNativeQuery(CLOSE_PRIOR_INTERVAL_SQL);
        closePrior.setParameter("userUuid", userUuid);
        closePrior.setHint("jakarta.persistence.query.timeout", CXO_QUERY_TIMEOUT_MS);
        closePrior.executeUpdate();

        if (next == null) {
            Query deleteCurrent = em.createNativeQuery(DELETE_CURRENT_INTERVAL_SQL);
            deleteCurrent.setParameter("userUuid", userUuid);
            deleteCurrent.setHint("jakarta.persistence.query.timeout", CXO_QUERY_TIMEOUT_MS);
            deleteCurrent.executeUpdate();
        } else {
            upsertCurrent(userUuid, next, "USER_SERVICE_UPDATE_FALLBACK");
        }
    }

    private void upsertCurrent(String userUuid, String practice, String source) {
        Query upsert = em.createNativeQuery(UPSERT_CURRENT_INTERVAL_SQL);
        upsert.setParameter("uuid", UUID.randomUUID().toString());
        upsert.setParameter("userUuid", userUuid);
        upsert.setParameter("practice", practice);
        upsert.setParameter("source", source);
        upsert.setHint("jakarta.persistence.query.timeout", CXO_QUERY_TIMEOUT_MS);
        upsert.executeUpdate();
    }

    private static String practiceValue(Object practice) {
        if (practice == null) return null;
        String value = practice.toString().trim();
        return value.isEmpty() ? null : value;
    }

    private static LocalDate toLocalDate(Object value) {
        if (value instanceof LocalDate date) return date;
        if (value instanceof java.sql.Date date) return date.toLocalDate();
        throw new IllegalStateException("Unexpected practice coverage date type: " + value.getClass());
    }

    public record AttributionMetadata(String method, LocalDate coverageStartDate, String note) {}
}
