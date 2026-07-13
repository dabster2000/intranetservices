package dk.trustworks.intranet.aggregates.bonus.individual.services;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndividualBonusPreviewProofServiceTest {

    @Test
    void digestComparison_acceptsOnlyEqualFixedLengthSha256Hex() {
        String digest = "0123456789abcdef".repeat(4);
        assertTrue(IndividualBonusPreviewProofService.safeDigestEquals(digest, digest));
        assertFalse(IndividualBonusPreviewProofService.safeDigestEquals(
                digest, "1123456789abcdef" + "0123456789abcdef".repeat(3)));
        assertFalse(IndividualBonusPreviewProofService.safeDigestEquals(digest, "short"));
        assertFalse(IndividualBonusPreviewProofService.safeDigestEquals(digest, "z".repeat(64)));
        assertFalse(IndividualBonusPreviewProofService.safeDigestEquals(null, digest));
    }
}
