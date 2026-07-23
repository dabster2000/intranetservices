package dk.trustworks.intranet.recruitmentservice.resources;

import jakarta.persistence.EntityManager;

import java.util.List;
import java.util.UUID;

/**
 * Shared native-SQL fixture helpers for the P8 profile/timeline/grid API
 * tests — the same idioms as {@code RecruitmentPositionBoardApiTest}
 * (staging-shaped local DB: {@code user} has no {@code active} column,
 * {@code userstatus} untouched, everything cleaned up per test class).
 * Events are seeded with raw INSERTs (the single-writer ArchUnit rule
 * excludes tests, and no reactor fires without an EventBus publish).
 */
public final class P8ProfileFixtures {

    public static final String PIPELINE_FLAG = "recruitment.pipeline.enabled";
    public static final String DOSSIER_FLAG = "recruitment.dossier.enabled";

    private P8ProfileFixtures() {
    }

    // ---- Feature flags ---------------------------------------------------------

    /** Upsert a flag; returns the previous value (null = row was absent). */
    public static String setFlag(EntityManager em, String key, String value) {
        List<?> current = em.createNativeQuery(
                        "SELECT setting_value FROM app_settings WHERE setting_key = :key")
                .setParameter("key", key).getResultList();
        String previous = current.isEmpty() ? null : (String) current.get(0);
        if (previous == null) {
            em.createNativeQuery("""
                            INSERT INTO app_settings (setting_key, setting_value, category)
                            VALUES (:key, :value, 'recruitment')
                            """)
                    .setParameter("key", key).setParameter("value", value).executeUpdate();
        } else {
            em.createNativeQuery(
                            "UPDATE app_settings SET setting_value = :value WHERE setting_key = :key")
                    .setParameter("value", value).setParameter("key", key).executeUpdate();
        }
        return previous;
    }

    /** Restore a flag to its pre-test value (null deletes the row). */
    public static void restoreFlag(EntityManager em, String key, String previous) {
        if (previous == null) {
            em.createNativeQuery("DELETE FROM app_settings WHERE setting_key = :key")
                    .setParameter("key", key).executeUpdate();
        } else {
            em.createNativeQuery(
                            "UPDATE app_settings SET setting_value = :value WHERE setting_key = :key")
                    .setParameter("value", previous).setParameter("key", key).executeUpdate();
        }
    }

    // ---- Users, roles, leads ---------------------------------------------------

    public static void insertUser(EntityManager em, String uuid, String firstname, String lastname) {
        em.createNativeQuery("""
                        INSERT INTO user (uuid, firstname, lastname, email, username, password, type,
                                          created, cpr, birthday)
                        VALUES (:uuid, :firstname, :lastname, :email, :username, 'x', 'CONSULTANT',
                                NOW(), '0000000000', '2000-01-01')
                        """)
                .setParameter("uuid", uuid)
                .setParameter("firstname", firstname)
                .setParameter("lastname", lastname)
                .setParameter("email", uuid + "@example.com")
                .setParameter("username", uuid)
                .executeUpdate();
    }

    public static void insertRole(EntityManager em, String userUuid, String role) {
        em.createNativeQuery("INSERT INTO roles (uuid, role, useruuid) VALUES (:uuid, :role, :user)")
                .setParameter("uuid", UUID.randomUUID().toString())
                .setParameter("role", role)
                .setParameter("user", userUuid)
                .executeUpdate();
    }

    public static void insertPractice(EntityManager em, String uuid) {
        em.createNativeQuery("""
                        INSERT INTO practice (code, uuid, name, active, sort_order,
                                              created_at, updated_at, created_by)
                        VALUES (:code, :uuid, 'P8 Fixture', 1, 998, NOW(), NOW(), 'test')
                        """)
                .setParameter("code", "P" + uuid.substring(0, 7))
                .setParameter("uuid", uuid)
                .executeUpdate();
    }

    public static void insertPracticeLead(EntityManager em, String userUuid, String practiceUuid) {
        em.createNativeQuery("""
                        INSERT INTO practice_lead (uuid, practice_uuid, useruuid, startdate, enddate,
                                                   created_at, updated_at, created_by)
                        VALUES (:uuid, :practice, :user, '2024-01-01', NULL, NOW(), NOW(), 'test')
                        """)
                .setParameter("uuid", UUID.randomUUID().toString())
                .setParameter("practice", practiceUuid)
                .setParameter("user", userUuid)
                .executeUpdate();
    }

    public static void insertTeamLeader(EntityManager em, String userUuid, String teamUuid) {
        em.createNativeQuery("""
                        INSERT INTO teamroles (uuid, teamuuid, useruuid, startdate, enddate, membertype)
                        VALUES (:uuid, :team, :user, '2024-01-01', NULL, 'LEADER')
                        """)
                .setParameter("uuid", UUID.randomUUID().toString())
                .setParameter("team", teamUuid)
                .setParameter("user", userUuid)
                .executeUpdate();
    }

    // ---- Positions, circles ----------------------------------------------------

    public static void insertPosition(EntityManager em, String uuid, String title, String track,
                               String practiceUuid, String teamUuid, String hiringOwnerUuid) {
        insertPosition(em, uuid, title, track, practiceUuid, teamUuid, hiringOwnerUuid,
                "[\"SCREENING\",\"INTERVIEW_1\",\"OFFER\",\"HIRED\"]",
                STANDARD_SCORECARD_TEMPLATE_JSON);
    }

    /** The P2 standard 4-attribute framework, as the position column stores it. */
    public static final String STANDARD_SCORECARD_TEMPLATE_JSON = """
            [{"code":"WHY_CONSULTING","label":"Why consulting"},\
            {"code":"COMMERCIAL_DRIVE","label":"Commercial drive"},\
            {"code":"UNCERTAINTY","label":"Handling uncertainty"},\
            {"code":"CULTURE_FIT","label":"Culture fit"}]""";

    /** Full-control variant (P11: trimmed staff templates, custom stage sets). */
    public static void insertPosition(EntityManager em, String uuid, String title, String track,
                               String practiceUuid, String teamUuid, String hiringOwnerUuid,
                               String stageSetJson, String scorecardTemplateJson) {
        em.createNativeQuery("""
                        INSERT INTO recruitment_positions
                            (uuid, title, hiring_track, practice_uuid, team_uuid, hiring_owner_uuid,
                             stage_set, scorecard_template, status, opened_at, created_at, updated_at, created_by)
                        VALUES (:uuid, :title, :track, :practice, :team, :owner,
                                :stageSet, :template,
                                'OPEN', NOW(3), NOW(), NOW(), 'test')
                        """)
                .setParameter("uuid", uuid)
                .setParameter("title", title)
                .setParameter("track", track)
                .setParameter("practice", practiceUuid)
                .setParameter("team", teamUuid)
                .setParameter("owner", hiringOwnerUuid)
                .setParameter("stageSet", stageSetJson)
                .setParameter("template", scorecardTemplateJson)
                .executeUpdate();
    }

    public static void insertCircleMember(EntityManager em, String positionUuid, String userUuid) {
        em.createNativeQuery("""
                        INSERT INTO recruitment_circle_members
                            (position_uuid, user_uuid, role_in_circle, added_at, added_by_uuid)
                        VALUES (:p, :u, 'RECRUITER', NOW(3), :u)
                        """)
                .setParameter("p", positionUuid)
                .setParameter("u", userUuid)
                .executeUpdate();
    }

    // ---- Candidates & applications ---------------------------------------------

    public static void insertCandidate(EntityManager em, String uuid, String firstName, String lastName,
                                String status, String poolStatus, String tagsJson,
                                String createdByUuid) {
        em.createNativeQuery("""
                        INSERT INTO recruitment_candidates
                            (uuid, first_name, last_name, status, pool_status, tags,
                             created_by_useruuid, created_at, updated_at)
                        VALUES (:uuid, :first, :last, :status, :poolStatus, :tags,
                                :actor, NOW(), NOW())
                        """)
                .setParameter("uuid", uuid)
                .setParameter("first", firstName)
                .setParameter("last", lastName)
                .setParameter("status", status)
                .setParameter("poolStatus", poolStatus)
                .setParameter("tags", tagsJson)
                .setParameter("actor", createdByUuid)
                .executeUpdate();
    }

    public static void insertOpenApplication(EntityManager em, String uuid, String candidateUuid,
                                      String positionUuid, String stage) {
        em.createNativeQuery("""
                        INSERT INTO recruitment_applications
                            (uuid, candidate_uuid, position_uuid, stage,
                             stage_entered_at, created_at, updated_at, created_by)
                        VALUES (:uuid, :candidate, :position, :stage,
                                UTC_TIMESTAMP(3), NOW(), NOW(), 'test')
                        """)
                .setParameter("uuid", uuid)
                .setParameter("candidate", candidateUuid)
                .setParameter("position", positionUuid)
                .setParameter("stage", stage)
                .executeUpdate();
    }

    public static void insertClosedApplication(EntityManager em, String uuid, String candidateUuid,
                                        String positionUuid, String terminal) {
        em.createNativeQuery("""
                        INSERT INTO recruitment_applications
                            (uuid, candidate_uuid, position_uuid, stage, terminal,
                             stage_entered_at, created_at, updated_at, created_by)
                        VALUES (:uuid, :candidate, :position, 'SCREENING', :terminal,
                                UTC_TIMESTAMP(3), NOW(), NOW(), 'test')
                        """)
                .setParameter("uuid", uuid)
                .setParameter("candidate", candidateUuid)
                .setParameter("position", positionUuid)
                .setParameter("terminal", terminal)
                .executeUpdate();
    }

    // ---- Events (raw stream rows) ----------------------------------------------

    /**
     * Insert one event row and return its {@code seq}. {@code pii_state}
     * derives from the pii argument, mirroring the recorder.
     */
    public static long insertEvent(EntityManager em, String eventType, String candidateUuid,
                            String applicationUuid, String positionUuid,
                            String actorType, String actorUuid, String visibility,
                            String payloadJson, String piiJson) {
        String eventId = UUID.randomUUID().toString();
        em.createNativeQuery("""
                        INSERT INTO recruitment_events
                            (event_id, event_type, candidate_uuid, application_uuid, position_uuid,
                             actor_uuid, actor_type, occurred_at, visibility, payload, pii, pii_state)
                        VALUES (:eventId, :type, :candidate, :application, :position,
                                :actor, :actorType, UTC_TIMESTAMP(3), :visibility, :payload, :pii,
                                :piiState)
                        """)
                .setParameter("eventId", eventId)
                .setParameter("type", eventType)
                .setParameter("candidate", candidateUuid)
                .setParameter("application", applicationUuid)
                .setParameter("position", positionUuid)
                .setParameter("actor", actorUuid)
                .setParameter("actorType", actorType)
                .setParameter("visibility", visibility)
                .setParameter("payload", payloadJson)
                .setParameter("pii", piiJson)
                .setParameter("piiState", piiJson == null ? "NONE" : "PRESENT")
                .executeUpdate();
        return ((Number) em.createNativeQuery(
                        "SELECT seq FROM recruitment_events WHERE event_id = :eventId")
                .setParameter("eventId", eventId)
                .getSingleResult()).longValue();
    }

    // ---- Answers, files, consents ----------------------------------------------

    /** XOR ownership: pass exactly one of applicationUuid / candidateUuid. */
    public static void insertAnswer(EntityManager em, String applicationUuid, String candidateUuid,
                             String questionKey, String answer) {
        em.createNativeQuery("""
                        INSERT INTO recruitment_application_answers
                            (uuid, application_uuid, candidate_uuid, question_key, answer, created_at)
                        VALUES (:uuid, :application, :candidate, :key, :answer, UTC_TIMESTAMP(3))
                        """)
                .setParameter("uuid", UUID.randomUUID().toString())
                .setParameter("application", applicationUuid)
                .setParameter("candidate", candidateUuid)
                .setParameter("key", questionKey)
                .setParameter("answer", answer)
                .executeUpdate();
    }

    public static void insertFileRow(EntityManager em, String uuid, String relatedUuid, String filename) {
        em.createNativeQuery("""
                        INSERT INTO files (uuid, relateduuid, type, name, filename, uploaddate)
                        VALUES (:uuid, :related, 'DOCUMENT', :filename, :filename, CURDATE())
                        """)
                .setParameter("uuid", uuid)
                .setParameter("related", relatedUuid)
                .setParameter("filename", filename)
                .executeUpdate();
    }

    public static void insertConsent(EntityManager em, String uuid, String candidateUuid, String kind,
                              String status, String grantedAt, String expiresAt, String tokenHash) {
        em.createNativeQuery("""
                        INSERT INTO recruitment_consents
                            (uuid, candidate_uuid, kind, status, granted_at, expires_at, token_hash,
                             created_at, updated_at, created_by)
                        VALUES (:uuid, :candidate, :kind, :status, :grantedAt, :expiresAt, :tokenHash,
                                NOW(), NOW(), 'test')
                        """)
                .setParameter("uuid", uuid)
                .setParameter("candidate", candidateUuid)
                .setParameter("kind", kind)
                .setParameter("status", status)
                .setParameter("grantedAt", grantedAt)
                .setParameter("expiresAt", expiresAt)
                .setParameter("tokenHash", tokenHash)
                .executeUpdate();
    }

    // ---- Interviews & scorecards (P11) -------------------------------------------

    /**
     * Insert one interview row. {@code interviewerUuidsJson} is the raw JSON
     * array (e.g. {@code ["uuid-a","uuid-b"]}); {@code scheduledAt} may be
     * null for "now + 1 day".
     */
    public static void insertInterview(EntityManager em, String uuid, String applicationUuid,
                                String kind, Integer round, String interviewerUuidsJson,
                                String status) {
        em.createNativeQuery("""
                        INSERT INTO recruitment_interviews
                            (uuid, application_uuid, kind, round, scheduled_at, interviewer_uuids,
                             location, status, created_at, updated_at, created_by)
                        VALUES (:uuid, :application, :kind, :round,
                                DATE_ADD(UTC_TIMESTAMP(3), INTERVAL 1 DAY), :interviewers,
                                'Teams', :status, NOW(), NOW(), 'test')
                        """)
                .setParameter("uuid", uuid)
                .setParameter("application", applicationUuid)
                .setParameter("kind", kind)
                .setParameter("round", round)
                .setParameter("interviewers", interviewerUuidsJson)
                .setParameter("status", status)
                .executeUpdate();
    }

    /** Insert one submitted scorecard (scores keyed on the standard template). */
    public static void insertScorecard(EntityManager em, String uuid, String interviewUuid,
                                String interviewerUuid, String recommendation) {
        em.createNativeQuery("""
                        INSERT INTO recruitment_scorecards
                            (uuid, interview_uuid, interviewer_uuid, scores, recommendation,
                             submitted_at, created_at, updated_at, created_by)
                        VALUES (:uuid, :interview, :interviewer,
                                '{"WHY_CONSULTING":3,"COMMERCIAL_DRIVE":3,"UNCERTAINTY":3,"CULTURE_FIT":3}',
                                :recommendation, UTC_TIMESTAMP(3), NOW(), NOW(), 'test')
                        """)
                .setParameter("uuid", uuid)
                .setParameter("interview", interviewUuid)
                .setParameter("interviewer", interviewerUuid)
                .setParameter("recommendation", recommendation)
                .executeUpdate();
    }

    // ---- Cleanup ----------------------------------------------------------------

    public static void cleanupRecruitmentRows(EntityManager em, List<String> candidateUuids,
                                       List<String> positionUuids, List<String> userUuids,
                                       String practiceUuid) {
        if (!candidateUuids.isEmpty()) {
            // FK order: scorecards → interviews → applications (RESTRICT FKs).
            em.createNativeQuery("""
                            DELETE FROM recruitment_scorecards WHERE interview_uuid IN
                            (SELECT i.uuid FROM recruitment_interviews i
                             JOIN recruitment_applications a ON a.uuid = i.application_uuid
                             WHERE a.candidate_uuid IN :c)
                            """)
                    .setParameter("c", candidateUuids).executeUpdate();
            em.createNativeQuery("""
                            DELETE FROM recruitment_interviews WHERE application_uuid IN
                            (SELECT uuid FROM recruitment_applications WHERE candidate_uuid IN :c)
                            """)
                    .setParameter("c", candidateUuids).executeUpdate();
            em.createNativeQuery("DELETE FROM recruitment_events WHERE candidate_uuid IN :c")
                    .setParameter("c", candidateUuids).executeUpdate();
            em.createNativeQuery("DELETE FROM recruitment_application_answers WHERE candidate_uuid IN :c")
                    .setParameter("c", candidateUuids).executeUpdate();
            em.createNativeQuery("""
                            DELETE FROM recruitment_application_answers WHERE application_uuid IN
                            (SELECT uuid FROM recruitment_applications WHERE candidate_uuid IN :c)
                            """)
                    .setParameter("c", candidateUuids).executeUpdate();
            em.createNativeQuery("DELETE FROM recruitment_consents WHERE candidate_uuid IN :c")
                    .setParameter("c", candidateUuids).executeUpdate();
            em.createNativeQuery("DELETE FROM files WHERE relateduuid IN :c")
                    .setParameter("c", candidateUuids).executeUpdate();
            em.createNativeQuery("DELETE FROM recruitment_applications WHERE candidate_uuid IN :c")
                    .setParameter("c", candidateUuids).executeUpdate();
            em.createNativeQuery("DELETE FROM recruitment_candidates WHERE uuid IN :c")
                    .setParameter("c", candidateUuids).executeUpdate();
        }
        if (!positionUuids.isEmpty()) {
            em.createNativeQuery("DELETE FROM recruitment_circle_members WHERE position_uuid IN :p")
                    .setParameter("p", positionUuids).executeUpdate();
            em.createNativeQuery("DELETE FROM recruitment_positions WHERE uuid IN :p")
                    .setParameter("p", positionUuids).executeUpdate();
        }
        if (!userUuids.isEmpty()) {
            em.createNativeQuery("DELETE FROM practice_lead WHERE useruuid IN :u")
                    .setParameter("u", userUuids).executeUpdate();
            em.createNativeQuery("DELETE FROM teamroles WHERE useruuid IN :u")
                    .setParameter("u", userUuids).executeUpdate();
            em.createNativeQuery("DELETE FROM roles WHERE useruuid IN :u")
                    .setParameter("u", userUuids).executeUpdate();
            em.createNativeQuery("DELETE FROM user WHERE uuid IN :u")
                    .setParameter("u", userUuids).executeUpdate();
        }
        if (practiceUuid != null) {
            em.createNativeQuery("DELETE FROM practice WHERE uuid = :p")
                    .setParameter("p", practiceUuid).executeUpdate();
        }
    }
}
