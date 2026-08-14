package dk.trustworks.intranet.competenceservice.services;

import dk.trustworks.intranet.competenceservice.model.CompetenceContentVersion;
import dk.trustworks.intranet.competenceservice.model.CompetenceRequirement;
import dk.trustworks.intranet.competenceservice.model.ContentKind;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The two authoring events that carried no actor: the content export and the draft discard.
 *
 * <p>§10.7 requires every export to be logged and §10.9 says so again. The two CSV exports
 * always carried {@code actor=} and {@code rows=}; the content export carried neither, and its
 * service method did not even take an actor. It is the <em>most</em> sensitive export in the
 * module: a topic carries every quiz question with {@code correct} on each option — the whole
 * answer key — plus any unpublished draft. Someone who downloads it can afterwards pass every
 * test at 100 % with attempts that log and approve normally, so the name on the export line is
 * the only thing that makes the download reconstructable at all.
 *
 * <p>Discarding a draft is the one destructive operation on the authoring surface — a real
 * {@code DELETE}, because {@code competence_content_version} is the one competence table
 * without an append-only trigger — and it was the one event with no attribution field
 * whatsoever.
 *
 * <p>Both lines also carry {@code actingFor=}: during impersonation the actor <em>is</em> the
 * impersonated subject (the swap happens in the BFF's JWT), so without it a support session
 * that exported the answer key would be recorded as the content owner doing it themselves.
 */
class CompetenceContentAttributionTest {

    private static final String ACTOR = "5c2e77a1-user-0000-0000-000000000001";
    private static final String ADMIN = "5c2e77a1-admn-0000-0000-000000000002";

    private CompetenceContentService contentService;
    private RequestHeaderHolder headerHolder;

    @BeforeEach
    void setUp() {
        headerHolder = new RequestHeaderHolder();
        headerHolder.setUserUuid(ACTOR);

        contentService = new CompetenceContentService();
        contentService.requirementService = mock(CompetenceRequirementService.class);
        contentService.requestHeaderHolder = headerHolder;
    }

    @Test
    @DisplayName("the content export — the answer key — is logged with the actor")
    void contentExportNamesTheActor() {
        try (LogRecorder logs = new LogRecorder(CompetenceContentService.class);
             MockedStatic<CompetenceRequirement> requirements = mockStatic(CompetenceRequirement.class)) {
            requirements.when(() -> CompetenceRequirement.listAllOrdered()).thenReturn(List.of());

            contentService.exportContent(null, ACTOR);

            String line = logs.only("COMPETENCE_EXPORT kind=content");
            assertNotNull(line, "the export event itself must still be logged: " + logs.lines());
            assertTrue(line.contains("actor=" + ACTOR),
                    "an export of every answer to every test that names nobody leaves the log "
                            + "sweep unable to say who downloaded it: " + line);
        }
    }

    @Test
    @DisplayName("an impersonated export names the admin behind the session, not only the subject")
    void contentExportNamesTheImpersonator() {
        headerHolder.setActingForUuid(ADMIN);

        try (LogRecorder logs = new LogRecorder(CompetenceContentService.class);
             MockedStatic<CompetenceRequirement> requirements = mockStatic(CompetenceRequirement.class)) {
            requirements.when(() -> CompetenceRequirement.listAllOrdered()).thenReturn(List.of());

            contentService.exportContent(null, ACTOR);

            String line = logs.only("COMPETENCE_EXPORT kind=content");
            assertNotNull(line, logs.lines().toString());
            assertTrue(line.contains("actingFor=" + ADMIN), line);
        }
    }

    @Test
    @DisplayName("discarding a draft — the module's one delete — is attributed")
    void discardNamesTheActor() {
        CompetenceContentVersion draft = mock(CompetenceContentVersion.class);
        when(draft.getVersionLabel()).thenReturn("2026-08");

        try (LogRecorder logs = new LogRecorder(CompetenceContentService.class);
             MockedStatic<CompetenceContentVersion> versions = mockStatic(CompetenceContentVersion.class)) {
            versions.when(() -> CompetenceContentVersion.findDraft(any(), any())).thenReturn(draft);

            contentService.discardDraft("requirement-uuid", ContentKind.COURSE, ACTOR);

            verify(draft).delete();
            String line = logs.only("COMPETENCE_DRAFT_DISCARDED");
            assertNotNull(line, logs.lines().toString());
            assertTrue(line.contains("actor=" + ACTOR),
                    "the one operation that removes a row must not be the one event nobody signs: "
                            + line);
        }
    }
}
