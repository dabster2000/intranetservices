package dk.trustworks.intranet.recruitmentservice.resources;

import dk.trustworks.intranet.utils.NextsignSigningService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;

/**
 * P10 signature-send gate at the REST boundary (contract B1b/B3.3):
 * {@code POST /recruitment/candidates/{uuid}/dossier/send-signature} on a
 * candidate whose practice-track application sits at OFFER without a team
 * answers 409 {@code TEAM_NOT_ASSIGNED} with message + guidance — and
 * NextSign is NEVER called. Candidates that pass the gate proceed into the
 * legacy fail-fast validation (this fixture's dossier has no signers, so the
 * flow stops at the "no signers configured" 409 — a DIFFERENT conflict,
 * which proves the gate let them through) and NextSign is still never
 * reached.
 */
@QuarkusTest
class RecruitmentResourceSendSignatureGateTest {

    private static final String DOSSIER_FLAG = "recruitment.dossier.enabled";

    @Inject
    EntityManager em;

    @InjectMock
    NextsignSigningService nextsignSigningService;

    private String candidateUuid;
    private String positionUuid;
    private String partnerPositionUuid;
    private String actorUuid;
    private String previousFlagValue;

    @BeforeEach
    void seed() {
        candidateUuid = UUID.randomUUID().toString();
        positionUuid = UUID.randomUUID().toString();
        partnerPositionUuid = UUID.randomUUID().toString();
        actorUuid = UUID.randomUUID().toString();

        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("""
                            INSERT INTO recruitment_candidates
                                (uuid, first_name, last_name, email, status, source,
                                 created_by_useruuid, created_at, updated_at)
                            VALUES (:uuid, 'Gate', 'Fixture', :email, 'ACTIVE', 'OTHER',
                                    :actor, NOW(), NOW())
                            """)
                    .setParameter("uuid", candidateUuid)
                    .setParameter("email", candidateUuid + "@example.com")
                    .setParameter("actor", actorUuid).executeUpdate();
            em.createNativeQuery("""
                            INSERT INTO candidate_dossiers
                                (uuid, candidate_uuid, template_uuid, status)
                            VALUES (:uuid, :candidate, :template, 'OPEN')
                            """)
                    .setParameter("uuid", UUID.randomUUID().toString())
                    .setParameter("candidate", candidateUuid)
                    .setParameter("template", UUID.randomUUID().toString())
                    .executeUpdate();
            insertPosition(positionUuid, "PRACTICE_TEAM");
            insertPosition(partnerPositionUuid, "PARTNER");

            List<?> current = em.createNativeQuery(
                            "SELECT setting_value FROM app_settings WHERE setting_key = :key")
                    .setParameter("key", DOSSIER_FLAG).getResultList();
            previousFlagValue = current.isEmpty() ? null : (String) current.get(0);
            if (previousFlagValue == null) {
                em.createNativeQuery("""
                                INSERT INTO app_settings (setting_key, setting_value, category)
                                VALUES (:key, 'true', 'recruitment')
                                """)
                        .setParameter("key", DOSSIER_FLAG).executeUpdate();
            } else {
                em.createNativeQuery(
                                "UPDATE app_settings SET setting_value = 'true' WHERE setting_key = :key")
                        .setParameter("key", DOSSIER_FLAG).executeUpdate();
            }
        });
    }

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("DELETE FROM recruitment_events WHERE candidate_uuid = :c")
                    .setParameter("c", candidateUuid).executeUpdate();
            em.createNativeQuery("DELETE FROM recruitment_applications WHERE candidate_uuid = :c")
                    .setParameter("c", candidateUuid).executeUpdate();
            em.createNativeQuery("DELETE FROM candidate_dossiers WHERE candidate_uuid = :c")
                    .setParameter("c", candidateUuid).executeUpdate();
            em.createNativeQuery("DELETE FROM recruitment_candidates WHERE uuid = :c")
                    .setParameter("c", candidateUuid).executeUpdate();
            em.createNativeQuery("DELETE FROM recruitment_positions WHERE uuid IN :p")
                    .setParameter("p", List.of(positionUuid, partnerPositionUuid)).executeUpdate();
            if (previousFlagValue == null) {
                em.createNativeQuery("DELETE FROM app_settings WHERE setting_key = :key")
                        .setParameter("key", DOSSIER_FLAG).executeUpdate();
            } else {
                em.createNativeQuery(
                                "UPDATE app_settings SET setting_value = :value WHERE setting_key = :key")
                        .setParameter("value", previousFlagValue)
                        .setParameter("key", DOSSIER_FLAG).executeUpdate();
            }
        });
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void practiceTrackOfferWithoutTeam_answers409TeamNotAssigned_nextSignNeverCalled() {
        insertApplication(positionUuid, "OFFER", null);

        given()
                .contentType("application/json")
                .header("X-Requested-By", actorUuid)
                .body("{}")
                .when()
                .post("/recruitment/candidates/{uuid}/dossier/send-signature", candidateUuid)
                .then()
                .statusCode(409)
                .body("error", Matchers.equalTo("TEAM_NOT_ASSIGNED"))
                .body("message", Matchers.containsString("no team assigned"))
                .body("guidance", Matchers.containsString("Assign a team"));

        Mockito.verifyNoInteractions(nextsignSigningService);
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void offerWithTeamAssigned_passesTheGate() {
        insertApplication(positionUuid, "OFFER", UUID.randomUUID().toString());
        postSendSignatureExpectingLegacySignerConflict();
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void applicationBeforeOffer_passesTheGate() {
        insertApplication(positionUuid, "INTERVIEW_1", null);
        postSendSignatureExpectingLegacySignerConflict();
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void partnerTrackOfferWithoutTeam_passesTheGate() {
        insertApplication(partnerPositionUuid, "OFFER", null);
        postSendSignatureExpectingLegacySignerConflict();
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void candidateWithoutApplications_passesTheGate_legacyFlowUntouched() {
        postSendSignatureExpectingLegacySignerConflict();
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void alreadyHiredApplication_doesNotBlockFutureSends() {
        insertApplication(positionUuid, "HIRED", null);
        postSendSignatureExpectingLegacySignerConflict();
    }

    /**
     * Every pass-the-gate scenario ends in the PRE-EXISTING fail-fast 409
     * ("no signers configured on dossier" — this fixture's dossier has no
     * signer config), NOT the gate's {@code TEAM_NOT_ASSIGNED} body. That
     * distinction is the proof the gate let the request through; NextSign
     * is still never reached because the legacy validation fires first.
     */
    private void postSendSignatureExpectingLegacySignerConflict() {
        String body = given()
                .contentType("application/json")
                .header("X-Requested-By", actorUuid)
                .body("{}")
                .when()
                .post("/recruitment/candidates/{uuid}/dossier/send-signature", candidateUuid)
                .then()
                .statusCode(409)
                .extract().body().asString();
        org.junit.jupiter.api.Assertions.assertFalse(body.contains("TEAM_NOT_ASSIGNED"),
                "the request must get PAST the team gate and fail on the legacy signers check");
        Mockito.verifyNoInteractions(nextsignSigningService);
    }

    private void insertPosition(String uuid, String track) {
        em.createNativeQuery("""
                        INSERT INTO recruitment_positions
                            (uuid, title, hiring_track, stage_set,
                             demand_rag, status, opened_at, created_at, updated_at, created_by)
                        VALUES (:uuid, 'Gate Fixture', :track,
                                '["SCREENING","INTERVIEW_1","INTERVIEW_2","OFFER","HIRED"]',
                                'GREEN', 'OPEN', NOW(3), NOW(), NOW(), 'test')
                        """)
                .setParameter("uuid", uuid)
                .setParameter("track", track)
                .executeUpdate();
    }

    private void insertApplication(String positionUuid, String stage, String teamUuid) {
        QuarkusTransaction.requiringNew().run(() ->
                em.createNativeQuery("""
                                INSERT INTO recruitment_applications
                                    (uuid, candidate_uuid, position_uuid, stage, assigned_team_uuid,
                                     stage_entered_at, created_at, updated_at, created_by)
                                VALUES (:uuid, :candidate, :position, :stage, :team,
                                        NOW(3), NOW(), NOW(), 'test')
                                """)
                        .setParameter("uuid", UUID.randomUUID().toString())
                        .setParameter("candidate", candidateUuid)
                        .setParameter("position", positionUuid)
                        .setParameter("stage", stage)
                        .setParameter("team", teamUuid)
                        .executeUpdate());
    }
}
