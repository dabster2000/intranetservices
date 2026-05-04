package dk.trustworks.intranet.recruitmentservice.model;

import dk.trustworks.intranet.recruitmentservice.model.enums.DossierStatus;
import dk.trustworks.intranet.recruitmentservice.model.enums.RevisionKind;
import dk.trustworks.intranet.recruitmentservice.model.exception.BusinessRuleViolation;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Pure unit tests for {@link CandidateDossier#allocateRevision} state guards.
 * <p>
 * Only the CLOSED-status guard is exercised here — the OPEN path triggers a
 * JPA query for {@code MAX(versionNumber)} and therefore requires Quarkus
 * boot (covered separately by an integration test). Argument-validation paths
 * are also covered because they don't touch the EntityManager.
 */
class CandidateDossierAllocateRevisionTest {

    private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private CandidateDossier closed() {
        CandidateDossier d = new CandidateDossier();
        d.setUuid(UUID.randomUUID().toString());
        d.setCandidateUuid(UUID.randomUUID().toString());
        d.setTemplateUuid(UUID.randomUUID().toString());
        d.setStatus(DossierStatus.CLOSED);
        return d;
    }

    private CandidateDossier open() {
        CandidateDossier d = closed();
        d.setStatus(DossierStatus.OPEN);
        return d;
    }

    // -- CLOSED dossier: allocateRevision must throw --

    @Test
    void allocateRevision_onClosedDossier_throwsBusinessRuleViolation() {
        CandidateDossier dossier = closed();

        BusinessRuleViolation ex = assertThrows(BusinessRuleViolation.class,
                () -> dossier.allocateRevision(RevisionKind.REVIEW_EMAIL, ACTOR));
        assertNotNull(ex.getMessage());
    }

    @Test
    void allocateRevision_onClosedDossier_throwsForEveryRevisionKind() {
        for (RevisionKind kind : RevisionKind.values()) {
            CandidateDossier dossier = closed();
            assertThrows(BusinessRuleViolation.class,
                    () -> dossier.allocateRevision(kind, ACTOR),
                    "Expected BusinessRuleViolation for kind=" + kind);
        }
    }

    // -- Argument validation (does not touch EntityManager) --

    @Test
    void allocateRevision_nullKind_throwsNullPointerException() {
        // Null guard fires before status guard — verify on OPEN dossier so the
        // status path is not taken (and the JPA query is never reached because
        // requireNonNull throws first).
        CandidateDossier dossier = open();
        assertThrows(NullPointerException.class,
                () -> dossier.allocateRevision(null, ACTOR));
    }

    @Test
    void allocateRevision_nullActor_throwsNullPointerException() {
        CandidateDossier dossier = open();
        assertThrows(NullPointerException.class,
                () -> dossier.allocateRevision(RevisionKind.SIGNATURE, null));
    }

    // -- closeOnTerminal idempotency --

    @Test
    void closeOnTerminal_onOpenDossier_setsStatusClosed() {
        CandidateDossier dossier = open();

        dossier.closeOnTerminal();

        assertEquals(DossierStatus.CLOSED, dossier.getStatus());
    }

    @Test
    void closeOnTerminal_onClosedDossier_isIdempotent() {
        CandidateDossier dossier = closed();

        dossier.closeOnTerminal();
        dossier.closeOnTerminal();

        assertEquals(DossierStatus.CLOSED, dossier.getStatus());
    }
}
