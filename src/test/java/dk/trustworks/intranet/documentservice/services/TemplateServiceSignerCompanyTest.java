package dk.trustworks.intranet.documentservice.services;

import dk.trustworks.intranet.documentservice.dto.TemplateDefaultSignerDTO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The per-company narrowing of a template's default signers.
 * <p>
 * The fixture is the real Ansættelseskontrakter triplet as it stands in
 * production: the first counter-signer differs per company (and carries a
 * different role label), the second is the same person everywhere, the
 * recipient is a token, and TWT alone copies HR without a signature. Merging
 * the three per-company templates into one means all of those rows live on a
 * single template, and only the company filter keeps them apart.
 */
class TemplateServiceSignerCompanyTest {

    private static final String TW = "d8894494-2fb4-4f72-9e05-e6032e6dd691";
    private static final String TWC = "e4b0a2a4-0963-4153-b0a2-a409637153a2";
    private static final String TWT = "44592d3b-2be5-4b29-bfaf-4fafc60b0fa3";

    private static TemplateDefaultSignerDTO signer(String name, String role, String companyUuid, int group) {
        return TemplateDefaultSignerDTO.builder()
                .name(name).role(role).companyUuid(companyUuid)
                .signerGroup(group).signing(true).build();
    }

    /** The merged template: every company's counter-signers on one template. */
    private static List<TemplateDefaultSignerDTO> mergedTemplate() {
        return List.of(
                signer("Thomas Gammelvind", "CEO & Managing Partner", TW, 1),
                signer("Lars Albert Ahnfeldt Beck Thomsen", "CEO & Managing Partner", TWC, 1),
                signer("Thomas Buchholdt", "Managing Director", TWT, 1),
                signer("Hans Ernst Lassen", "COO & Managing Partner", null, 1),
                signer("${EMPLOYEE_NAME}", null, null, 2),
                TemplateDefaultSignerDTO.builder()
                        .name("Marie Myssing").role("HR").companyUuid(TWT)
                        .signerGroup(3).signing(false).build());
    }

    private static List<String> namesFor(String companyUuid) {
        return TemplateService.signersForCompany(mergedTemplate(), companyUuid)
                .stream().map(TemplateDefaultSignerDTO::getName).toList();
    }

    @Test
    void eachCompanyGetsItsOwnCounterSignerPlusTheSharedOnes() {
        assertEquals(List.of("Thomas Gammelvind", "Hans Ernst Lassen", "${EMPLOYEE_NAME}"), namesFor(TW));
        assertEquals(List.of("Lars Albert Ahnfeldt Beck Thomsen", "Hans Ernst Lassen", "${EMPLOYEE_NAME}"),
                namesFor(TWC));
    }

    /** TWT keeps its Managing Director and its non-signing HR copy. */
    @Test
    void companySpecificRoleAndNonSigningRecipientSurvive() {
        assertEquals(List.of("Thomas Buchholdt", "Hans Ernst Lassen", "${EMPLOYEE_NAME}", "Marie Myssing"),
                namesFor(TWT));

        List<TemplateDefaultSignerDTO> twt = TemplateService.signersForCompany(mergedTemplate(), TWT);
        assertEquals("Managing Director", twt.get(0).getRole());
        assertTrue(twt.stream().anyMatch(s -> "Marie Myssing".equals(s.getName()) && !s.isSigning()),
                "TWT's HR copy must survive as a non-signing recipient");
    }

    /** The point of the whole change: no company ever sees another's executives. */
    @Test
    void oneCompanyNeverSeesAnothersCounterSigner() {
        assertTrue(namesFor(TW).stream().noneMatch(n -> n.contains("Buchholdt") || n.contains("Lars Albert")));
        assertTrue(namesFor(TWT).stream().noneMatch(n -> n.contains("Gammelvind") || n.contains("Lars Albert")));
    }

    /**
     * No derivable company must not degrade to "everyone" — that is precisely
     * how a contract would go out countersigned by the wrong legal entity.
     */
    @Test
    void unknownCompanyKeepsOnlyTheCompanyAgnosticSigners() {
        assertEquals(List.of("Hans Ernst Lassen", "${EMPLOYEE_NAME}"), namesFor(null));
    }

    /** Today's un-merged templates carry no company, so nothing changes for them. */
    @Test
    void templatesWithoutAnyCompanyScopingAreUnaffected() {
        List<TemplateDefaultSignerDTO> legacy = List.of(
                signer("Thomas Gammelvind", "CEO & Managing Partner", null, 1),
                signer("Hans Ernst Lassen", "COO & Managing Partner", null, 1),
                signer("${EMPLOYEE_NAME}", null, null, 2));
        assertEquals(3, TemplateService.signersForCompany(legacy, TW).size());
        assertEquals(3, TemplateService.signersForCompany(legacy, null).size());
    }

    @Test
    void blankCompanyMeansAlleSelskaber() {
        assertNull(TemplateService.normalizeCompanyUuid(""));
        assertNull(TemplateService.normalizeCompanyUuid("   "));
        assertNull(TemplateService.normalizeCompanyUuid(null));
        assertEquals(TW, TemplateService.normalizeCompanyUuid(TW));
    }
}
