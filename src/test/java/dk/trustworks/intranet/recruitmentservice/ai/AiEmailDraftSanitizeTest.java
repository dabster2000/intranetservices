package dk.trustworks.intranet.recruitmentservice.ai;

import dk.trustworks.intranet.recruitmentservice.services.RecruitmentEmailRenderer;
import dk.trustworks.intranet.recruitmentservice.services.RecruitmentEmailService;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P16 unit contract for the draft-body handling: the model's output is
 * untrusted plain text — line endings normalised, control characters
 * stripped (newlines survive: an email body needs its paragraphs), length
 * capped at the send path's limit, blank treated as upstream failure.
 * Plus the leftover-placeholder scan the compose dialog's warning rides on.
 */
class AiEmailDraftSanitizeTest {

    @Test
    void sanitize_normalisesLineEndings_andKeepsNewlines() {
        assertEquals("Kære Anna,\n\nmed venlig hilsen",
                AiEmailDraftService.sanitizeBody("Kære Anna,\r\n\r\nmed venlig hilsen\r\n"));
    }

    @Test
    void sanitize_stripsControlCharacters_exceptNewline() {
        assertEquals("linje et\nlinje to", AiEmailDraftService.sanitizeBody(
                "linje\u0007et\nlinje\u200bto"));
    }

    @Test
    void sanitize_capsAtTheSendPathBodyLimit() {
        String over = "æ".repeat(RecruitmentEmailService.BODY_MAX_LENGTH + 500);
        String capped = AiEmailDraftService.sanitizeBody(over);
        assertTrue(capped.length() <= RecruitmentEmailService.BODY_MAX_LENGTH,
                "a draft must always fit through the manual-send contract");
    }

    @Test
    void sanitize_blankOrNull_isNull() {
        assertNull(AiEmailDraftService.sanitizeBody(null));
        assertNull(AiEmailDraftService.sanitizeBody("   \n \r\n "));
    }

    @Test
    void tokensIn_findsLeftoverPlaceholders_inAiText() {
        Set<String> tokens = RecruitmentEmailRenderer.tokensIn(
                "Kære Anna, dit tilbud på {{salary_offer}} gælder til {{ deadline }}.");
        assertEquals(Set.of("salary_offer", "deadline"), tokens);
        assertTrue(RecruitmentEmailRenderer.tokensIn("ingen pladsholdere her").isEmpty());
        assertTrue(RecruitmentEmailRenderer.tokensIn(null).isEmpty());
    }
}
