package dk.trustworks.intranet.recruitmentservice.services;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The P19 consent-token primitives: 32 bytes of {@code SecureRandom} as
 * 43-char base64url, stored only as SHA-256 hex (fits the
 * {@code token_hash VARCHAR(64)} column exactly).
 */
class RecruitmentConsentTokenTest {

    @Test
    void generatedTokens_are43CharsOfBase64Url() {
        for (int i = 0; i < 100; i++) {
            String token = RecruitmentConsentService.generateToken();
            assertEquals(43, token.length());
            assertTrue(token.matches("[A-Za-z0-9_-]{43}"),
                    "base64url without padding — URL-safe by construction: " + token);
        }
    }

    @Test
    void generatedTokens_doNotRepeat() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1_000; i++) {
            assertTrue(seen.add(RecruitmentConsentService.generateToken()),
                    "256-bit tokens must never collide in practice");
        }
    }

    @Test
    void hash_is64HexChars_andDeterministic() {
        String token = RecruitmentConsentService.generateToken();
        String hash = RecruitmentConsentService.sha256Hex(token);
        assertEquals(64, hash.length(), "SHA-256 hex fills token_hash VARCHAR(64) exactly");
        assertTrue(hash.matches("[0-9a-f]{64}"));
        assertEquals(hash, RecruitmentConsentService.sha256Hex(token));
        assertNotEquals(hash, RecruitmentConsentService.sha256Hex(
                RecruitmentConsentService.generateToken()));
    }
}
