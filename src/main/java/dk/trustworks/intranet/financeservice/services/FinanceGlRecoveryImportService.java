package dk.trustworks.intranet.financeservice.services;

import dk.trustworks.intranet.financeservice.model.enums.PostingStatus;
import dk.trustworks.intranet.model.Company;
import dk.trustworks.intranet.utils.DateUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.transaction.Transactional;
import org.apache.commons.lang3.Range;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Complete, bounded FINANCE_GL evidence rebuild for an already-owned recovery attempt.
 *
 * <p>The recognition scope is exactly 60 completed months for every company. The finite one-hop
 * source documents referenced by included credits are added to the fiscal-period plan even when
 * they predate that recognition window. The service never changes the FINANCE_GL watermark: the
 * recovery coordinator owns that token, advances its version once, and restores READY only after
 * this service has completed and revalidated the same owner.</p>
 */
@ApplicationScoped
public class FinanceGlRecoveryImportService {

    static final int QUERY_TIMEOUT_MILLIS = 120_000;
    static final String RECOVERY_OWNER_SQL = """
            SELECT COUNT(*)
            FROM practice_revenue_source_watermark
            WHERE source_name='FINANCE_GL'
              AND source_state='RUNNING'
              AND attempt_token=:token
            """;
    static final String DOCUMENT_DEPENDENCY_SQL = """
            SELECT DISTINCT i.uuid, i.companyuuid, i.invoicedate
            FROM invoices i
            WHERE i.status='CREATED'
              AND i.type IN ('INVOICE','PHANTOM','CREDIT_NOTE')
              AND ((i.invoicedate>=:fromInclusive AND i.invoicedate<:toExclusive)
                OR i.uuid IN (
                    SELECT c.creditnote_for_uuid
                    FROM invoices c
                    WHERE c.status='CREATED' AND c.type='CREDIT_NOTE'
                      AND c.invoicedate>=:fromInclusive AND c.invoicedate<:toExclusive
                      AND c.creditnote_for_uuid IS NOT NULL
                ))
            ORDER BY i.companyuuid, i.invoicedate, i.uuid
            """;

    @Inject
    EconomicsService economicsService;

    @Inject
    EntityManager em;

    @Transactional(Transactional.TxType.NOT_SUPPORTED)
    public RecoverySummary rebuild(LocalDate fromInclusive, LocalDate toInclusive, String recoveryToken) {
        validateRecognitionBounds(fromInclusive, toInclusive);
        if (recoveryToken == null || recoveryToken.isBlank()) {
            throw new IllegalArgumentException("FINANCE_GL_RECOVERY_TOKEN_REQUIRED");
        }

        assertRecoveryOwner(recoveryToken);
        List<Company> companies = loadCompanies();
        if (companies.isEmpty()) {
            throw new IllegalStateException("FINANCE_GL_COMPANY_SCOPE_EMPTY");
        }
        Map<String, Company> companiesByUuid = indexCompanies(companies);
        List<DocumentDependency> dependencies = loadDocumentDependencies(fromInclusive, toInclusive);
        List<CompanyFiscalPeriod> plan = buildImportPlan(
                companies, dependencies, fromInclusive, toInclusive, companiesByUuid);

        assertRecoveryOwner(recoveryToken);
        economicsService.cleanForRecovery(recoveryToken);

        Set<CompanyFiscalPeriod> completed = new LinkedHashSet<>();
        long importedControlRows = 0;
        for (CompanyFiscalPeriod period : plan) {
            assertRecoveryOwner(recoveryToken);
            Company company = companiesByUuid.get(period.companyUuid());
            String economicsPeriod = DateUtils.toEconomicsUrlYear(
                    DateUtils.getFiscalYearName(period.fiscalStart(), period.companyUuid()));
            Map<PostingStatus, Map<Range<Integer>, List<EconomicsService.FinanceEntry>>> entries =
                    economicsService.getAllEntriesForRecovery(company, economicsPeriod);
            importedControlRows += validateControlCoverage(period, entries);
            assertRecoveryOwner(recoveryToken);
            economicsService.persistExpenses(entries);
            if (!completed.add(period)) {
                throw new IllegalStateException("FINANCE_GL_DUPLICATE_PERIOD");
            }
            assertRecoveryOwner(recoveryToken);
        }

        if (completed.size() != plan.size() || !completed.containsAll(plan)) {
            throw new IllegalStateException("FINANCE_GL_PERIOD_COVERAGE_INCOMPLETE");
        }
        validateDocumentCoverage(dependencies, completed);
        assertRecoveryOwner(recoveryToken);
        return new RecoverySummary(fromInclusive, toInclusive, companies.size(), plan.size(),
                dependencies.size(), importedControlRows);
    }

    List<Company> loadCompanies() {
        List<Company> companies = Company.listAll();
        return companies.stream()
                .sorted(Comparator.comparing(Company::getUuid, Comparator.nullsFirst(String::compareTo)))
                .toList();
    }

    @SuppressWarnings("unchecked")
    List<DocumentDependency> loadDocumentDependencies(LocalDate fromInclusive, LocalDate toInclusive) {
        List<Object[]> rows = timed(em.createNativeQuery(DOCUMENT_DEPENDENCY_SQL))
                .setParameter("fromInclusive", fromInclusive)
                .setParameter("toExclusive", toInclusive.plusDays(1))
                .getResultList();
        List<DocumentDependency> result = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            result.add(new DocumentDependency(text(row[0]), text(row[1]), localDate(row[2])));
        }
        return List.copyOf(result);
    }

    void assertRecoveryOwner(String recoveryToken) {
        Object value = timed(em.createNativeQuery(RECOVERY_OWNER_SQL))
                .setParameter("token", recoveryToken)
                .getSingleResult();
        if (!(value instanceof Number number) || number.intValue() != 1) {
            throw new IllegalStateException("FINANCE_GL_RECOVERY_OWNER_CHANGED");
        }
    }

    static List<CompanyFiscalPeriod> buildImportPlan(
            List<Company> companies,
            List<DocumentDependency> dependencies,
            LocalDate fromInclusive,
            LocalDate toInclusive,
            Map<String, Company> companiesByUuid) {
        Set<CompanyFiscalPeriod> periods = new LinkedHashSet<>();
        LocalDate firstFiscalStart = fiscalStart(fromInclusive);
        LocalDate lastFiscalStart = fiscalStart(toInclusive);
        for (Company company : companies) {
            requireCompany(company);
            for (LocalDate fiscalStart = firstFiscalStart;
                 !fiscalStart.isAfter(lastFiscalStart);
                 fiscalStart = fiscalStart.plusYears(1)) {
                periods.add(new CompanyFiscalPeriod(company.getUuid(), fiscalStart));
            }
        }
        for (DocumentDependency dependency : dependencies) {
            if (dependency.documentUuid() == null || dependency.documentUuid().isBlank()
                    || dependency.companyUuid() == null || dependency.companyUuid().isBlank()
                    || dependency.documentDate() == null) {
                throw new IllegalStateException("FINANCE_GL_DOCUMENT_DEPENDENCY_INCOMPLETE");
            }
            if (!companiesByUuid.containsKey(dependency.companyUuid())) {
                throw new IllegalStateException("FINANCE_GL_DOCUMENT_COMPANY_UNKNOWN");
            }
            periods.add(new CompanyFiscalPeriod(
                    dependency.companyUuid(), fiscalStart(dependency.documentDate())));
        }
        return periods.stream()
                .sorted(Comparator.comparing(CompanyFiscalPeriod::companyUuid)
                        .thenComparing(CompanyFiscalPeriod::fiscalStart))
                .toList();
    }

    private static Map<String, Company> indexCompanies(List<Company> companies) {
        Map<String, Company> result = new LinkedHashMap<>();
        for (Company company : companies) {
            requireCompany(company);
            if (result.putIfAbsent(company.getUuid(), company) != null) {
                throw new IllegalStateException("FINANCE_GL_DUPLICATE_COMPANY");
            }
        }
        return Map.copyOf(result);
    }

    private static void requireCompany(Company company) {
        if (company == null || company.getUuid() == null || company.getUuid().isBlank()) {
            throw new IllegalStateException("FINANCE_GL_COMPANY_IDENTITY_INCOMPLETE");
        }
    }

    private static long validateControlCoverage(
            CompanyFiscalPeriod period,
            Map<PostingStatus, Map<Range<Integer>, List<EconomicsService.FinanceEntry>>> entries) {
        if (entries == null
                || !entries.containsKey(PostingStatus.BOOKED)
                || !entries.containsKey(PostingStatus.DRAFT)) {
            throw new IllegalStateException("FINANCE_GL_POSTING_STATUS_COVERAGE_INCOMPLETE");
        }
        LocalDate endExclusive = period.fiscalStart().plusYears(1);
        long rows = 0;
        for (Map<Range<Integer>, List<EconomicsService.FinanceEntry>> byAccount : entries.values()) {
            if (byAccount == null) {
                throw new IllegalStateException("FINANCE_GL_ACCOUNT_COVERAGE_INCOMPLETE");
            }
            for (List<EconomicsService.FinanceEntry> controlRows : byAccount.values()) {
                if (controlRows == null) {
                    throw new IllegalStateException("FINANCE_GL_CONTROL_ROWS_INCOMPLETE");
                }
                for (EconomicsService.FinanceEntry entry : controlRows) {
                    if (entry == null || entry.period() == null
                            || entry.period().isBefore(period.fiscalStart())
                            || !entry.period().isBefore(endExclusive)) {
                        throw new IllegalStateException("FINANCE_GL_CONTROL_PERIOD_OUT_OF_SCOPE");
                    }
                    rows++;
                }
            }
        }
        return rows;
    }

    private static void validateDocumentCoverage(
            List<DocumentDependency> dependencies, Set<CompanyFiscalPeriod> completed) {
        for (DocumentDependency dependency : dependencies) {
            CompanyFiscalPeriod required = new CompanyFiscalPeriod(
                    dependency.companyUuid(), fiscalStart(dependency.documentDate()));
            if (!completed.contains(required)) {
                throw new IllegalStateException("FINANCE_GL_DOCUMENT_COVERAGE_INCOMPLETE");
            }
        }
    }

    static void validateRecognitionBounds(LocalDate fromInclusive, LocalDate toInclusive) {
        if (fromInclusive == null || toInclusive == null || fromInclusive.isAfter(toInclusive)
                || fromInclusive.getDayOfMonth() != 1
                || !toInclusive.equals(YearMonth.from(toInclusive).atEndOfMonth())
                || ChronoUnit.MONTHS.between(YearMonth.from(fromInclusive), YearMonth.from(toInclusive)) + 1 != 60) {
            throw new IllegalArgumentException("FINANCE_GL_RECOVERY_REQUIRES_60_COMPLETE_MONTHS");
        }
    }

    private static LocalDate fiscalStart(LocalDate date) {
        return LocalDate.of(date.getMonthValue() >= 7 ? date.getYear() : date.getYear() - 1, 7, 1);
    }

    private Query timed(Query query) {
        return query.setHint("jakarta.persistence.query.timeout", QUERY_TIMEOUT_MILLIS);
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    private static LocalDate localDate(Object value) {
        if (value == null) return null;
        if (value instanceof LocalDate date) return date;
        if (value instanceof java.sql.Date date) return date.toLocalDate();
        if (value instanceof java.sql.Timestamp timestamp) return timestamp.toLocalDateTime().toLocalDate();
        return LocalDate.parse(value.toString());
    }

    public record RecoverySummary(
            LocalDate fromInclusive,
            LocalDate toInclusive,
            int companyCount,
            int fiscalPeriodCount,
            int documentDependencyCount,
            long importedControlRowCount) {
    }

    record DocumentDependency(String documentUuid, String companyUuid, LocalDate documentDate) {
    }

    record CompanyFiscalPeriod(String companyUuid, LocalDate fiscalStart) {
        CompanyFiscalPeriod {
            Objects.requireNonNull(companyUuid, "companyUuid");
            Objects.requireNonNull(fiscalStart, "fiscalStart");
        }
    }
}
