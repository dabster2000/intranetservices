package dk.trustworks.intranet.recruitmentservice.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.apis.openai.OpenAIService;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEvent;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventPiiAssertions;
import dk.trustworks.intranet.recruitmentservice.resources.P8ProfileFixtures;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventPiiAssertions.PII_SENTINEL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P9 §8.7: the referral triage reactor against the real chassis (raw
 * referral + REFERRAL_SUBMITTED fixtures, deterministic catch-up sweeps)
 * with a mocked {@link OpenAIService}: happy path (no subjects, NORMAL
 * visibility, payload/pii split), validation drops (inactive practice,
 * departed teamlead, bad enum — all dropped ⇒ no event), flag-off silent
 * advance, missing referral, and idempotency.
 */
@QuarkusTest
class AiReferralTriageReactorTest {

    private static final String TRIAGE_FLAG = "recruitment.ai.referral-triage.enabled";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    EntityManager em;

    @Inject
    AiReferralTriageReactor reactor;

    @InjectMock
    OpenAIService openAIService;

    private String practiceUuid;
    private String teamUuid;
    private String teamleadUser;
    private String referrerUser;
    private String referralUuid;

    private String previousFlag;

    @BeforeEach
    void seed() {
        practiceUuid = UUID.randomUUID().toString();
        teamUuid = UUID.randomUUID().toString();
        teamleadUser = UUID.randomUUID().toString();
        referrerUser = UUID.randomUUID().toString();
        referralUuid = UUID.randomUUID().toString();

        QuarkusTransaction.requiringNew().run(() -> {
            P8ProfileFixtures.insertUser(em, teamleadUser, "Tim", "Teamlead");
            P8ProfileFixtures.insertUser(em, referrerUser, "Rita", "Referrer");
            P8ProfileFixtures.insertTeamLeader(em, teamleadUser, teamUuid);
            P8ProfileFixtures.insertPractice(em, practiceUuid);
            insertReferral(referralUuid, "SUBMITTED");
            previousFlag = P8ProfileFixtures.setFlag(em, TRIAGE_FLAG, "false");
        });
        reactor.catchUp(); // drain backlog with the flag off

        when(openAIService.getDefaultModel()).thenReturn("test-model");
    }

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("DELETE FROM recruitment_events WHERE payload LIKE :ref")
                    .setParameter("ref", "%" + referralUuid + "%").executeUpdate();
            em.createNativeQuery("DELETE FROM recruitment_referrals WHERE uuid = :uuid")
                    .setParameter("uuid", referralUuid).executeUpdate();
            P8ProfileFixtures.cleanupRecruitmentRows(em, List.of(), List.of(),
                    List.of(teamleadUser, referrerUser), practiceUuid);
            P8ProfileFixtures.restoreFlag(em, TRIAGE_FLAG, previousFlag);
        });
        reactor.catchUp();
    }

    // ---- fixtures ----------------------------------------------------------------

    private void insertReferral(String uuid, String status) {
        em.createNativeQuery("""
                        INSERT INTO recruitment_referrals
                            (uuid, referrer_uuid, referrer_relation, candidate_name, linkedin_url,
                             why_text, status, submitted_at, version, created_at, updated_at, created_by)
                        VALUES (:uuid, :referrer, 'FORMER_COLLEAGUE', :name, :linkedin,
                                :why, :status, UTC_TIMESTAMP(3), 0, NOW(), NOW(), 'test')
                        """)
                .setParameter("uuid", uuid)
                .setParameter("referrer", referrerUser)
                .setParameter("name", PII_SENTINEL + " Karla Kandidat")
                .setParameter("linkedin", "https://www.linkedin.com/in/karla")
                .setParameter("why", PII_SENTINEL + " Dygtig arkitekt, kender hende fra Acme")
                .setParameter("status", status)
                .executeUpdate();
    }

    private long insertSubmittedEvent(String referral) {
        return QuarkusTransaction.requiringNew().call(() ->
                P8ProfileFixtures.insertEvent(em, "REFERRAL_SUBMITTED", null, null, null,
                        "USER", referrerUser, "NORMAL",
                        "{\"referral_uuid\":\"" + referral + "\",\"relation\":\"FORMER_COLLEAGUE\","
                                + "\"has_linkedin\":true,\"has_email\":false,\"origin\":\"web\"}",
                        "{\"candidate_name\":\"" + PII_SENTINEL + " Karla Kandidat\","
                                + "\"why_text\":\"" + PII_SENTINEL + " Dygtig arkitekt\"}"));
    }

    private void flag(boolean on) {
        QuarkusTransaction.requiringNew().run(() ->
                P8ProfileFixtures.setFlag(em, TRIAGE_FLAG, String.valueOf(on)));
    }

    private void stubOpenAi(String json) {
        when(openAIService.askQuestionWithSchema(anyString(), anyString(), any(), any(),
                any(), any(), anyInt(), anyBoolean())).thenReturn(json);
    }

    private List<RecruitmentEvent> referralAiEvents(String referral) {
        return QuarkusTransaction.requiringNew().call(() -> {
            em.clear();
            return em.createQuery("SELECT e FROM RecruitmentEvent e WHERE e.candidateUuid IS NULL "
                            + "AND e.eventType = :type AND e.payload LIKE :ref ORDER BY e.seq",
                            RecruitmentEvent.class)
                    .setParameter("type", dk.trustworks.intranet.recruitmentservice.events
                            .RecruitmentEventType.AI_SUGGESTIONS_GENERATED)
                    .setParameter("ref", "%" + referral + "%")
                    .getResultList();
        });
    }

    private String validTriple() {
        return """
                {"practice":{"uuid":"%s","rationale":"Arkitekturprofil passer til praksissen"},
                 "experienceLevel":{"value":"SENIOR","rationale":"Otte aars erfaring naevnt"},
                 "teamlead":{"uuid":"%s","rationale":"Leder holdet med samme profil"}}
                """.formatted(practiceUuid, teamleadUser);
    }

    // ---- tests --------------------------------------------------------------------

    @Test
    void happyPath_appendsSubjectlessNormalEventWithValidatedTriple() throws Exception {
        flag(true);
        stubOpenAi(validTriple());
        long seq = insertSubmittedEvent(referralUuid);

        reactor.catchUp();

        List<RecruitmentEvent> events = referralAiEvents(referralUuid);
        assertEquals(1, events.size());
        RecruitmentEvent event = events.get(0);
        RecruitmentEventPiiAssertions.assertNoPiiInPayload(event);
        assertNull(event.getCandidateUuid(), "referral events carry no subjects");
        assertNull(event.getApplicationUuid());
        assertNull(event.getPositionUuid());
        assertEquals("NORMAL", event.getVisibility().name());
        assertEquals("SYSTEM", event.getActorType().name());

        JsonNode payload = MAPPER.readTree(event.getPayload());
        assertEquals(referralUuid, payload.path("referral_uuid").asText());
        assertEquals("referral-triage-v1", payload.path("prompt_version").asText());
        assertEquals("test-model", payload.path("model").asText());
        assertEquals(seq, payload.path("source_event_seq").asLong());
        assertEquals(3, payload.path("fields").size());

        JsonNode suggestions = MAPPER.readTree(event.getPii()).path("suggestions");
        assertEquals(3, suggestions.size());
        String generationId = payload.path("generation_id").asText();
        for (JsonNode suggestion : suggestions) {
            assertEquals(generationId + ":" + suggestion.path("field").asText(),
                    suggestion.path("id").asText());
            assertTrue(suggestion.path("rationale").asText().length() > 0);
        }
    }

    @Test
    void invalidPicks_areDropped_allDroppedMeansNoEvent() {
        flag(true);
        // Inactive/unknown practice uuid, departed (unknown) teamlead, bad enum.
        stubOpenAi("""
                {"practice":{"uuid":"%s","rationale":"x"},
                 "experienceLevel":{"value":"GALACTIC","rationale":"x"},
                 "teamlead":{"uuid":"%s","rationale":"x"}}
                """.formatted(UUID.randomUUID(), UUID.randomUUID()));
        long seq = insertSubmittedEvent(referralUuid);

        reactor.catchUp();

        assertEquals(0, referralAiEvents(referralUuid).size(),
                "all-invalid model output must append nothing");
        assertTrue(reactor.watermark() >= seq, "a validated-empty result is not a failure");
    }

    @Test
    void flagOff_silentAdvance_noCallNoEvent() {
        flag(false);
        long seq = insertSubmittedEvent(referralUuid);

        reactor.catchUp();

        verify(openAIService, never()).askQuestionWithSchema(anyString(), anyString(), any(), any(),
                any(), any(), anyInt(), anyBoolean());
        assertEquals(0, referralAiEvents(referralUuid).size());
        assertTrue(reactor.watermark() >= seq);
    }

    @Test
    void missingReferralRow_skipsSilently() {
        flag(true);
        stubOpenAi(validTriple());
        String ghost = UUID.randomUUID().toString();
        long seq = insertSubmittedEvent(ghost);

        reactor.catchUp();

        verify(openAIService, never()).askQuestionWithSchema(anyString(), anyString(), any(), any(),
                any(), any(), anyInt(), anyBoolean());
        assertEquals(0, referralAiEvents(ghost).size());
        assertTrue(reactor.watermark() >= seq);
    }

    @Test
    void alreadyTriagedReferral_skipsSilently() {
        flag(true);
        stubOpenAi(validTriple());
        String triaged = UUID.randomUUID().toString();
        QuarkusTransaction.requiringNew().run(() -> insertReferral(triaged, "CLOSED"));
        try {
            insertSubmittedEvent(triaged);
            reactor.catchUp();

            verify(openAIService, never()).askQuestionWithSchema(anyString(), anyString(), any(), any(),
                    any(), any(), anyInt(), anyBoolean());
            assertEquals(0, referralAiEvents(triaged).size());
        } finally {
            QuarkusTransaction.requiringNew().run(() -> {
                em.createNativeQuery("DELETE FROM recruitment_events WHERE payload LIKE :ref")
                        .setParameter("ref", "%" + triaged + "%").executeUpdate();
                em.createNativeQuery("DELETE FROM recruitment_referrals WHERE uuid = :uuid")
                        .setParameter("uuid", triaged).executeUpdate();
            });
        }
    }

    @Test
    void duplicateDelivery_isIdempotent() {
        flag(true);
        stubOpenAi(validTriple());
        long seq = insertSubmittedEvent(referralUuid);

        reactor.catchUp();
        reactor.catchUp();
        reactor.deliverLive(seq);

        verify(openAIService, times(1)).askQuestionWithSchema(anyString(), anyString(), any(), any(),
                any(), any(), anyInt(), anyBoolean());
        assertEquals(1, referralAiEvents(referralUuid).size());
    }
}
