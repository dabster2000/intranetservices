package dk.trustworks.intranet.documentservice.services;

import dk.trustworks.intranet.documentservice.dto.TemplateDocumentDTO;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Per-company narrowing of a template's documents.
 * <p>
 * The fixture is the real Ansættelseskontrakter triplet as it stands in
 * production: each company has its own contract file <em>and</em> its own
 * loyalty-programme appendix — the appendices are three different programmes,
 * not one text with the name swapped, which is why they cannot collapse into a
 * single {@code {{COMPANY_*}}}-tagged document. Merging the three templates
 * means all six files live on one template, and only this filter keeps them
 * apart.
 */
class TemplateServiceDocumentCompanyTest {

    private static final String TW = "d8894494-2fb4-4f72-9e05-e6032e6dd691";
    private static final String TWC = "e4b0a2a4-0963-4153-b0a2-a409637153a2";
    private static final String TWT = "44592d3b-2be5-4b29-bfaf-4fafc60b0fa3";

    private static TemplateDocumentDTO doc(String name, String companyUuid, int order) {
        return TemplateDocumentDTO.builder()
                .documentName(name).companyUuid(companyUuid)
                .fileUuid("file-" + name).displayOrder(order).build();
    }

    /** All six documents on one merged template. */
    private static List<TemplateDocumentDTO> mergedTemplate() {
        return List.of(
                doc("Ansættelseskontrakt", TW, 1),
                doc("Ansættelsesaftale", TWC, 1),
                doc("Ansættelseskontrakt (Technology)", TWT, 1),
                doc("Din del af Trustworks", TW, 2),
                doc("Din del af Tw Cyber Security", TWC, 2),
                doc("Din del af Trustworks Technology", TWT, 2));
    }

    private static List<String> namesFor(List<TemplateDocumentDTO> docs, String companyUuid) {
        return TemplateService.documentsForCompany(docs, companyUuid)
                .stream().map(TemplateDocumentDTO::getDocumentName).toList();
    }

    @Test
    void eachCompanyGetsExactlyItsOwnTwoDocuments() {
        assertEquals(List.of("Ansættelseskontrakt", "Din del af Trustworks"),
                namesFor(mergedTemplate(), TW));
        assertEquals(List.of("Ansættelsesaftale", "Din del af Tw Cyber Security"),
                namesFor(mergedTemplate(), TWC));
        assertEquals(List.of("Ansættelseskontrakt (Technology)", "Din del af Trustworks Technology"),
                namesFor(mergedTemplate(), TWT));
    }

    /** The whole point: no company ever receives another's contract. */
    @Test
    void oneCompanyNeverReceivesAnothersContract() {
        assertTrue(namesFor(mergedTemplate(), TW).stream()
                .noneMatch(n -> n.contains("Cyber") || n.contains("Technology")));
        assertTrue(namesFor(mergedTemplate(), TWT).stream()
                .noneMatch(n -> n.equals("Ansættelsesaftale") || n.equals("Din del af Trustworks")));
    }

    /**
     * The intended end state: one shared contract using {@code {{COMPANY_*}}}
     * tags, plus a company-specific appendix each. Everyone gets two documents.
     */
    @Test
    void aSharedContractMixesWithCompanySpecificAppendices() {
        List<TemplateDocumentDTO> mixed = List.of(
                doc("Ansættelseskontrakt", null, 1),
                doc("Din del af Trustworks", TW, 2),
                doc("Din del af Tw Cyber Security", TWC, 2),
                doc("Din del af Trustworks Technology", TWT, 2));

        assertEquals(List.of("Ansættelseskontrakt", "Din del af Trustworks"), namesFor(mixed, TW));
        assertEquals(List.of("Ansættelseskontrakt", "Din del af Tw Cyber Security"), namesFor(mixed, TWC));
        assertEquals(List.of("Ansættelseskontrakt", "Din del af Trustworks Technology"), namesFor(mixed, TWT));
    }

    /**
     * An underivable company must not widen to "every document" — that would
     * put all three companies' contracts in one envelope.
     */
    @Test
    void unknownCompanyKeepsOnlyTheSharedDocuments() {
        assertEquals(List.of(), namesFor(mergedTemplate(), null));
        assertEquals(List.of("Ansættelseskontrakt"), namesFor(List.of(
                doc("Ansættelseskontrakt", null, 1),
                doc("Din del af Trustworks", TW, 2)), null));
    }

    /** Today's un-merged templates carry no company, so nothing changes. */
    @Test
    void templatesWithoutCompanyScopingAreUnaffected() {
        List<TemplateDocumentDTO> legacy = List.of(
                doc("Ansættelseskontrakt", null, 1),
                doc("Din del af Trustworks", null, 2));
        assertEquals(2, TemplateService.documentsForCompany(legacy, TW).size());
        assertEquals(2, TemplateService.documentsForCompany(legacy, null).size());
    }
}
