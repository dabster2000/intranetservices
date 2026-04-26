package dk.trustworks.intranet.recruitmentservice.application.handlers;

import dk.trustworks.intranet.recruitmentservice.domain.entities.AiArtifact;
import dk.trustworks.intranet.recruitmentservice.domain.enums.AiArtifactKind;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure JUnit (no {@code @QuarkusTest}) — the handler is a stateless no-op
 * with no DB or CDI dependency, so booting the full Quarkus context just to
 * exercise it is unnecessary overhead.
 */
class CandidateSummaryApplyHandlerTest {

    private final CandidateSummaryApplyHandler handler = new CandidateSummaryApplyHandler();

    @Test
    void handlesCandidateSummaryKind() {
        assertTrue(handler.handles(AiArtifactKind.CANDIDATE_SUMMARY));
        assertFalse(handler.handles(AiArtifactKind.ROLE_BRIEF));
        assertFalse(handler.handles(AiArtifactKind.CV_EXTRACTION));
    }

    @Test
    void apply_isNoOp_doesNotThrow() {
        AiArtifact a = new AiArtifact();
        a.uuid = UUID.randomUUID().toString();
        a.kind = AiArtifactKind.CANDIDATE_SUMMARY.name();
        a.output = "{}";
        assertDoesNotThrow(() -> handler.apply(a, null));
    }
}
