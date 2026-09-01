package dk.trustworks.intranet.agreementservice.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dk.trustworks.intranet.agreementservice.services.AgreementExtractionService.Proposal;
import dk.trustworks.intranet.documentservice.model.EmployeeDocument;
import dk.trustworks.intranet.documentservice.model.enums.EmployeeDocumentCategory;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DB-free tests for the Phase-4 backfill core logic (template-clauses
 * spec §10): the walker's file/folder filters (which decide what ever
 * reaches a download or an AI call), the strict extraction schema, and
 * the defensive parsing of model responses.
 */
class AgreementBackfillCoreTest {

    private static final List<String> TYPE_KEYS = List.of(
            "GARANTIBONUS", "PROEVETID_FRAVIGET", "ANCIENNITET", "OPSIGELSESVARSEL",
            "SAERLIGE_VILKAAR", "LOYALITETSPROGRAM", "INDIVIDUEL");

    // Mirrors the Quarkus-injected mapper: JSR-310 registered, dates as
    // ISO strings — the shape proposal_json is actually stored in.
    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules()
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private AgreementExtractionService extractionService() {
        AgreementExtractionService service = new AgreementExtractionService();
        service.objectMapper = objectMapper;
        return service;
    }

    // ---- Corpus filter (what gets fetched from S3 at all) ----------------------

    private static EmployeeDocument doc(String filename, String contentType, long size,
                                        boolean archived) {
        EmployeeDocument doc = new EmployeeDocument();
        doc.setUuid("doc-1");
        doc.setUserUuid("user-1");
        doc.setS3Key("employee/user-1/doc-1");
        doc.setCategory(EmployeeDocumentCategory.CONTRACT);
        doc.setOriginalFilename(filename);
        doc.setContentType(contentType);
        doc.setFileSizeBytes(size);
        doc.setArchived(archived);
        return doc;
    }

    @Test
    void corpusDocument_pdfByContentTypeOrExtension() {
        assertTrue(AgreementBackfillWalkerService.isCorpusDocument(
                doc("Ansættelsesaftale.pdf", "application/octet-stream", 100_000, false)));
        assertTrue(AgreementBackfillWalkerService.isCorpusDocument(
                doc("Tillæg 2021.PDF", "application/octet-stream", 100_000, false)));
        assertTrue(AgreementBackfillWalkerService.isCorpusDocument(
                doc("misnamed.dat", "application/pdf", 100_000, false)));
    }

    @Test
    void corpusDocument_rejectsNonPdfArchivedZeroByteAndOversize() {
        assertFalse(AgreementBackfillWalkerService.isCorpusDocument(doc("CV.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", 100_000, false)));
        // Archived replaces the SharePoint "Arkiv" folders — out of corpus.
        assertFalse(AgreementBackfillWalkerService.isCorpusDocument(
                doc("kontrakt.pdf", "application/pdf", 100_000, true)));
        // Zero-byte files are the false-duplicate trap from the migration.
        assertFalse(AgreementBackfillWalkerService.isCorpusDocument(
                doc("empty.pdf", "application/pdf", 0, false)));
        assertFalse(AgreementBackfillWalkerService.isCorpusDocument(
                doc("huge.pdf", "application/pdf", AgreementBackfillWalkerService.MAX_FILE_BYTES + 1, false)));
    }

    // ---- Category config (data minimization) -----------------------------------

    @Test
    void parseCategories_defaultExcludesHealthAndIdentity() {
        var categories = AgreementBackfillWalkerService.parseCategories("CONTRACT,ADDENDUM,DECLARATION");
        assertEquals(java.util.Set.of("CONTRACT", "ADDENDUM", "DECLARATION"), categories);
        // SICKNESS/IDENTITY must never reach the AI call by default
        // (GDPR special-category data minimization).
        assertFalse(categories.contains("SICKNESS"));
        assertFalse(categories.contains("IDENTITY"));
    }

    @Test
    void parseCategories_toleratesSpacingCaseAndBlanks() {
        assertEquals(java.util.Set.of("CONTRACT", "OTHER"),
                AgreementBackfillWalkerService.parseCategories(" contract , OTHER ,, "));
        assertTrue(AgreementBackfillWalkerService.parseCategories("").isEmpty());
        assertTrue(AgreementBackfillWalkerService.parseCategories(null).isEmpty());
    }

    // ---- Hashing ---------------------------------------------------------------

    @Test
    void sha256Hex_isStableLowercaseHex() {
        String hash = AgreementBackfillWalkerService.sha256Hex("hello".getBytes());
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", hash);
        assertEquals(64, hash.length());
    }

    // ---- Extraction schema (OpenAI strict mode) --------------------------------

    @Test
    void schema_isStrictAndEnumeratesTypeKeys() {
        ObjectNode schema = extractionService().buildSchema(TYPE_KEYS);
        assertEquals("object", schema.get("type").asText());
        assertFalse(schema.get("additionalProperties").asBoolean());

        JsonNode item = schema.get("properties").get("proposals").get("items");
        assertFalse(item.get("additionalProperties").asBoolean());
        // Strict mode: every property must be required (nullable ones use
        // a ["type","null"] union) — a mismatch is an HTTP 400 at call time.
        assertEquals(item.get("properties").size(), item.get("required").size());

        JsonNode typeEnum = item.get("properties").get("agreement_type").get("enum");
        assertEquals(TYPE_KEYS.size(), typeEnum.size());
        assertEquals("GARANTIBONUS", typeEnum.get(0).asText());
    }

    // ---- Response parsing ------------------------------------------------------

    @Test
    void parseProposals_fullProposal() {
        String response = """
                {"proposals":[{"agreement_type":"GARANTIBONUS","title":"Garantibonus FY24/25",
                "summary":"Garanteret bonus på 60.000 kr.","amount":60000,"currency":"DKK",
                "valid_from":"2024-07-01","valid_to":"2025-06-30","effective_date":null,
                "verbatim_quote":"Medarbejderen er garanteret en bonus på kr. 60.000",
                "confidence":0.92}]}""";
        List<Proposal> proposals = extractionService().parseProposals(response, TYPE_KEYS);
        assertEquals(1, proposals.size());
        Proposal proposal = proposals.get(0);
        assertEquals("GARANTIBONUS", proposal.agreementType());
        assertEquals(new BigDecimal("60000"), proposal.amount());
        assertEquals("DKK", proposal.currency());
        assertEquals(LocalDate.of(2024, 7, 1), proposal.validFrom());
        assertEquals(LocalDate.of(2025, 6, 30), proposal.validTo());
        assertNull(proposal.effectiveDate());
        assertEquals(0.92, proposal.confidence(), 0.0001);
    }

    @Test
    void parseProposals_errorSentinelIsNullNotEmpty() {
        // "{}" is OpenAIService's error sentinel — it must read as
        // "extraction failed", never as "document holds no agreements".
        assertNull(extractionService().parseProposals("{}", TYPE_KEYS));
        assertNull(extractionService().parseProposals("not json", TYPE_KEYS));
        assertNull(extractionService().parseProposals(null, TYPE_KEYS));
    }

    @Test
    void parseProposals_emptyArrayIsGenuineNoProposals() {
        List<Proposal> proposals = extractionService().parseProposals("{\"proposals\":[]}", TYPE_KEYS);
        assertTrue(proposals.isEmpty());
    }

    @Test
    void parseProposals_defensiveDegradation() {
        String response = """
                {"proposals":[
                  {"agreement_type":"UKENDT_TYPE","title":"Noget særligt","summary":null,
                   "amount":"60.000 kr.","currency":"kroner","valid_from":"01.07.2024",
                   "valid_to":"garbage","effective_date":null,"verbatim_quote":"…","confidence":7},
                  {"agreement_type":"ANCIENNITET","title":"  ","summary":null,"amount":null,
                   "currency":null,"valid_from":null,"valid_to":null,"effective_date":null,
                   "verbatim_quote":"…","confidence":0.5}
                ]}""";
        List<Proposal> proposals = extractionService().parseProposals(response, TYPE_KEYS);
        // The blank-title proposal is dropped; the odd one survives, degraded.
        assertEquals(1, proposals.size());
        Proposal proposal = proposals.get(0);
        assertEquals("INDIVIDUEL", proposal.agreementType());
        assertEquals(new BigDecimal("60000"), proposal.amount());
        assertNull(proposal.currency());
        assertEquals(LocalDate.of(2024, 7, 1), proposal.validFrom());
        assertNull(proposal.validTo());
        assertEquals(1.0, proposal.confidence(), 0.0001);
    }

    @Test
    void proposals_roundTripThroughJson() throws Exception {
        // The walker persists proposals with ObjectMapper and the review
        // service reads them back — the record must survive the trip.
        Proposal original = new Proposal("OPSIGELSESVARSEL", "Forlænget opsigelsesvarsel",
                "6 måneders varsel", null, null, LocalDate.of(2022, 1, 1), null, null,
                "opsigelsesvarslet udgør 6 måneder", 0.8);
        String json = objectMapper.writeValueAsString(List.of(original));
        List<Proposal> back = objectMapper.readValue(json,
                new com.fasterxml.jackson.core.type.TypeReference<List<Proposal>>() {
                });
        assertEquals(List.of(original), back);
    }
}
