package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.recruitmentservice.dto.CandidateListResponse;
import dk.trustworks.intranet.recruitmentservice.dto.CandidateRequest;
import dk.trustworks.intranet.recruitmentservice.dto.CandidateResponse;
import dk.trustworks.intranet.recruitmentservice.dto.CandidateSummary;
import dk.trustworks.intranet.recruitmentservice.dto.NoteRequest;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEvent;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventPiiAssertions;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventType;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateEducationLevel;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateExperienceLevel;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateLawfulBasis;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidatePoolStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateSecurityClearance;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateSource;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateStatus;
import dk.trustworks.intranet.recruitmentservice.model.exception.BusinessRuleViolation;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventPiiAssertions.PII_SENTINEL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P3 DoD at the service layer: mandatory source on the ATS create path,
 * qualification fields persist and filter, GDPR Art. 14 bookkeeping on
 * sourced/referred creation, pool/unpool lifecycle, exact event emission
 * with {@code assertNoPiiInPayload} green on every appended event
 * (PII_SENTINEL embedded in every personal fixture field), and timeline
 * accumulation verified via direct {@code recruitment_events} query.
 */
@QuarkusTest
class CandidateServiceAtsIntegrationTest {

    @Inject
    CandidateService candidateService;

    @Inject
    EntityManager em;

    private final UUID actor = UUID.randomUUID();
    private final List<String> createdCandidates = new ArrayList<>();

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            if (createdCandidates.isEmpty()) {
                return;
            }
            em.createNativeQuery("DELETE FROM recruitment_events WHERE candidate_uuid IN :uuids")
                    .setParameter("uuids", createdCandidates).executeUpdate();
            em.createNativeQuery("DELETE FROM candidate_dossiers WHERE candidate_uuid IN :uuids")
                    .setParameter("uuids", createdCandidates).executeUpdate();
            em.createNativeQuery("DELETE FROM recruitment_candidates WHERE uuid IN :uuids")
                    .setParameter("uuids", createdCandidates).executeUpdate();
        });
        createdCandidates.clear();
    }

    // ---- Create: ATS path ------------------------------------------------------

    @Test
    void atsCreate_withoutSource_isRejected() {
        WebApplicationException e = assertThrows(WebApplicationException.class,
                () -> candidateService.createCandidate(builder().build(), actor));
        assertEquals(400, e.getResponse().getStatus());
    }

    @Test
    void atsCreate_persistsQualifications_setsGdprBookkeeping_andEmitsCreatedEvent() {
        CandidateResponse created = create(builder()
                .source(CandidateSource.REFERRAL)
                .email(uniqueEmail())
                .phone("+45 12 34 56 78")
                .linkedinUrl("https://www.linkedin.com/in/" + PII_SENTINEL.toLowerCase() + "-jane/")
                .externalReferrerName(PII_SENTINEL + " External Referrer")
                .sourceDetail(Map.of("referenceName", PII_SENTINEL + " Reference"))
                .educationLevel(CandidateEducationLevel.MASTER)
                .experienceLevel(CandidateExperienceLevel.SENIOR)
                .specializations(List.of("Projektleder"))
                .securityClearance(CandidateSecurityClearance.CLEARED)
                .securityRelevant(true)
                .tags(List.of("cleared", "senior-pm"))
                .build());

        assertEquals(CandidateStatus.ACTIVE, created.status());
        assertEquals(CandidateSource.REFERRAL, created.source());
        assertEquals(CandidateEducationLevel.MASTER, created.educationLevel());
        assertEquals(CandidateExperienceLevel.SENIOR, created.experienceLevel());
        assertEquals(List.of("Projektleder"), created.specializations());
        assertEquals(CandidateSecurityClearance.CLEARED, created.securityClearance());
        assertEquals(Boolean.TRUE, created.securityRelevant());
        assertEquals(List.of("cleared", "senior-pm"), created.tags());
        assertEquals(CandidateLawfulBasis.LEGITIMATE_INTEREST, created.lawfulBasis());

        // Referral = data not collected from the candidate → Art. 14 notice
        // required within 30 days (data only; the clock reactor is P19).
        assertEquals(Boolean.TRUE, created.art14Required());
        assertNotNull(created.art14Deadline());
        LocalDateTime expected = LocalDateTime.now(ZoneOffset.UTC).plusDays(30);
        assertTrue(Math.abs(java.time.Duration.between(expected, created.art14Deadline()).toMinutes()) < 5,
                "art14_deadline should be created_at + 30d");

        List<RecruitmentEvent> events = eventsFor(created.uuid());
        assertEquals(List.of(RecruitmentEventType.CANDIDATE_CREATED),
                events.stream().map(RecruitmentEvent::getEventType).toList());
        events.forEach(RecruitmentEventPiiAssertions::assertNoPiiInPayload);
        // Personal data must be in the pii section — spot-check the sentinel.
        assertTrue(events.get(0).getPii().contains(PII_SENTINEL),
                "personal fixture data should land in the pii section");
        assertTrue(events.get(0).getPayload().contains("\"source\":\"REFERRAL\""),
                "structural facts should land in the payload section");
    }

    @Test
    void atsCreate_directSource_setsNoArt14Clock() {
        CandidateResponse created = create(builder()
                .source(CandidateSource.CONFERENCE)
                .sourceDetail(Map.of("eventName", "Driving IT 2026"))
                .build());
        assertNull(created.art14Required(), "conference contact = data from the candidate (Art. 13)");
        assertNull(created.art14Deadline());
    }

    @Test
    void partnerReferral_requiresSponsoringPartner() {
        WebApplicationException e = assertThrows(WebApplicationException.class,
                () -> candidateService.createCandidate(builder()
                        .source(CandidateSource.PARTNER_REFERRAL)
                        .build(), actor));
        assertEquals(400, e.getResponse().getStatus());

        CandidateResponse created = create(builder()
                .source(CandidateSource.PARTNER_REFERRAL)
                .sponsoringPartnerUuid(UUID.randomUUID().toString())
                .build());
        assertNotNull(created.sponsoringPartnerUuid());
        assertEquals(Boolean.TRUE, created.art14Required(), "partner referral is an indirect source");
    }

    // ---- Create: dossier path (regression guard) --------------------------------

    @Test
    void dossierCreate_requiresEmailAndTargetCompany_andStillOpensTheDossier() {
        WebApplicationException noEmail = assertThrows(WebApplicationException.class,
                () -> candidateService.createCandidate(builder()
                        .templateUuid(UUID.randomUUID().toString())
                        .targetCompanyUuid(UUID.randomUUID().toString())
                        .build(), actor));
        assertEquals(400, noEmail.getResponse().getStatus());

        WebApplicationException noCompany = assertThrows(WebApplicationException.class,
                () -> candidateService.createCandidate(builder()
                        .templateUuid(UUID.randomUUID().toString())
                        .email(uniqueEmail())
                        .build(), actor));
        assertEquals(400, noCompany.getResponse().getStatus());

        CandidateResponse created = create(builder()
                .templateUuid(UUID.randomUUID().toString())
                .email(uniqueEmail())
                .targetCompanyUuid(UUID.randomUUID().toString())
                .build());
        Number dossiers = (Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM candidate_dossiers WHERE candidate_uuid = :uuid")
                .setParameter("uuid", created.uuid())
                .getSingleResult();
        assertEquals(1, dossiers.intValue(), "dossier path still opens the initial dossier");

        List<RecruitmentEvent> events = eventsFor(created.uuid());
        assertEquals(List.of(RecruitmentEventType.CANDIDATE_CREATED),
                events.stream().map(RecruitmentEvent::getEventType).toList());
        assertTrue(events.get(0).getPayload().contains("\"dossier_opened\":true"));
        events.forEach(RecruitmentEventPiiAssertions::assertNoPiiInPayload);
    }

    // ---- Update ------------------------------------------------------------------

    @Test
    void update_recordsPersonalChangesInPii_andStructuralChangesInPayload() {
        CandidateResponse created = create(builder().source(CandidateSource.OTHER).build());

        String newEmail = uniqueEmail();
        candidateService.update(UUID.fromString(created.uuid()), builder()
                .email(newEmail)
                .experienceLevel(CandidateExperienceLevel.PRINCIPAL)
                .build(), actor);

        List<RecruitmentEvent> events = eventsFor(created.uuid());
        assertEquals(RecruitmentEventType.CANDIDATE_UPDATED, events.get(events.size() - 1).getEventType());
        RecruitmentEvent updated = events.get(events.size() - 1);
        events.forEach(RecruitmentEventPiiAssertions::assertNoPiiInPayload);
        assertTrue(updated.getPayload().contains("changed_fields"));
        assertTrue(updated.getPayload().contains("experience_level"),
                "structural before/after belongs in payload");
        assertTrue(updated.getPii().contains(newEmail),
                "personal before/after belongs in pii");
        assertFalse(updated.getPayload().contains(newEmail),
                "the email value must never appear in payload");
    }

    @Test
    void noOpUpdate_appendsNoEvent() {
        CandidateResponse created = create(builder().source(CandidateSource.OTHER).build());
        int before = eventsFor(created.uuid()).size();

        candidateService.update(UUID.fromString(created.uuid()), builder().build(), actor);

        assertEquals(before, eventsFor(created.uuid()).size(), "no-op update → no event");
    }

    // ---- Pool lifecycle ------------------------------------------------------------

    @Test
    void poolAndUnpool_transitionStatus_andEmitEvents() {
        CandidateResponse created = create(builder().source(CandidateSource.LINKEDIN_SEARCH).build());

        CandidateResponse pooled = candidateService.pool(UUID.fromString(created.uuid()), null, actor);
        assertEquals(CandidateStatus.POOLED, pooled.status());
        assertEquals(CandidatePoolStatus.PROSPECT, pooled.poolStatus(), "bucket defaults to PROSPECT");

        CandidateResponse rebucketed = candidateService.pool(
                UUID.fromString(created.uuid()), CandidatePoolStatus.INTERESTED, actor);
        assertEquals(CandidatePoolStatus.INTERESTED, rebucketed.poolStatus());

        CandidateResponse unpooled = candidateService.unpool(UUID.fromString(created.uuid()), actor);
        assertEquals(CandidateStatus.ACTIVE, unpooled.status());
        assertNull(unpooled.poolStatus(), "unpool clears the bucket");

        assertThrows(BusinessRuleViolation.class,
                () -> candidateService.unpool(UUID.fromString(created.uuid()), actor),
                "unpooling an ACTIVE candidate is a state conflict");

        List<RecruitmentEventType> types = eventsFor(created.uuid()).stream()
                .map(RecruitmentEvent::getEventType).toList();
        assertEquals(List.of(
                RecruitmentEventType.CANDIDATE_CREATED,
                RecruitmentEventType.CANDIDATE_POOLED,
                RecruitmentEventType.CANDIDATE_POOLED,
                RecruitmentEventType.CANDIDATE_UNPOOLED), types);
        eventsFor(created.uuid()).forEach(RecruitmentEventPiiAssertions::assertNoPiiInPayload);
    }

    // ---- Tags ------------------------------------------------------------------------

    @Test
    void updateTags_normalizes_andSkipsNoOpReplacement() {
        CandidateResponse created = create(builder().source(CandidateSource.OTHER).build());

        CandidateResponse tagged = candidateService.updateTags(
                UUID.fromString(created.uuid()),
                List.of(" cleared ", "cleared", "senior-pm", "  "),
                actor);
        assertEquals(List.of("cleared", "senior-pm"), tagged.tags(),
                "tags are trimmed, deduped, blanks dropped");
        int after = eventsFor(created.uuid()).size();

        candidateService.updateTags(UUID.fromString(created.uuid()),
                List.of("cleared", "senior-pm"), actor);
        assertEquals(after, eventsFor(created.uuid()).size(), "no-op tag replacement → no event");
    }

    // ---- Notes ---------------------------------------------------------------------

    @Test
    void addNote_putsTextInPiiOnly_andPrivateFlagInPayload() {
        CandidateResponse created = create(builder().source(CandidateSource.OTHER).build());

        candidateService.addNote(UUID.fromString(created.uuid()),
                new NoteRequest(PII_SENTINEL + " expects 75.000 kr/md",
                        true, NoteRequest.FIELD_SALARY_EXPECTATION),
                actor);

        List<RecruitmentEvent> events = eventsFor(created.uuid());
        RecruitmentEvent note = events.get(events.size() - 1);
        assertEquals(RecruitmentEventType.NOTE_ADDED, note.getEventType());
        RecruitmentEventPiiAssertions.assertNoPiiInPayload(note);
        assertTrue(note.getPayload().contains("\"private\":true"));
        assertTrue(note.getPayload().contains("SALARY_EXPECTATION"));
        assertTrue(note.getPii().contains(PII_SENTINEL), "note text lives in pii");
    }

    @Test
    void addNote_rejectsUnknownStructuredField() {
        CandidateResponse created = create(builder().source(CandidateSource.OTHER).build());
        WebApplicationException e = assertThrows(WebApplicationException.class,
                () -> candidateService.addNote(UUID.fromString(created.uuid()),
                        new NoteRequest("text", false, "SOME_OTHER_FIELD"), actor));
        assertEquals(400, e.getResponse().getStatus());
    }

    // ---- List filters (qualification fields persist AND filter — DoD) -----------------

    @Test
    void listFilters_qualificationFieldsAndTags() {
        String marker = UUID.randomUUID().toString().substring(0, 8);
        CandidateResponse pm = create(builder()
                .source(CandidateSource.OTHER)
                .educationLevel(CandidateEducationLevel.MASTER)
                .experienceLevel(CandidateExperienceLevel.SENIOR)
                .specializations(List.of("Spec-" + marker))
                .securityClearance(CandidateSecurityClearance.CLEARED)
                .tags(List.of("tag-" + marker))
                .build());
        CandidateResponse dev = create(builder()
                .source(CandidateSource.OTHER)
                .educationLevel(CandidateEducationLevel.BACHELOR)
                .experienceLevel(CandidateExperienceLevel.JUNIOR)
                .securityClearance(CandidateSecurityClearance.NONE)
                .build());

        assertOnlyContains(candidateService.list(null, null, "tag-" + marker,
                null, null, null, null, null, null, 0, 100, null), pm.uuid(), dev.uuid());
        assertOnlyContains(candidateService.list(null, null, null,
                null, null, "Spec-" + marker, null, null, null, 0, 100, null), pm.uuid(), dev.uuid());

        CandidateListResponse cleared = candidateService.list(null, null, null,
                null, null, null, "CLEARED", null, null, 0, 1000, null);
        assertTrue(uuidsOf(cleared).contains(pm.uuid()));
        assertFalse(uuidsOf(cleared).contains(dev.uuid()));

        CandidateListResponse seniors = candidateService.list(null, null, null,
                null, "SENIOR", null, null, null, null, 0, 1000, null);
        assertTrue(uuidsOf(seniors).contains(pm.uuid()));
        assertFalse(uuidsOf(seniors).contains(dev.uuid()));

        CandidateListResponse masters = candidateService.list(null, null, null,
                "MASTER", null, null, null, null, null, 0, 1000, null);
        assertTrue(uuidsOf(masters).contains(pm.uuid()));
        assertFalse(uuidsOf(masters).contains(dev.uuid()));

        // The POOLED status filter finds pool candidates.
        candidateService.pool(UUID.fromString(dev.uuid()), CandidatePoolStatus.SILVER_MEDALIST, actor);
        CandidateListResponse pooled = candidateService.list("POOLED", null, null,
                null, null, null, null, null, null, 0, 1000, null);
        assertTrue(uuidsOf(pooled).contains(dev.uuid()));
        assertFalse(uuidsOf(pooled).contains(pm.uuid()));

        WebApplicationException badFilter = assertThrows(WebApplicationException.class,
                () -> candidateService.list(null, null, null, "NOT_A_LEVEL",
                        null, null, null, null, null, 0, 10, null));
        assertEquals(400, badFilter.getResponse().getStatus());
    }

    // ---- Timeline (DoD: events accumulate, verified via direct query) -----------------

    @Test
    void timeline_eventsAccumulateInSeqOrder() {
        CandidateResponse created = create(builder().source(CandidateSource.REFERRAL).build());
        candidateService.update(UUID.fromString(created.uuid()),
                builder().experienceLevel(CandidateExperienceLevel.MID).build(), actor);
        candidateService.pool(UUID.fromString(created.uuid()), null, actor);
        candidateService.addNote(UUID.fromString(created.uuid()),
                new NoteRequest(PII_SENTINEL + " strong profile", false, null), actor);

        List<RecruitmentEvent> events = eventsFor(created.uuid());
        assertEquals(List.of(
                        RecruitmentEventType.CANDIDATE_CREATED,
                        RecruitmentEventType.CANDIDATE_UPDATED,
                        RecruitmentEventType.CANDIDATE_POOLED,
                        RecruitmentEventType.NOTE_ADDED),
                events.stream().map(RecruitmentEvent::getEventType).toList());
        for (int i = 1; i < events.size(); i++) {
            assertTrue(events.get(i).getSeq() > events.get(i - 1).getSeq(),
                    "seq strictly increases along the timeline");
        }
        events.forEach(RecruitmentEventPiiAssertions::assertNoPiiInPayload);
    }

    // ---- Helpers --------------------------------------------------------------------

    private CandidateResponse create(CandidateRequest request) {
        CandidateResponse created = candidateService.createCandidate(request, actor);
        createdCandidates.add(created.uuid());
        return created;
    }

    private List<RecruitmentEvent> eventsFor(String candidateUuid) {
        return RecruitmentEvent.list("candidateUuid = ?1 order by seq", candidateUuid);
    }

    private static List<String> uuidsOf(CandidateListResponse response) {
        return response.data().stream().map(CandidateSummary::uuid).toList();
    }

    private static void assertOnlyContains(CandidateListResponse response,
                                           String expectedUuid, String excludedUuid) {
        List<String> uuids = uuidsOf(response);
        assertTrue(uuids.contains(expectedUuid), "filter should match the qualifying candidate");
        assertFalse(uuids.contains(excludedUuid), "filter should exclude the other candidate");
    }

    private static String uniqueEmail() {
        return "p3-" + UUID.randomUUID().toString().substring(0, 12) + "@example.com";
    }

    /** Fluent builder over the 22-field request record — tests set only what they assert. */
    private static RequestBuilder builder() {
        return new RequestBuilder();
    }

    private static final class RequestBuilder {
        private String email;
        private String linkedinUrl;
        private String targetCompanyUuid;
        private String templateUuid;
        private String phone;
        private CandidateSource source;
        private Map<String, Object> sourceDetail;
        private String sponsoringPartnerUuid;
        private String externalReferrerName;
        private List<String> tags;
        private CandidateEducationLevel educationLevel;
        private CandidateExperienceLevel experienceLevel;
        private List<String> specializations;
        private CandidateSecurityClearance securityClearance;
        private Boolean securityRelevant;

        RequestBuilder email(String v) { this.email = v; return this; }
        RequestBuilder linkedinUrl(String v) { this.linkedinUrl = v; return this; }
        RequestBuilder targetCompanyUuid(String v) { this.targetCompanyUuid = v; return this; }
        RequestBuilder templateUuid(String v) { this.templateUuid = v; return this; }
        RequestBuilder phone(String v) { this.phone = v; return this; }
        RequestBuilder source(CandidateSource v) { this.source = v; return this; }
        RequestBuilder sourceDetail(Map<String, Object> v) { this.sourceDetail = v; return this; }
        RequestBuilder sponsoringPartnerUuid(String v) { this.sponsoringPartnerUuid = v; return this; }
        RequestBuilder externalReferrerName(String v) { this.externalReferrerName = v; return this; }
        RequestBuilder tags(List<String> v) { this.tags = v; return this; }
        RequestBuilder educationLevel(CandidateEducationLevel v) { this.educationLevel = v; return this; }
        RequestBuilder experienceLevel(CandidateExperienceLevel v) { this.experienceLevel = v; return this; }
        RequestBuilder specializations(List<String> v) { this.specializations = v; return this; }
        RequestBuilder securityClearance(CandidateSecurityClearance v) { this.securityClearance = v; return this; }
        RequestBuilder securityRelevant(Boolean v) { this.securityRelevant = v; return this; }

        CandidateRequest build() {
            return new CandidateRequest(
                    PII_SENTINEL + "-Jane",
                    PII_SENTINEL + "-Doe",
                    email,
                    phone,
                    linkedinUrl,
                    targetCompanyUuid,
                    null,
                    null,
                    templateUuid,
                    source,
                    sourceDetail,
                    null,
                    externalReferrerName,
                    sponsoringPartnerUuid,
                    null,
                    tags,
                    educationLevel,
                    null,
                    experienceLevel,
                    specializations,
                    securityClearance,
                    securityRelevant);
        }
    }
}
