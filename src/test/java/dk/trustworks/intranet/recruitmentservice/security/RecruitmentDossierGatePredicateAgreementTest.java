package dk.trustworks.intranet.recruitmentservice.security;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import dk.trustworks.intranet.recruitmentservice.dto.CandidateRequest;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentPosition;
import dk.trustworks.intranet.recruitmentservice.model.enums.CandidateSource;
import dk.trustworks.intranet.recruitmentservice.resources.RecruitmentResource;
import dk.trustworks.intranet.recruitmentservice.services.CandidateService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The A3 dossier gate is only worth anything if the resource and the service
 * answer <em>"is a template present?"</em> identically.
 *
 * <h3>The bug this class pins shut</h3>
 * They used to answer it with two different predicates:
 * <ul>
 *   <li>{@code RecruitmentResource.createCandidate}:
 *       {@code trimToNull(request.templateUuid()) != null}, i.e.
 *       {@code !value.trim().isEmpty()} — and {@link String#trim()} strips
 *       <b>every</b> character at or below U+0020, control characters
 *       included.</li>
 *   <li>{@code CandidateService.createCandidate}:
 *       {@code != null && !isBlank()} — and {@link String#isBlank()} strips
 *       only {@link Character#isWhitespace}, which U+0001 is not.</li>
 * </ul>
 * So a {@code templateUuid} made of control characters was <em>absent</em> to
 * the gate and <em>present</em> to the service: an intake-only caller
 * (TEAMLEAD holding {@code recruitment:intake} but not recruiter tier) passed
 * the 403 and still had a {@code CandidateDossier} — the offer/contract
 * surface go-live decision D17 denies them — opened in their name.
 * {@code @Size(min = 36)} did not save it: bean validation is inert in this
 * backend.
 *
 * <p>The fix is structural rather than a second patch: ONE predicate,
 * {@link CandidateRequest#opensDossier()}, called from both sides. These
 * tests assert both halves of that — what the predicate answers, and that
 * both call sites actually call it.</p>
 */
class RecruitmentDossierGatePredicateAgreementTest {

    private static final JavaClasses CLASSES = new ClassFileImporter().importClasses(
            RecruitmentResource.class, CandidateService.class);

    /**
     * Values that separated the two old predicates, plus the ordinary ones.
     * Each entry is (templateUuid, "does this open the dossier?").
     */
    private static List<Object[]> cases() {
        return List.of(
                new Object[]{null, false},
                new Object[]{"", false},
                new Object[]{" ", false},
                new Object[]{"\t\n ", false},
                // U+0001 START OF HEADING. trim() strips it (it is at or below
                // U+0020); isBlank() does not (it is not isWhitespace). THIS is
                // the value that walked past the gate.
                new Object[]{ch(0x01), true},
                new Object[]{ch(0x01) + ch(0x02), true},
                // U+001F UNIT SEPARATOR and U+000B VERTICAL TAB: Java's
                // isWhitespace DOES include U+001C..U+001F and U+000B, so both
                // predicates always agreed these were blank. Pinned to keep the
                // boundary honest — the bypass is U+0000..U+000A / U+000E..U+001B,
                // not "control characters" as a category.
                new Object[]{ch(0x1f) + ch(0x0b), false},
                // U+00A0 NO-BREAK SPACE: neither predicate ever stripped it, so
                // both always agreed it was a template. Pinned so the fix does
                // not quietly start dropping it.
                new Object[]{ch(0xa0), true},
                // U+2028 LINE SEPARATOR: here the disagreement ran the OTHER
                // way (trim keeps it, isBlank strips it), so the old gate fired
                // and the old service opened nothing — noisy, never unsafe.
                new Object[]{ch(0x2028), false},
                new Object[]{"not-a-uuid", true},
                new Object[]{"550e8400-e29b-41d4-a716-446655440000", true},
                new Object[]{"  550e8400-e29b-41d4-a716-446655440000  ", true});
    }

    // ---- The predicate itself ------------------------------------------------------

    @Test
    void oneSharedPredicateAnswersForBothSides() {
        for (Object[] testCase : cases()) {
            String templateUuid = (String) testCase[0];
            boolean expected = (boolean) testCase[1];

            assertEquals(expected, request(templateUuid).opensDossier(),
                    "opensDossier() disagreed for templateUuid=" + render(templateUuid));
        }
    }

    /**
     * Control characters must count as PRESENT — fail-closed. A gate that
     * read junk as "no template" would be the same bypass spelled differently.
     */
    @Test
    void junkCountsAsATemplate_soTheGateFiresRatherThanTheDossierOpening() {
        assertTrue(request(ch(0x01)).opensDossier(),
                "a non-whitespace value is a supplied template, however malformed — the "
                        + "intake-only caller must get a 403, not a dossier");
        assertFalse(request("   ").opensDossier(),
                "genuinely empty input is the ordinary ATS path, not a denied dossier attempt");
    }

    /**
     * The regression itself, spelled out: the two predicates that used to sit
     * on either side of the gate really do disagree. If this ever starts
     * failing, {@link String#trim()} and {@link String#isBlank()} have become
     * equivalent — until then the shared predicate is the only thing holding
     * the gate shut.
     */
    @Test
    void theTwoOldPredicatesDisagree_whichIsWhatMadeTheGateBypassable() {
        String controlChars = ch(0x01) + ch(0x02);

        boolean oldResourceSawATemplate = oldResourcePredicate(controlChars);
        boolean oldServiceSawATemplate = oldServicePredicate(controlChars);

        assertFalse(oldResourceSawATemplate, "trim() strips control characters");
        assertTrue(oldServiceSawATemplate, "isBlank() does not");
        assertNotEquals(oldResourceSawATemplate, oldServiceSawATemplate,
                "the gate said 'no template, let them through' while the service said "
                        + "'template present, open the dossier'");
        assertTrue(request(controlChars).opensDossier(),
                "and the one predicate that replaced them answers PRESENT, so the gate fires");
    }

    // ---- Both call sites use it ------------------------------------------------------

    @Test
    void theResourceGateCallsTheSharedPredicate() {
        Set<String> callees = calleesOf(RecruitmentResource.class, "createCandidate",
                CandidateRequest.class);

        assertTrue(callees.contains("CandidateRequest#opensDossier"),
                "the A3 gate must ask CandidateRequest rather than re-derive 'is a template "
                        + "present'; actual callees: " + callees);
    }

    @Test
    void theServiceBranchCallsTheSameSharedPredicate() {
        Set<String> callees = calleesOf(CandidateService.class, "createCandidate",
                CandidateRequest.class, UUID.class, RecruitmentPosition.class);

        assertTrue(callees.contains("CandidateRequest#opensDossier"),
                "the service must branch on the same predicate the gate used; "
                        + "actual callees: " + callees);
        assertFalse(callees.contains("String#isBlank"),
                "a String#isBlank here is a second, private answer to the gate's question — "
                        + "the exact drift that opened the bypass; actual callees: " + callees);
    }

    // ---- Helpers ---------------------------------------------------------------------

    /** The two predicates as they stood before the fix, for the regression above. */
    private static boolean oldResourcePredicate(String templateUuid) {
        if (templateUuid == null) {
            return false;
        }
        return !templateUuid.trim().isEmpty();
    }

    private static boolean oldServicePredicate(String templateUuid) {
        return templateUuid != null && !templateUuid.isBlank();
    }

    private static CandidateRequest request(String templateUuid) {
        return new CandidateRequest(
                "Ada", "Lovelace", null, null, null, null, null, null,
                templateUuid,
                CandidateSource.OTHER,
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null);
    }

    /** One character by code point — control characters stay readable in source. */
    private static String ch(int codePoint) {
        return new String(Character.toChars(codePoint));
    }

    private static String render(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder("\"");
        value.codePoints().forEach(cp -> out.append(cp < 0x20 || cp > 0x7e
                ? String.format("\\u%04x", cp)
                : String.valueOf((char) cp)));
        return out.append('"').toString();
    }

    /** Every method this method calls, as {@code SimpleOwner#name}. */
    private static Set<String> calleesOf(Class<?> owner, String methodName, Class<?>... params) {
        JavaMethod method = CLASSES.get(owner).getMethod(methodName, params);
        return method.getMethodCallsFromSelf().stream()
                .map(JavaMethodCall::getTarget)
                .map(target -> target.getOwner().getSimpleName() + "#" + target.getName())
                .collect(Collectors.toSet());
    }
}
