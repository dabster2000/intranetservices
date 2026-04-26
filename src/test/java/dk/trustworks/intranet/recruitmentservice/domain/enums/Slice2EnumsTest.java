package dk.trustworks.intranet.recruitmentservice.domain.enums;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class Slice2EnumsTest {

    @Test
    void aiArtifactKindHasAllSpecValues() {
        // Spec §4.8 lists these 7 kinds; Slice 2 implements first 3; rest still defined.
        assertNotNull(AiArtifactKind.valueOf("ROLE_BRIEF"));
        assertNotNull(AiArtifactKind.valueOf("CV_EXTRACTION"));
        assertNotNull(AiArtifactKind.valueOf("CANDIDATE_SUMMARY"));
        assertNotNull(AiArtifactKind.valueOf("SCREENING_RECOMMENDATION"));
        assertNotNull(AiArtifactKind.valueOf("INTERVIEW_KIT"));
        assertNotNull(AiArtifactKind.valueOf("SCORECARD_ROUNDUP"));
        assertNotNull(AiArtifactKind.valueOf("TALENT_POOL_MATCH"));
        assertEquals(7, AiArtifactKind.values().length);
    }

    @Test
    void aiArtifactStateLifecycle() {
        // Spec §4.8: NOT_GENERATED → GENERATING → GENERATED → REVIEWED|OVERRIDDEN; FAILED is terminal
        assertTrue(AiArtifactState.GENERATING.isInProgress());
        assertTrue(AiArtifactState.GENERATED.isReviewable());
        assertTrue(AiArtifactState.REVIEWED.isTerminal());
        assertTrue(AiArtifactState.OVERRIDDEN.isTerminal());
        assertTrue(AiArtifactState.FAILED.isTerminal());
        assertFalse(AiArtifactState.GENERATING.isReviewable());
    }

    @Test
    void outboxStatusValues() {
        assertEquals(4, OutboxStatus.values().length);
        for (var s : new OutboxStatus[]{OutboxStatus.PENDING, OutboxStatus.IN_FLIGHT, OutboxStatus.DONE, OutboxStatus.FAILED}) {
            assertNotNull(s);
        }
    }

    @Test
    void aiSubjectKindMatchesSpec() {
        assertEquals(5, AiSubjectKind.values().length);
        assertNotNull(AiSubjectKind.ROLE);
        assertNotNull(AiSubjectKind.CANDIDATE);
        assertNotNull(AiSubjectKind.APPLICATION);
        assertNotNull(AiSubjectKind.INTERVIEW);
        assertNotNull(AiSubjectKind.OFFER);
    }

    @Test
    void outboxKindIncludesAiGenerate() {
        // Slice 2 only uses AI_GENERATE. Other kinds are reserved per V307 outbox table enum.
        assertNotNull(OutboxKind.valueOf("AI_GENERATE"));
    }
}
