package dk.trustworks.intranet.recruitmentservice.domain.entities;

import dk.trustworks.intranet.recruitmentservice.domain.enums.AiArtifactKind;
import dk.trustworks.intranet.recruitmentservice.domain.enums.AiArtifactState;
import dk.trustworks.intranet.recruitmentservice.domain.enums.AiSubjectKind;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
class AiArtifactTest {

    @Test
    @Transactional
    void persistAndReload() {
        AiArtifact a = new AiArtifact();
        a.uuid = UUID.randomUUID().toString();
        a.subjectKind = AiSubjectKind.CANDIDATE;
        a.subjectUuid = UUID.randomUUID().toString();
        a.kind = AiArtifactKind.CV_EXTRACTION.name();
        a.promptVersion = "cv-extraction-v1";
        a.model = "gpt-5-nano";
        a.inputDigest = "a".repeat(64);
        a.state = AiArtifactState.GENERATING.name();
        a.persistAndFlush();

        AiArtifact found = AiArtifact.findById(a.uuid);
        assertNotNull(found);
        assertEquals(AiSubjectKind.CANDIDATE, found.subjectKind);
        assertEquals(AiArtifactState.GENERATING.name(), found.state);
    }

    @Test
    @Transactional
    void uniqueOnSubjectKindUuidKindDigest() {
        // V307 has UNIQUE (subject_kind, subject_uuid, kind, input_digest)
        String subj = UUID.randomUUID().toString();
        String digest = "b".repeat(64);

        AiArtifact a = makeArtifact(subj, AiArtifactKind.CV_EXTRACTION, digest);
        a.persistAndFlush();

        AiArtifact dup = makeArtifact(subj, AiArtifactKind.CV_EXTRACTION, digest);
        assertThrows(Exception.class, () -> {
            dup.persistAndFlush();
        });
    }

    private AiArtifact makeArtifact(String subjectUuid, AiArtifactKind kind, String digest) {
        AiArtifact a = new AiArtifact();
        a.uuid = UUID.randomUUID().toString();
        a.subjectKind = AiSubjectKind.CANDIDATE;
        a.subjectUuid = subjectUuid;
        a.kind = kind.name();
        a.promptVersion = "v1";
        a.model = "gpt-5-nano";
        a.inputDigest = digest;
        a.state = AiArtifactState.GENERATING.name();
        return a;
    }
}
