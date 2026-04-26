package dk.trustworks.intranet.recruitmentservice.domain.entities;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CandidateCvTest {

    @Test
    @Transactional
    void persistAndReload() {
        // Need a candidate to FK to — keep it minimal.
        Candidate c = new Candidate();
        c.uuid = UUID.randomUUID().toString();
        c.firstName = "Alice";
        c.consentStatus = "PENDING";
        c.persist();

        CandidateCv cv = new CandidateCv();
        cv.uuid = UUID.randomUUID().toString();
        cv.candidateUuid = c.uuid;
        cv.fileUrl = "https://sharepoint.example.com/Sites/Recruitment/Candidates/" + c.uuid + "/cv-1.pdf";
        cv.fileSha256 = "a".repeat(64);
        cv.isCurrent = true;
        cv.uploadedByUuid = UUID.randomUUID().toString();
        cv.uploadedAt = LocalDateTime.now();
        cv.persistAndFlush();

        CandidateCv reloaded = CandidateCv.findById(cv.uuid);
        assertEquals(c.uuid, reloaded.candidateUuid);
        assertTrue(reloaded.isCurrent);
        // currentForUnique is derived by trigger; should equal candidate.uuid
        assertEquals(c.uuid, reloaded.currentForUnique);
    }

    @Test
    @Transactional
    void uniqueOneCurrentCvPerCandidate() {
        Candidate c = new Candidate();
        c.uuid = UUID.randomUUID().toString();
        c.firstName = "Bob";
        c.consentStatus = "PENDING";
        c.persist();

        CandidateCv first = makeCv(c.uuid, true);
        first.persistAndFlush();

        CandidateCv second = makeCv(c.uuid, true);
        assertThrows(Exception.class, () -> second.persistAndFlush(),
                "second is_current=true CV for same candidate is rejected by uk_one_current_cv_per_candidate");
    }

    private CandidateCv makeCv(String candidateUuid, boolean isCurrent) {
        CandidateCv cv = new CandidateCv();
        cv.uuid = UUID.randomUUID().toString();
        cv.candidateUuid = candidateUuid;
        cv.fileUrl = "https://x/" + UUID.randomUUID() + ".pdf";
        cv.fileSha256 = "b".repeat(64);
        cv.isCurrent = isCurrent;
        cv.uploadedByUuid = UUID.randomUUID().toString();
        cv.uploadedAt = LocalDateTime.now();
        return cv;
    }
}
