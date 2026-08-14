package dk.trustworks.intranet.competenceservice.services;

import dk.trustworks.intranet.competenceservice.domain.CompetenceAudienceMatcher.Targeting;
import dk.trustworks.intranet.competenceservice.model.CompetenceContentVersion;
import dk.trustworks.intranet.competenceservice.model.CompetenceRequirement;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code POST /admin/import/content} must apply §11.4 to the targeting it writes.
 *
 * <p>The REST upsert has always refused a practice, team or user uuid that does not resolve —
 * {@code CompetenceRequirementService.requireResolvableTargeting}, whose javadoc calls that
 * "the failure this prevents is the worst one the module has". The import path writes the very
 * same three columns from a file an author edited by hand, which is the <em>likelier</em> place
 * for a typo, and it did not run the check.
 *
 * <p>What the missing check costs is not disclosure but the module's whole claim: a targeting
 * uuid that matches nobody removes the krav from every learner's list, marks every matrix cell
 * "not applicable", and lets approved coverage read 100 % over whatever is left. Nothing goes
 * red, no reminder fires, and the headcount alone cannot tell a typo apart from deliberately
 * narrow targeting. So the assertion that matters is not only "a 400 comes back" but "nothing
 * was written" — a partially imported file would leave exactly that state behind.
 *
 * <p><strong>Why the fast tier.</strong> The three collaborators are a
 * {@link CompetenceRequirementService} (a plain class — a Mockito mock, standing in for the
 * three database lookups that decide what resolves), a {@link RequestHeaderHolder} (a Lombok
 * {@code @Data} bean — just {@code new}), and two Panache statics that {@code mockStatic}
 * intercepts the way the rest of this codebase's fast-tier service tests do. That is the tier
 * the CI deploy gate runs, and a rule this load-bearing must not live only in the ungated
 * {@code @QuarkusTest} tier.
 */
class CompetenceImportTargetingTest {

    private static final String COMP_ID = "adgangsstyring";
    private static final String ACTOR = "9a1c0d3e-user-0000-0000-000000000001";
    private static final String ADMIN = "9a1c0d3e-admn-0000-0000-000000000002";

    /** The uuid an author fat-fingered. Resolves to no practice row. */
    private static final String TYPO_PRACTICE = "3f7a0e10-tech-TYPO";

    /**
     * A one-topic v2 file whose targeting names one practice. Deliberately quiz-only: the
     * targeting rule has nothing to do with the content shape, and a course would add
     * thirteen lines of screens that no assertion here looks at.
     */
    private static final String FILE = """
            {
              "kind": "kompetencemodul-full-export",
              "formatVersion": 2,
              "exportedAt": "2026-09-01T00:00:00.000Z",
              "topics": [
                {
                  "compId": "adgangsstyring",
                  "kref": "K2",
                  "name": "Adgangsstyring",
                  "desc": "Hvem må hvad, og hvordan bevises det",
                  "targetPracticeUuids": ["3f7a0e10-tech-TYPO"],
                  "quiz": {
                    "version": "2026-09",
                    "questions": [
                      {
                        "id": "kode-1",
                        "text": "Hvad betyder mindste privilegium?",
                        "options": [
                          { "id": "kode-1-a", "text": "Kun den adgang opgaven kræver", "correct": true },
                          { "id": "kode-1-b", "text": "Adgang til alt i eget team", "correct": false }
                        ]
                      }
                    ]
                  }
                }
              ]
            }
            """;

    private CompetenceContentService contentService;
    private CompetenceRequirementService requirementService;
    private RequestHeaderHolder headerHolder;

    /** The requirement the file re-imports onto, already targeted at a practice that resolves. */
    private CompetenceRequirement existing;

    @BeforeEach
    void setUp() {
        requirementService = mock(CompetenceRequirementService.class);
        headerHolder = new RequestHeaderHolder();
        headerHolder.setUserUuid(ACTOR);

        contentService = new CompetenceContentService();
        contentService.requirementService = requirementService;
        contentService.requestHeaderHolder = headerHolder;

        existing = new CompetenceRequirement();
        existing.setUuid("requirement-uuid");
        existing.setCompId(COMP_ID);
        existing.setTargetPracticeUuids("[\"3f7a0e10-tech\"]");
    }

    @Test
    @DisplayName("an import naming a practice uuid that does not resolve is a 400 that names it")
    void unresolvableTargetingIsRefused() {
        when(requirementService.blockingTargetingProblems(any()))
                .thenReturn(List.of("practice " + TYPO_PRACTICE + ": findes ikke"));

        try (MockedStatic<CompetenceRequirement> requirements = mockStatic(CompetenceRequirement.class);
             MockedStatic<CompetenceContentVersion> versions = mockStatic(CompetenceContentVersion.class)) {
            requirements.when(() -> CompetenceRequirement.findByCompId(COMP_ID)).thenReturn(existing);

            WebApplicationException thrown = assertThrows(WebApplicationException.class,
                    () -> contentService.importContent(FILE, ACTOR));

            assertEquals(Response.Status.BAD_REQUEST.getStatusCode(),
                    thrown.getResponse().getStatus(),
                    "an unknown target is the author's mistake, not a server fault");
            assertTrue(thrown.getMessage().contains(TYPO_PRACTICE),
                    "the message must name the offending uuid — 'et mål findes ikke' sends an "
                            + "author back to a file with no idea which value to fix: " + thrown.getMessage());
            assertTrue(thrown.getMessage().contains(COMP_ID),
                    "and the topic it belongs to, because a file carries four of them: "
                            + thrown.getMessage());

            // The point of the finding: not merely that it 400s, but that the refusal happens
            // before the write loop. A draft written for a topic whose targeting was rejected
            // would be a half-imported file, which is the state nobody can reason about.
            versions.verifyNoInteractions();
            assertEquals("[\"3f7a0e10-tech\"]", existing.getTargetPracticeUuids(),
                    "the stored audience must not move when the file is refused");
        }
    }

    @Test
    @DisplayName("the same file imports and retargets when every target resolves")
    void resolvableTargetingIsWritten() {
        when(requirementService.blockingTargetingProblems(any())).thenReturn(List.of());
        CompetenceContentVersion draftRow = mock(CompetenceContentVersion.class);

        try (LogRecorder logs = new LogRecorder(CompetenceContentService.class);
             MockedStatic<CompetenceRequirement> requirements = mockStatic(CompetenceRequirement.class);
             MockedStatic<CompetenceContentVersion> versions = mockStatic(CompetenceContentVersion.class)) {
            requirements.when(() -> CompetenceRequirement.findByCompId(COMP_ID)).thenReturn(existing);
            versions.when(() -> CompetenceContentVersion.findDraft(any(), any())).thenReturn(draftRow);
            headerHolder.setActingForUuid(ADMIN);

            CompetenceContentService.ImportSummary summary = contentService.importContent(FILE, ACTOR);

            assertEquals(1, summary.topics());
            assertEquals(0, summary.requirementsCreated());
            assertEquals(1, summary.draftsWritten());
            verify(draftRow).setPayloadJson(any());
            assertTrue(existing.getTargetPracticeUuids().contains(TYPO_PRACTICE),
                    "a resolvable targeting array is written through, as before");

            // Positive control for the log assertions below: without it a "must not log"
            // assertion elsewhere could pass because the handler is on the wrong logger.
            String retargeted = logs.only("COMPETENCE_REQUIREMENT_RETARGETED");
            assertNotNull(retargeted, "moving an audience through an import must leave a trace — "
                    + "COMPETENCE_CONTENT_IMPORTED counts topics and drafts and cannot say that a "
                    + "krav changed hands: " + logs.lines());
            assertTrue(retargeted.contains("from=[\"3f7a0e10-tech\"]"),
                    "with the audience it had before — 'the targeting changed' is not evidence: "
                            + retargeted);
            assertTrue(retargeted.contains("to=[\"" + TYPO_PRACTICE + "\"]"),
                    "and the audience it now has: " + retargeted);
            assertTrue(retargeted.contains("actor=" + ACTOR), retargeted);
            assertTrue(retargeted.contains("actingFor=" + ADMIN),
                    "and the admin behind an impersonated session, or nothing records that the "
                            + "retargeting was not the subject's own doing: " + retargeted);

            String imported = logs.only("COMPETENCE_CONTENT_IMPORTED");
            assertNotNull(imported, logs.lines().toString());
            assertTrue(imported.contains("actingFor=" + ADMIN), imported);
        }
    }

    @Test
    @DisplayName("the targeting the import checks is the targeting the file carries")
    void theCheckedTargetingIsTheFileTargeting() {
        when(requirementService.blockingTargetingProblems(any()))
                .thenReturn(List.of("practice " + TYPO_PRACTICE + ": findes ikke"));

        try (MockedStatic<CompetenceRequirement> requirements = mockStatic(CompetenceRequirement.class);
             MockedStatic<CompetenceContentVersion> versions = mockStatic(CompetenceContentVersion.class)) {
            requirements.when(() -> CompetenceRequirement.findByCompId(COMP_ID)).thenReturn(existing);

            assertThrows(WebApplicationException.class,
                    () -> contentService.importContent(FILE, ACTOR));

            // Checking the stored targeting instead of the file's would validate the value that
            // is about to be overwritten — the check would pass on every import that breaks one.
            ArgumentCaptor<Targeting> captor = ArgumentCaptor.forClass(Targeting.class);
            verify(requirementService).blockingTargetingProblems(captor.capture());
            assertEquals(List.of(TYPO_PRACTICE), captor.getValue().practiceUuids());
            assertNull(captor.getValue().teamUuids(),
                    "an absent array stays absent — NULL and [] mean different things (§5.2)");
        }
    }
}
