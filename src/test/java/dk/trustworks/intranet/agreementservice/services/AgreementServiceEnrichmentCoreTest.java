package dk.trustworks.intranet.agreementservice.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.aggregates.users.services.StatusService;
import dk.trustworks.intranet.agreementservice.dto.AgreementDTO;
import dk.trustworks.intranet.agreementservice.model.AgreementType;
import dk.trustworks.intranet.agreementservice.model.EmployeeAgreement;
import dk.trustworks.intranet.documentservice.model.TemplateClauseEntity;
import dk.trustworks.intranet.domain.user.entity.UserStatus;
import dk.trustworks.intranet.model.Company;
import dk.trustworks.intranet.recruitmentservice.model.RecruitmentCandidate;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The enrichment step {@code /agreements} runs for every row it returns.
 *
 * <p>These pin the <em>non-empty</em> path. Until 2026-09-06 nothing did, and
 * that is precisely why the outage shipped: a user with no agreements gets an
 * empty list and {@code toDTO} never runs, so every existing test passed while
 * every employee who actually had an agreement got a 500.</p>
 *
 * <p><b>Limits, stated plainly.</b> {@code find(...)} itself cannot be called
 * here. It opens with {@code EmployeeAgreement.list(...)}, and the four
 * enrichment lookups are {@code findById}/{@code findByIdOptional} — all
 * inherited from {@code PanacheEntityBase}, so {@code mockStatic} intercepts
 * nothing (Mockito resolves static mocks on the <em>declaring</em> class) and
 * none of them can reach a database in the fast tier, which is the CI deploy
 * gate. So these tests seed {@link AgreementService.EnrichmentCache} and drive
 * {@code toDTO} directly: everything {@code find} does per row after the query
 * runs, with the query stubbed out.</p>
 *
 * <p>That covers the mapping. It does <em>not</em> catch the defect that caused
 * the outage — a method reference to a Panache static compiles, and in a test
 * JVM with no build-time enhancement the direct call fails exactly like the
 * method reference does, so no DB-free test can tell the two apart by running
 * them. {@code PanacheStaticMethodReferenceGuardTest} is the guard for that,
 * and it is structural for this reason.</p>
 */
class AgreementServiceEnrichmentCoreTest {

    private static final String USER = "d0efe516-651e-4755-b98f-f8ff0209a8d4";
    private static final String CANDIDATE = "9c1e77a2-4c65-4a3f-8f2c-c0b6b6ae5f30";
    private static final String CLAUSE = "0b4a5cd1-2a2c-4d0f-9a54-0f5c2f1a7c11";
    private static final String COMPANY = "6e0d9f43-79c9-4a7f-8b0a-3d0b2a5cf102";

    /**
     * The shape the HR Agreements page actually consumes: a row belonging to an
     * employee, rendered with the type's Danish display name, the subject's name
     * and company, the clause name, and the parsed parameters.
     */
    @Test
    void anEmployeeRowRendersFullyEnriched() {
        AgreementService service = service();
        AgreementService.EnrichmentCache cache = service.new EnrichmentCache();

        cache.types.put("BONUS", type("BONUS", "Bonusaftale"));
        cache.userNames.put(USER, "Hans Ernst Lassen");
        cache.userCompanies.put(USER, company(COMPANY, "Trustworks A/S"));
        cache.clauses.put(CLAUSE, clause("Bonusklausul 2026"));

        EmployeeAgreement row = new EmployeeAgreement();
        row.setUuid("a1");
        row.setUserUuid(USER);
        row.setAgreementType("BONUS");
        row.setTitle("Bonusaftale 2026");
        row.setSummary("10% af dækningsbidrag");
        row.setAmount(new BigDecimal("125000.00"));
        row.setCurrency("DKK");
        row.setValidFrom(LocalDate.of(2026, 7, 1));
        row.setValidTo(LocalDate.of(2027, 6, 30));
        row.setEffectiveDate(LocalDate.of(2026, 7, 1));
        row.setParametersJson("{\"pct\":\"10\",\"basis\":\"DB\"}");
        row.setClauseUuid(CLAUSE);
        row.setSource("SIGNED_CASE");
        row.setSigningCaseKey("case-77");
        row.setDocumentUrl("https://docs.example/bonus.pdf");
        row.setStatus("ACTIVE");
        row.setCreatedAt(LocalDateTime.of(2026, 7, 1, 9, 0));
        row.setCreatedBy("hr-1");

        AgreementDTO dto = service.toDTO(row, cache);

        assertEquals("a1", dto.getUuid());
        assertEquals("USER", dto.getSubjectType());
        assertEquals(USER, dto.getUserUuid());
        assertEquals("Hans Ernst Lassen", dto.getSubjectName());
        assertEquals(COMPANY, dto.getCompanyUuid());
        assertEquals("Trustworks A/S", dto.getCompanyName());
        assertEquals("BONUS", dto.getAgreementType());
        assertEquals("Bonusaftale", dto.getAgreementTypeName());
        assertEquals("Bonusaftale 2026", dto.getTitle());
        assertEquals(new BigDecimal("125000.00"), dto.getAmount());
        assertEquals("DKK", dto.getCurrency());
        assertEquals(LocalDate.of(2027, 6, 30), dto.getValidTo());
        assertEquals("Bonusklausul 2026", dto.getClauseName());
        assertEquals("https://docs.example/bonus.pdf", dto.getDocumentUrl());
        assertEquals("ACTIVE", dto.getStatus());
        assertEquals(2, dto.getParameters().size());
        assertEquals("10", dto.getParameters().get("pct"));
        assertEquals("DB", dto.getParameters().get("basis"));
    }

    /**
     * The other side of the XOR. {@code targetCompanyUuid} is left null on
     * purpose: {@code toDTO} resolves a candidate's company with a direct
     * {@code Company.findById}, not through the cache, so a non-null value
     * would need a database.
     */
    @Test
    void aCandidateRowRendersWithTheCandidateAsSubject() {
        AgreementService service = service();
        AgreementService.EnrichmentCache cache = service.new EnrichmentCache();

        cache.types.put("ANSAETTELSE", type("ANSAETTELSE", "Ansættelsesaftale"));
        cache.candidates.put(CANDIDATE, candidate("Mette", "Jensen"));

        EmployeeAgreement row = new EmployeeAgreement();
        row.setUuid("a2");
        row.setCandidateUuid(CANDIDATE);
        row.setAgreementType("ANSAETTELSE");
        row.setTitle("Ansættelseskontrakt");
        row.setSource("SIGNED_CASE");
        row.setStatus("ACTIVE");

        AgreementDTO dto = service.toDTO(row, cache);

        assertEquals("CANDIDATE", dto.getSubjectType());
        assertEquals("Mette Jensen", dto.getSubjectName());
        assertEquals(CANDIDATE, dto.getCandidateUuid());
        assertEquals("Ansættelsesaftale", dto.getAgreementTypeName());
        assertNull(dto.getUserUuid());
        assertNull(dto.getCompanyName(), "no target company on the candidate, so none on the DTO");
    }

    /**
     * An unknown type key must not blank the column — the row still has to
     * render, falling back to the raw key.
     *
     * <p>The misses are seeded directly as nulls, which is only possible because
     * {@link AgreementService.EnrichmentCache} memoizes negatives: a cached null
     * is a resolved negative, not an empty slot. Under the old
     * {@code computeIfAbsent} shape a seeded null read as an absent key and the
     * lookup fell through to the real Panache static, so this test had to
     * override the three lookups instead.</p>
     *
     * <p>That makes the seeding load-bearing rather than cosmetic: if negatives
     * ever stop being memoized, these lookups reach an inherited Panache static
     * with no database behind it and the test fails loudly.</p>
     */
    @Test
    void anUnknownTypeFallsBackToTheRawKeyAndParametersDefaultToEmpty() {
        AgreementService service = service();
        AgreementService.EnrichmentCache cache = service.new EnrichmentCache();

        cache.types.put("MYSTERY", null);
        cache.userNames.put(USER, "Hans Ernst Lassen");
        cache.userCompanies.put(USER, null);

        EmployeeAgreement row = new EmployeeAgreement();
        row.setUuid("a3");
        row.setUserUuid(USER);
        row.setAgreementType("MYSTERY");
        row.setTitle("Individuel aftale");
        row.setParametersJson("not json at all");
        row.setSource("MANUAL");
        row.setStatus("ACTIVE");

        AgreementDTO dto = service.toDTO(row, cache);

        assertEquals("MYSTERY", dto.getAgreementTypeName(), "falls back to the raw key");
        assertEquals("Hans Ernst Lassen", dto.getSubjectName());
        assertNull(dto.getCompanyName(), "no resolvable company leaves the column empty");
        assertNull(dto.getCompanyUuid());
        assertTrue(dto.getParameters().isEmpty(), "unparseable parameters_json degrades to an empty map");
        assertNull(dto.getDocumentUrl(), "no document_url on the row");
    }

    /**
     * The memo is what makes a list render cheap: one lookup per distinct key
     * however many rows reference it. A miss would call an inherited Panache
     * static and fail here, so a second call that does not fail is the
     * assertion.
     */
    @Test
    void theCacheResolvesEachKeyOnceAcrossRows() {
        AgreementService service = service();
        AgreementService.EnrichmentCache cache = service.new EnrichmentCache();
        cache.types.put("BONUS", type("BONUS", "Bonusaftale"));
        cache.userNames.put(USER, "Hans Ernst Lassen");
        cache.userCompanies.put(USER, company(COMPANY, "Trustworks A/S"));

        EmployeeAgreement first = new EmployeeAgreement();
        first.setUuid("a4");
        first.setUserUuid(USER);
        first.setAgreementType("BONUS");
        first.setStatus("ACTIVE");

        EmployeeAgreement second = new EmployeeAgreement();
        second.setUuid("a5");
        second.setUserUuid(USER);
        second.setAgreementType("BONUS");
        second.setStatus("EXPIRED");

        assertEquals("Bonusaftale", service.toDTO(first, cache).getAgreementTypeName());
        assertEquals("Bonusaftale", service.toDTO(second, cache).getAgreementTypeName());
        assertEquals(1, cache.types.size());
        assertEquals(1, cache.userNames.size());
        assertEquals(1, cache.userCompanies.size());
    }

    /**
     * The degraded path pays the same one-lookup-per-key price as the happy one.
     *
     * <p>A key that resolves to nothing is still resolved <em>once</em>: the
     * negative is memoized, and every later row referencing it is served from the
     * memo. Before that, {@code computeIfAbsent} discarded the null and re-ran the
     * lookup for each referencing row — an N+1 on exactly the rows that were
     * already broken.</p>
     *
     * <p>{@code userCompany} is the lookup under test because it is the only one of
     * the five whose resolution goes through an injectable collaborator rather than
     * an inherited Panache static, so a real call count is observable without a
     * database. The other four share the shape.</p>
     */
    @Test
    void aMissIsResolvedOnceAndThenServedFromTheMemo() {
        AgreementService service = service();
        CountingStatusService statuses = new CountingStatusService();
        service.statusService = statuses;

        AgreementService.EnrichmentCache cache = service.new EnrichmentCache();
        cache.types.put("BONUS", type("BONUS", "Bonusaftale"));
        cache.userNames.put(USER, "Hans Ernst Lassen");
        // userCompanies deliberately left unseeded: the stub resolves USER to no
        // employment status, which is the miss this test is about.

        AgreementDTO first = service.toDTO(row("a6", "ACTIVE"), cache);
        AgreementDTO second = service.toDTO(row("a7", "ACTIVE"), cache);
        AgreementDTO third = service.toDTO(row("a8", "EXPIRED"), cache);

        assertEquals(1, statuses.calls,
                "three rows referencing one unresolvable user must cost one lookup, not three");

        assertTrue(cache.userCompanies.containsKey(USER), "the negative is memoized as an entry");
        assertNull(cache.userCompanies.get(USER), "and that entry is null");

        for (AgreementDTO dto : List.of(first, second, third)) {
            assertNull(dto.getCompanyName(), "an unresolved company leaves the column empty");
            assertNull(dto.getCompanyUuid());
            assertEquals("Hans Ernst Lassen", dto.getSubjectName(), "the row still renders");
            assertEquals("Bonusaftale", dto.getAgreementTypeName());
        }
    }

    // ---- fixtures ------------------------------------------------------------

    /** Counts real resolutions of the employment-status lookup; resolves to nothing. */
    private static final class CountingStatusService extends StatusService {
        private int calls;

        @Override
        public UserStatus getLatestEmploymentStatus(String useruuid) {
            calls++;
            return null;
        }
    }

    private static EmployeeAgreement row(String uuid, String status) {
        EmployeeAgreement row = new EmployeeAgreement();
        row.setUuid(uuid);
        row.setUserUuid(USER);
        row.setAgreementType("BONUS");
        row.setStatus(status);
        return row;
    }

    private static AgreementService service() {
        AgreementService service = new AgreementService();
        service.objectMapper = new ObjectMapper();
        return service;
    }

    private static AgreementType type(String key, String name) {
        AgreementType type = new AgreementType();
        type.setTypeKey(key);
        type.setName(name);
        return type;
    }

    private static Company company(String uuid, String name) {
        Company company = new Company();
        company.setUuid(uuid);
        company.setName(name);
        return company;
    }

    private static TemplateClauseEntity clause(String name) {
        TemplateClauseEntity clause = new TemplateClauseEntity();
        clause.setUuid(CLAUSE);
        clause.setName(name);
        return clause;
    }

    private static RecruitmentCandidate candidate(String first, String last) {
        RecruitmentCandidate candidate = new RecruitmentCandidate();
        candidate.setUuid(CANDIDATE);
        candidate.setFirstName(first);
        candidate.setLastName(last);
        return candidate;
    }
}
