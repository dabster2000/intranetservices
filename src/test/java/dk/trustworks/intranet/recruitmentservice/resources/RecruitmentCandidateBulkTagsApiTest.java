package dk.trustworks.intranet.recruitmentservice.resources;

import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEvent;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventPiiAssertions;
import dk.trustworks.intranet.recruitmentservice.events.RecruitmentEventType;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static dk.trustworks.intranet.recruitmentservice.resources.P8ProfileFixtures.PIPELINE_FLAG;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P8 DoD (bulk tags): {@code POST /recruitment/candidates/tags/bulk}
 * union-adds through the existing tag path — one {@code CANDIDATE_UPDATED}
 * per actually-changed candidate (no-ops emit nothing, and the payload
 * stays PII-free), existing tags are never removed, the 200-candidate cap
 * answers 400, non-recruiter actors answer 403, and invisible
 * (partner-track-only) targets answer 404 for the whole call.
 */
@QuarkusTest
class RecruitmentCandidateBulkTagsApiTest {

    private static final String BULK = "/recruitment/candidates/tags/bulk";

    @Inject
    EntityManager em;

    private String hrUser;
    private String plainUser;
    private String partnerPosition;

    private String taggedCandidate;    // tags ["existing"] — union grows
    private String untaggedCandidate;  // tags NULL — union starts the list
    private String noopCandidate;      // already carries the added tag — no-op
    private String partnerOnlyCandidate;

    private String previousFlag;

    @BeforeEach
    void seed() {
        hrUser = UUID.randomUUID().toString();
        plainUser = UUID.randomUUID().toString();
        partnerPosition = UUID.randomUUID().toString();
        taggedCandidate = UUID.randomUUID().toString();
        untaggedCandidate = UUID.randomUUID().toString();
        noopCandidate = UUID.randomUUID().toString();
        partnerOnlyCandidate = UUID.randomUUID().toString();

        QuarkusTransaction.requiringNew().run(() -> {
            P8ProfileFixtures.insertUser(em, hrUser, "Rina", "Recruiter");
            P8ProfileFixtures.insertUser(em, plainUser, "Palle", "Plain");
            P8ProfileFixtures.insertRole(em, hrUser, "HR");

            P8ProfileFixtures.insertPosition(em, partnerPosition, "Partner hire",
                    "PARTNER", null, null, null);

            P8ProfileFixtures.insertCandidate(em, taggedCandidate,
                    "PII_SENTINEL Tine", "PII_SENTINEL Tagget", "ACTIVE", null,
                    "[\"existing\"]", hrUser);
            P8ProfileFixtures.insertCandidate(em, untaggedCandidate,
                    "PII_SENTINEL Uma", "PII_SENTINEL Utagget", "ACTIVE", null, null, hrUser);
            P8ProfileFixtures.insertCandidate(em, noopCandidate,
                    "PII_SENTINEL Nils", "PII_SENTINEL Noop", "ACTIVE", null,
                    "[\"p8bulk\"]", hrUser);
            P8ProfileFixtures.insertCandidate(em, partnerOnlyCandidate,
                    "PII_SENTINEL Gro", "PII_SENTINEL Gram", "ACTIVE", null, null, hrUser);
            P8ProfileFixtures.insertOpenApplication(em, UUID.randomUUID().toString(),
                    partnerOnlyCandidate, partnerPosition, "SCREENING");

            previousFlag = P8ProfileFixtures.setFlag(em, PIPELINE_FLAG, "true");
        });
    }

    @AfterEach
    void cleanup() {
        QuarkusTransaction.requiringNew().run(() -> {
            P8ProfileFixtures.cleanupRecruitmentRows(em,
                    List.of(taggedCandidate, untaggedCandidate, noopCandidate,
                            partnerOnlyCandidate),
                    List.of(partnerPosition),
                    List.of(hrUser, plainUser),
                    null);
            P8ProfileFixtures.restoreFlag(em, PIPELINE_FLAG, previousFlag);
        });
    }

    // ---- Union-add + event-only-on-change ------------------------------------------

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void bulkAddTags_unionAdds_countsAndEmitsOnlyForChangedCandidates() {
        given().header("X-Requested-By", hrUser)
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "candidateUuids",
                        List.of(taggedCandidate, untaggedCandidate, noopCandidate),
                        "addTags", List.of("p8bulk")))
                .when().post(BULK)
                .then().statusCode(200)
                .body("updated", Matchers.equalTo(2));

        QuarkusTransaction.requiringNew().run(() -> {
            // Union semantics: existing tags kept, addition appended.
            assertEquals(List.of("existing", "p8bulk"), tagsOf(taggedCandidate));
            assertEquals(List.of("p8bulk"), tagsOf(untaggedCandidate));
            assertEquals(List.of("p8bulk"), tagsOf(noopCandidate));

            // Events only for the two changed candidates — the no-op emits
            // nothing; payloads stay PII-free (sentinel names never leak).
            List<RecruitmentEvent> tagged = updateEventsFor(taggedCandidate);
            List<RecruitmentEvent> untagged = updateEventsFor(untaggedCandidate);
            assertEquals(1, tagged.size(), "one CANDIDATE_UPDATED per changed candidate");
            assertEquals(1, untagged.size());
            assertEquals(0, updateEventsFor(noopCandidate).size(),
                    "a no-op replacement must not emit an event");
            tagged.forEach(RecruitmentEventPiiAssertions::assertNoPiiInPayload);
            untagged.forEach(RecruitmentEventPiiAssertions::assertNoPiiInPayload);
            assertTrue(tagged.get(0).getPayload().contains("\"tags\""),
                    "the before/after tag change is structural payload");
        });
    }

    // ---- Validation -----------------------------------------------------------------

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void bulkAddTags_capsAt200Candidates_with400Above() {
        List<String> tooMany = new ArrayList<>();
        for (int i = 0; i < 201; i++) {
            tooMany.add(UUID.randomUUID().toString());
        }
        given().header("X-Requested-By", hrUser)
                .contentType(ContentType.JSON)
                .body(Map.of("candidateUuids", tooMany, "addTags", List.of("p8bulk")))
                .when().post(BULK)
                .then().statusCode(400);
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void bulkAddTags_explicitInputValidation_validIsInert() {
        // Empty candidate list.
        given().header("X-Requested-By", hrUser)
                .contentType(ContentType.JSON)
                .body(Map.of("candidateUuids", List.of(), "addTags", List.of("p8bulk")))
                .when().post(BULK)
                .then().statusCode(400);
        // Missing addTags.
        given().header("X-Requested-By", hrUser)
                .contentType(ContentType.JSON)
                .body(Map.of("candidateUuids", List.of(taggedCandidate)))
                .when().post(BULK)
                .then().statusCode(400);
        // Tags that trim to nothing.
        given().header("X-Requested-By", hrUser)
                .contentType(ContentType.JSON)
                .body(Map.of("candidateUuids", List.of(taggedCandidate),
                        "addTags", List.of("   ")))
                .when().post(BULK)
                .then().statusCode(400);
        // Over-long tag (the single-tag path's @Size cap, enforced explicitly).
        given().header("X-Requested-By", hrUser)
                .contentType(ContentType.JSON)
                .body(Map.of("candidateUuids", List.of(taggedCandidate),
                        "addTags", List.of("x".repeat(51))))
                .when().post(BULK)
                .then().statusCode(400);
    }

    // ---- Authorization ---------------------------------------------------------------

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void bulkAddTags_nonRecruiterActor_answers403() {
        given().header("X-Requested-By", plainUser)
                .contentType(ContentType.JSON)
                .body(Map.of("candidateUuids", List.of(taggedCandidate),
                        "addTags", List.of("p8bulk")))
                .when().post(BULK)
                .then().statusCode(403);
        QuarkusTransaction.requiringNew().run(() ->
                assertEquals(List.of("existing"), tagsOf(taggedCandidate)));
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void bulkAddTags_invisiblePartnerTrackTarget_answers404_forTheWholeCall() {
        given().header("X-Requested-By", hrUser)
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "candidateUuids", List.of(taggedCandidate, partnerOnlyCandidate),
                        "addTags", List.of("p8bulk")))
                .when().post(BULK)
                .then().statusCode(404);
        QuarkusTransaction.requiringNew().run(() -> {
            // All-or-nothing: the visible target stayed untouched too.
            assertEquals(List.of("existing"), tagsOf(taggedCandidate));
            assertEquals(0, updateEventsFor(taggedCandidate).size());
        });
    }

    @Test
    @TestSecurity(user = "bff-client", roles = {"recruitment:read", "recruitment:write"})
    void bulkAddTags_unknownCandidate_answers404() {
        given().header("X-Requested-By", hrUser)
                .contentType(ContentType.JSON)
                .body(Map.of("candidateUuids", List.of(UUID.randomUUID().toString()),
                        "addTags", List.of("p8bulk")))
                .when().post(BULK)
                .then().statusCode(404);
    }

    // ---- Helpers --------------------------------------------------------------------

    /** The candidate's tags, freshly loaded through the entity converter. */
    private List<String> tagsOf(String candidateUuid) {
        RecruitmentCandidate candidate = RecruitmentCandidate.findById(candidateUuid);
        return candidate.getTags();
    }

    private List<RecruitmentEvent> updateEventsFor(String candidateUuid) {
        return RecruitmentEvent.list("candidateUuid = ?1 and eventType = ?2 order by seq",
                candidateUuid, RecruitmentEventType.CANDIDATE_UPDATED);
    }
}
