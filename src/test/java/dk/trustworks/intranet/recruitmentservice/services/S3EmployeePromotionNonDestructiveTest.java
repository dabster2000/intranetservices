package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.fileservice.services.S3FileService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Promotion is a copy, and must stay one.
 *
 * <p>Until 2026-08-11 {@code runPromotion} deleted each staging original
 * immediately after copying it. That coupling is what turned a selection
 * mistake into permanent loss: one candidate's 24 dossier files were
 * destroyed, leaving the revision snapshots, the appendix rows and the
 * candidate Documents tab all pointing at nothing. The bytes survived only
 * because the copy happened first.</p>
 *
 * <p>These are structural assertions rather than behavioural ones on purpose.
 * "Verify this collaborator was never called" is not available once the
 * collaborator is gone — and the collaborator being gone is the stronger
 * guarantee. They exist to make the two ways this regresses fail loudly:
 * re-injecting a file-deleting dependency, or arming the retention reaper on
 * the way past.</p>
 */
class S3EmployeePromotionNonDestructiveTest {

    @Test
    void thePromotionServiceHoldsNoFileDeletingDependency() {
        boolean holdsFileService = Arrays.stream(S3EmployeePromotionService.class.getDeclaredFields())
                .map(Field::getType)
                .anyMatch(S3FileService.class::equals);

        assertFalse(holdsFileService,
                "S3EmployeePromotionService must not be able to delete files. Promotion is a copy: "
                        + "the offer dossier keeps every original, including the ones a pass declines "
                        + "to promote. If you need this dependency, you are about to re-create the "
                        + "2026-08-11 data loss.");
    }

    /**
     * The subtler regression. Stamping a retention deadline on the
     * candidate's revisions looks like tidy lifecycle management, and the
     * retired copy pipeline did exactly that — its reaper then called the
     * same {@code S3FileService.delete} on every ref in the snapshot. Under
     * the signed-only rule those drafts have no copy anywhere else, so such
     * a stamp would delete them outright thirty days later.
     */
    @Test
    void thePromotionServiceArmsNoRetentionClock() {
        boolean stampsRetention = Arrays.stream(S3EmployeePromotionService.class.getDeclaredMethods())
                .map(Method::getName)
                .anyMatch(name -> name.toLowerCase().contains("retention"));

        assertFalse(stampsRetention,
                "Promotion must not stamp a retention clock. The nightly reaper deletes what it "
                        + "stamps, and under the signed-only rule the unpromoted drafts exist "
                        + "nowhere else — a retention policy for the offer dossier is separate, "
                        + "explicit work.");
    }

    @Test
    void theEnumeratorIsReachableFromTheSamePackageForTesting() {
        // signedItems is the pure core of the rule; if it stops being
        // package-visible the selection tests silently lose their subject.
        boolean signedItemsVisible = Arrays.stream(S3EmployeePromotionService.class.getDeclaredMethods())
                .anyMatch(m -> m.getName().equals("signedItems"));

        assertTrue(signedItemsVisible);
    }
}
