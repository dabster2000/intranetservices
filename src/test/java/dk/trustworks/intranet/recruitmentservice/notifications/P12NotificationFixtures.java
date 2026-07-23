package dk.trustworks.intranet.recruitmentservice.notifications;

import jakarta.persistence.EntityManager;

import java.util.UUID;

/**
 * P12-specific fixture SQL on top of {@code P8ProfileFixtures}: referral
 * rows, referred-by/source columns on candidates, Slack links on users,
 * application stage/terminal mutation, and reactor-state resets.
 */
final class P12NotificationFixtures {

    private P12NotificationFixtures() {
    }

    static void setCandidateReferredBy(EntityManager em, String candidateUuid, String referrerUuid) {
        em.createNativeQuery("UPDATE recruitment_candidates SET referred_by_user_uuid = :ref, "
                        + "source = 'REFERRAL' WHERE uuid = :uuid")
                .setParameter("ref", referrerUuid)
                .setParameter("uuid", candidateUuid)
                .executeUpdate();
    }

    static void setCandidateSource(EntityManager em, String candidateUuid, String source) {
        em.createNativeQuery("UPDATE recruitment_candidates SET source = :source WHERE uuid = :uuid")
                .setParameter("source", source)
                .setParameter("uuid", candidateUuid)
                .executeUpdate();
    }

    static void setCandidateStatus(EntityManager em, String candidateUuid, String status) {
        em.createNativeQuery("UPDATE recruitment_candidates SET status = :status WHERE uuid = :uuid")
                .setParameter("status", status)
                .setParameter("uuid", candidateUuid)
                .executeUpdate();
    }

    static void setApplicationStage(EntityManager em, String applicationUuid, String stage) {
        em.createNativeQuery("UPDATE recruitment_applications SET stage = :stage, "
                        + "stage_entered_at = UTC_TIMESTAMP(3) WHERE uuid = :uuid")
                .setParameter("stage", stage)
                .setParameter("uuid", applicationUuid)
                .executeUpdate();
    }

    static void setApplicationTerminal(EntityManager em, String applicationUuid, String terminal) {
        em.createNativeQuery("UPDATE recruitment_applications SET terminal = :terminal WHERE uuid = :uuid")
                .setParameter("terminal", terminal)
                .setParameter("uuid", applicationUuid)
                .executeUpdate();
    }

    static void setUserSlackLink(EntityManager em, String userUuid, String slackId) {
        em.createNativeQuery("UPDATE user SET slackusername = :slack WHERE uuid = :uuid")
                .setParameter("slack", slackId)
                .setParameter("uuid", userUuid)
                .executeUpdate();
    }

    static String insertReferral(EntityManager em, String referrerUuid, String candidateName,
                                 String whyText, String status, String candidateUuid) {
        String uuid = UUID.randomUUID().toString();
        em.createNativeQuery("""
                        INSERT INTO recruitment_referrals
                            (uuid, referrer_uuid, referrer_relation, candidate_name, why_text,
                             status, candidate_uuid, submitted_at, created_at, updated_at, created_by)
                        VALUES (:uuid, :referrer, 'FORMER_COLLEAGUE', :name, :why,
                                :status, :candidate, UTC_TIMESTAMP(3), NOW(), NOW(), :referrer)
                        """)
                .setParameter("uuid", uuid)
                .setParameter("referrer", referrerUuid)
                .setParameter("name", candidateName)
                .setParameter("why", whyText)
                .setParameter("status", status)
                .setParameter("candidate", candidateUuid)
                .executeUpdate();
        return uuid;
    }

    static void deleteReferralsBy(EntityManager em, String referrerUuid) {
        em.createNativeQuery("DELETE FROM recruitment_referrals WHERE referrer_uuid = :ref")
                .setParameter("ref", referrerUuid)
                .executeUpdate();
    }

    /**
     * Forget a reactor entirely (offset + dedupe rows) — the "freshly
     * deployed reactor" precondition for the offset-seeding DoD test.
     */
    static void resetReactorState(EntityManager em, String reactorName) {
        em.createNativeQuery("DELETE FROM recruitment_reactor_deliveries WHERE reactor_name = :name")
                .setParameter("name", reactorName)
                .executeUpdate();
        em.createNativeQuery("DELETE FROM recruitment_reactor_offsets WHERE reactor_name = :name")
                .setParameter("name", reactorName)
                .executeUpdate();
    }
}
