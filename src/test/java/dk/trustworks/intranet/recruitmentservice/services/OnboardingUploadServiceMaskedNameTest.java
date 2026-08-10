package dk.trustworks.intranet.recruitmentservice.services;

import dk.trustworks.intranet.recruitmentservice.dto.OnboardingValidateResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for the masked owner name returned by the public
 * {@code /onboarding/tokens/{uuid}/validate} endpoint.
 *
 * <p>The name exists so a visitor can tell a mis-sent upload link from their
 * own before uploading identity documents — the page previously showed no
 * recipient identity at all. Because the endpoint is {@code @PermitAll}, the
 * value is masked: enough to answer "is this me?", never a full name.</p>
 */
class OnboardingUploadServiceMaskedNameTest {

    // ── The shape the page renders ────────────────────────────────────────

    @Test
    void mask_givenNameAndSurnameInitial() {
        assertEquals("Henrik F.",
                OnboardingUploadService.maskDisplayName("Henrik", "Falch Midtgaard"));
    }

    @Test
    void mask_singleSurname() {
        assertEquals("Peter F.",
                OnboardingUploadService.maskDisplayName("Peter", "Faber"));
    }

    @Test
    void mask_neverLeaksTheFullSurname() {
        String masked = OnboardingUploadService.maskDisplayName("Henrik", "Falch Midtgaard");
        // The whole point of masking: the surname tokens must not survive.
        assertFalse(masked.contains("Falch"));
        assertFalse(masked.contains("Midtgaard"));
    }

    @Test
    void mask_initialComesFromFirstSurnameToken() {
        // Danish records carry middle names in the surname field; the first
        // token is what the recipient recognises from their own paperwork.
        assertEquals("Sofie D.",
                OnboardingUploadService.maskDisplayName("Sofie", "Damkjær Østensgård"));
    }

    @Test
    void mask_distinguishesTheTwoPeopleAMisSendConfuses() {
        // The 2026-08-10 incident: documents for Henrik landed in Peter's file.
        // Masking still has to make those two links visibly different.
        assertEquals("Henrik F.", OnboardingUploadService.maskDisplayName("Henrik", "Falch Midtgaard"));
        assertEquals("Peter F.", OnboardingUploadService.maskDisplayName("Peter", "Faber"));
        assertNotEquals(
                OnboardingUploadService.maskDisplayName("Henrik", "Falch Midtgaard"),
                OnboardingUploadService.maskDisplayName("Peter", "Faber"));
    }

    // ── Normalisation ─────────────────────────────────────────────────────

    @Test
    void mask_trimsSurroundingWhitespace() {
        assertEquals("Henrik F.",
                OnboardingUploadService.maskDisplayName("  Henrik  ", "  Falch  "));
    }

    @Test
    void mask_uppercasesALowercaseSurname() {
        assertEquals("Jan V.",
                OnboardingUploadService.maskDisplayName("Jan", "van den Berg"));
    }

    @Test
    void mask_handlesDanishLetters() {
        assertEquals("Anne Ø.",
                OnboardingUploadService.maskDisplayName("Anne", "Østergaard"));
    }

    @Test
    void mask_hyphenatedSurnameIsOneToken() {
        assertEquals("Mette B.",
                OnboardingUploadService.maskDisplayName("Mette", "Bjerre-Nielsen"));
    }

    // ── Degradation: never render a meaningless confirmation ──────────────

    @Test
    void mask_noSurname_returnsGivenNameAlone() {
        assertEquals("Henrik", OnboardingUploadService.maskDisplayName("Henrik", null));
        assertEquals("Henrik", OnboardingUploadService.maskDisplayName("Henrik", "   "));
    }

    @Test
    void mask_noGivenName_returnsSurnameTokenInFull() {
        // A bare "F." would tell the visitor nothing, so the fallback is the
        // one name we actually have.
        assertEquals("Faber", OnboardingUploadService.maskDisplayName(null, "Faber"));
    }

    @Test
    void mask_bothMissing_returnsNull() {
        assertNull(OnboardingUploadService.maskDisplayName(null, null));
        assertNull(OnboardingUploadService.maskDisplayName("", ""));
        assertNull(OnboardingUploadService.maskDisplayName("  ", "  "));
    }

    @Test
    void mask_nonLetterSurname_fallsBackToGivenName() {
        // Imported rows sometimes carry annotations rather than a name;
        // "Sofie (." is worse than "Sofie".
        assertEquals("Sofie", OnboardingUploadService.maskDisplayName("Sofie", "(opsagt)"));
    }

    // ── The silence rule ──────────────────────────────────────────────────

    @Test
    void invalidResponse_carriesNoName() {
        // An unknown token must stay indistinguishable from a known one.
        assertNull(OnboardingValidateResponse.ofInvalid().displayName());
    }

    @Test
    void expiredResponse_carriesNoName() {
        // Expiry already discloses that the token existed; it must not also
        // disclose whose it was.
        assertNull(OnboardingValidateResponse.ofExpired().displayName());
    }
}
