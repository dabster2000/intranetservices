package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.documentservice.model.DocumentTemplateEntity;
import dk.trustworks.intranet.recruitmentservice.model.enums.SharePointMoveStatus;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit-level coverage for {@link CandidateConversionUseCase}'s post-hire
 * SharePoint integration. Full end-to-end flow is exercised by integration
 * tests; this class proves only the focused decisions:
 *
 * <ul>
 *   <li>The use case still carries the {@link SharePointEmployeeFolderService}
 *       collaborator (now used from {@link CandidateConversionUseCase#runSharePointCopy(UUID)}
 *       instead of {@code execute}).</li>
 *   <li>The new {@code runSharePointCopy(UUID)} and
 *       {@code applySharePointResult(UUID, SharePointMoveStatus)} methods are
 *       present with the expected signatures so the resource layer (which
 *       dispatches the copy on a {@code ManagedExecutor} after the
 *       conversion tx commits) keeps compiling.</li>
 *   <li>The transactional follow-up {@code applySharePointResult} is
 *       annotated {@code @Transactional} so the status update + retention
 *       stamp share a short tx (the slow Graph upload itself runs outside
 *       any DB tx).</li>
 *   <li>The {@code DocumentTemplateEntity.getSharepointFolder} accessor
 *       still exists (used by
 *       {@link SharePointEmployeeFolderService#resolveTemplateBaseFolder}).</li>
 *   <li>The {@link SharePointMoveStatus} enum still includes the four
 *       values the use case drives ({@code PENDING}/{@code PARTIAL}/
 *       {@code FAILED}/{@code COMPLETED}).</li>
 * </ul>
 *
 * Reflection-only assertions so the test runs without booting Quarkus.
 */
class CandidateConversionUseCaseSharePointTest {

    @Test
    void useCase_class_carriesSharePointEmployeeFolderServiceField() throws Exception {
        // The injected field exists so the use case can hand off to
        // SharePointEmployeeFolderService from runSharePointCopy(...).
        // Reflection guards against accidental rename.
        Field f = CandidateConversionUseCase.class.getDeclaredField("sharePointEmployeeFolderService");
        assertEquals(SharePointEmployeeFolderService.class, f.getType());
    }

    @Test
    void useCase_exposes_runSharePointCopy_method() throws Exception {
        // The resource layer dispatches this on a ManagedExecutor after
        // execute() commits. Reflection asserts the public API stays stable.
        Method m = CandidateConversionUseCase.class.getMethod("runSharePointCopy", UUID.class);
        assertNotNull(m, "runSharePointCopy(UUID) must be a public method");
        assertEquals(void.class, m.getReturnType());
        // NOT @Transactional at the method level — the slow Graph upload must
        // run without holding a DB tx open.
        assertTrue(m.getAnnotation(jakarta.transaction.Transactional.class) == null,
                "runSharePointCopy(UUID) must NOT be @Transactional — Graph upload runs off-tx");
    }

    @Test
    void useCase_exposes_applySharePointResult_method() throws Exception {
        // The short follow-up tx that writes status + (on COMPLETED) the
        // retention stamp.
        Method m = CandidateConversionUseCase.class.getMethod(
                "applySharePointResult", UUID.class, SharePointMoveStatus.class);
        assertNotNull(m, "applySharePointResult(UUID, SharePointMoveStatus) must be public");
        assertEquals(void.class, m.getReturnType());
        // MUST be @Transactional so the status update + retention stamp share
        // a short, single tx — the slow Graph upload itself is already done.
        assertNotNull(m.getAnnotation(jakarta.transaction.Transactional.class),
                "applySharePointResult(...) must be @Transactional");
    }

    @Test
    void documentTemplateEntity_exposes_sharepointFolder_getter() throws Exception {
        // The use case (via SharePointEmployeeFolderService.resolveTemplateBaseFolder)
        // reads the template's sharepoint_folder via the Lombok getter.
        // Reflection guards against the field/getter being renamed.
        Method getter = DocumentTemplateEntity.class.getMethod("getSharepointFolder");
        assertEquals(String.class, getter.getReturnType());
    }

    @Test
    void sharePointMoveStatus_enum_supports_partial_and_completed() {
        // Sanity: the enum values referenced from the use case are present
        // (the use case sets PENDING from execute(), then runSharePointCopy
        // calls applySharePointResult with PARTIAL/FAILED/COMPLETED depending
        // on outcome).
        SharePointMoveStatus[] expected = SharePointMoveStatus.values();
        boolean hasPartial = false, hasCompleted = false, hasPending = false, hasFailed = false;
        for (SharePointMoveStatus s : expected) {
            if (s == SharePointMoveStatus.PARTIAL) hasPartial = true;
            if (s == SharePointMoveStatus.COMPLETED) hasCompleted = true;
            if (s == SharePointMoveStatus.PENDING) hasPending = true;
            if (s == SharePointMoveStatus.FAILED) hasFailed = true;
        }
        assertTrue(hasPartial && hasCompleted && hasPending && hasFailed,
                "SharePointMoveStatus must include PENDING, COMPLETED, PARTIAL, FAILED");
    }
}
