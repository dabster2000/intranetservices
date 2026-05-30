package dk.trustworks.intranet.expenseservice.migration;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Verifies the V357 backfill CASE logic on representative legacy rows. */
@QuarkusTest
class V357BackfillIT {

    @Inject EntityManager em;

    private static final String BACKFILL =
        "UPDATE expenses SET " +
        " state = CASE " +
        "  WHEN status = 'VERIFIED_BOOKED' THEN 'BOOKED' " +
        "  WHEN status = 'VERIFIED_UNBOOKED' THEN 'POSTED' " +
        "  WHEN status IN ('UPLOADED','VOUCHER_CREATED','PROCESSING') THEN 'POSTING' " +
        "  WHEN status IN ('UP_FAILED','NO_FILE','NO_USER') THEN 'NEEDS_ATTENTION' " +
        "  WHEN status = 'VALIDATED' THEN 'APPROVED' " +
        "  WHEN status = 'DELETED' AND hr_decision = 'REJECTED' THEN 'REJECTED' " +
        "  WHEN status = 'DELETED' THEN 'DELETED' " +
        "  WHEN status = 'CREATED' AND review_state = 'NEEDS_FIX' THEN 'NEEDS_ATTENTION' " +
        "  WHEN status = 'CREATED' AND review_state IN ('NEEDS_JUSTIFICATION','HR_SENT_BACK') THEN 'NEEDS_ATTENTION' " +
        "  WHEN status = 'CREATED' AND review_state = 'PENDING_HR' THEN 'NEEDS_ATTENTION' " +
        "  WHEN status = 'CREATED' AND ai_validation_approved = 0 THEN 'NEEDS_ATTENTION' " +
        "  WHEN status = 'CREATED' THEN 'SUBMITTED' ELSE 'SUBMITTED' END, " +
        " attention_owner = CASE " +
        "  WHEN status IN ('UP_FAILED','NO_FILE','NO_USER') THEN 'ACCOUNTING' " +
        "  WHEN status = 'CREATED' AND review_state = 'NEEDS_FIX' THEN 'EMPLOYEE' " +
        "  WHEN status = 'CREATED' AND review_state IN ('NEEDS_JUSTIFICATION','HR_SENT_BACK') THEN 'EMPLOYEE' " +
        "  WHEN status = 'CREATED' AND review_state = 'PENDING_HR' THEN 'ACCOUNTING' " +
        "  WHEN status = 'CREATED' AND ai_validation_approved = 0 THEN 'ACCOUNTING' ELSE NULL END, " +
        " attention_kind = CASE " +
        "  WHEN status IN ('UP_FAILED','NO_FILE','NO_USER') THEN 'TECHNICAL' " +
        "  WHEN status = 'CREATED' AND review_state = 'NEEDS_FIX' THEN 'RECEIPT' " +
        "  WHEN status = 'CREATED' AND review_state IN ('NEEDS_JUSTIFICATION','HR_SENT_BACK') THEN 'JUSTIFICATION' " +
        "  WHEN status = 'CREATED' AND review_state = 'PENDING_HR' THEN 'POLICY' " +
        "  WHEN status = 'CREATED' AND ai_validation_approved = 0 THEN 'POLICY' ELSE NULL END " +
        "WHERE uuid LIKE 'v357it-%'";

    private void seed(String uuid, String status, String reviewState, Integer aiApproved, String hrDecision) {
        em.createNativeQuery(
            "INSERT INTO expenses (uuid, useruuid, amount, account, accountname, description, " +
            "datecreated, expensedate, datemodified, status, review_state, ai_validation_approved, " +
            "hr_decision, vouchernumber, ai_validation_count, version) " +
            "VALUES (?1, 'u', '100', '3585', 'Frokost', 'it', '2026-05-01', '2026-05-01', '2026-05-01', " +
            "?2, ?3, ?4, ?5, 0, 0, 0)")
            .setParameter(1, uuid).setParameter(2, status).setParameter(3, reviewState)
            .setParameter(4, aiApproved).setParameter(5, hrDecision)
            .executeUpdate();
    }

    private String stateOf(String uuid) {
        return (String) em.createNativeQuery("SELECT state FROM expenses WHERE uuid = ?1")
            .setParameter(1, uuid).getSingleResult();
    }

    private String ownerOf(String uuid) {
        return (String) em.createNativeQuery("SELECT attention_owner FROM expenses WHERE uuid = ?1")
            .setParameter(1, uuid).getSingleResult();
    }

    private String kindOf(String uuid) {
        return (String) em.createNativeQuery("SELECT attention_kind FROM expenses WHERE uuid = ?1")
            .setParameter(1, uuid).getSingleResult();
    }

    @Test
    @Transactional
    void backfill_maps_representative_rows() {
        seed("v357it-fiction", "VERIFIED_UNBOOKED", "PENDING_HR", 1, null);
        seed("v357it-fix",     "CREATED",           "NEEDS_FIX",  0, null);
        seed("v357it-tech",    "UP_FAILED",         null,         null, null);
        seed("v357it-pending", "CREATED",           "PENDING_HR", 1, null);

        em.createNativeQuery(BACKFILL).executeUpdate();
        em.clear();

        // Fiction-queue row: already posted → POSTED, stale PENDING_HR dropped.
        assertEquals("POSTED", stateOf("v357it-fiction"));
        assertNull(ownerOf("v357it-fiction"));

        assertEquals("NEEDS_ATTENTION", stateOf("v357it-fix"));
        assertEquals("EMPLOYEE", ownerOf("v357it-fix"));

        assertEquals("NEEDS_ATTENTION", stateOf("v357it-tech"));
        assertEquals("ACCOUNTING", ownerOf("v357it-tech"));

        assertEquals("NEEDS_ATTENTION", stateOf("v357it-pending"));
        assertEquals("ACCOUNTING", ownerOf("v357it-pending"));

        assertNull(kindOf("v357it-fiction"));
        assertEquals("RECEIPT",   kindOf("v357it-fix"));
        assertEquals("TECHNICAL", kindOf("v357it-tech"));
        assertEquals("POLICY",    kindOf("v357it-pending"));
    }
}
