package dk.trustworks.intranet.aggregates.practices.services;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Builds the bounded, immutable dependency manifest consumed by cost and revenue generations. */
@ApplicationScoped
public class PracticeRevenueDependencyManifestProvider {
    static final int QUERY_TIMEOUT_MS = 120_000;
    // Bounded pre-scan of every recognized document in the 60-month population (design §10 line 779).
    // Each UNION branch yields an explicit dependency kind and its exact required date bounds; the basis
    // union then covers the earliest evidence any recognized document actually consumes rather than the
    // invoice recognition month alone. It stays a bounded, deterministic SQL scan — never a valuation.
    //   DIRECT_BILLING_PERIOD    - an ordinary INVOICE/PHANTOM item's own recognized billing period.
    //   CREDIT_SOURCE_DOCUMENT   - the exact one-hop source document/item a CREDIT_NOTE reverses.
    //   REGISTERED_WORK_DELIVERY - the exact persisted registered-work delivery dates for an item.
    //   SERVICE_MONTH_DELIVERY   - the invoice service-month (i.year/i.month) delivery window that the
    //                              allocation loaders actually read from. Covering argument: both
    //                              loadRegisteredDeliverySources and loadLegacyDirectSources window
    //                              registered work by w.registered IN
    //                              [STR_TO_DATE(year-month-01), STR_TO_DATE(year-month-01)+1 MONTH), and the
    //                              legacy/registered fallback synthesizes a delivery interval spanning that
    //                              same month (delivery date defaults to year-month-01 when no work exists).
    //                              Every date those loaders can consume therefore lies in
    //                              [first-of-service-month, LAST_DAY(service-month)] for the recognized
    //                              document AND for the one-hop credit source (whose evidence a credit copies).
    //                              The service month can be arrears/prepaid and fall outside the recognition
    //                              window, so covering only i.invoicedate leaves a perpetual-miss blind spot.
    //                              Invalid year/month values emit no row (the loaders' STR_TO_DATE yields NULL
    //                              and their window filter then matches nothing, so nothing is consumed).
    static final String DOCUMENT_SCAN_SQL = """
            SELECT recognized_document_uuid, recognized_item_uuid, recognized_document_type,
                   recognized_month, dependency_kind, source_document_uuid, source_item_uuid,
                   required_start_date, required_end_date
            FROM (
                SELECT i.uuid AS recognized_document_uuid, ii.uuid AS recognized_item_uuid,
                       i.type AS recognized_document_type,
                       DATE_FORMAT(i.invoicedate, '%Y-%m-01') AS recognized_month,
                       CASE WHEN i.type = 'CREDIT_NOTE' THEN 'CREDIT_SOURCE_DOCUMENT'
                            ELSE 'DIRECT_BILLING_PERIOD' END AS dependency_kind,
                       COALESCE(src.uuid, i.uuid) AS source_document_uuid,
                       ii.source_item_uuid AS source_item_uuid,
                       COALESCE(src.invoicedate, i.invoicedate) AS required_start_date,
                       COALESCE(src.invoicedate, i.invoicedate) AS required_end_date
                FROM invoices i
                LEFT JOIN invoiceitems ii ON ii.invoiceuuid = i.uuid
                LEFT JOIN invoices src ON src.uuid = i.creditnote_for_uuid
                WHERE i.status = 'CREATED'
                  AND i.type IN ('INVOICE', 'PHANTOM', 'CREDIT_NOTE')
                  AND i.invoicedate >= :recognizedStart
                  AND i.invoicedate < :recognizedEndExclusive
                UNION ALL
                SELECT i.uuid, ii.uuid, i.type,
                       DATE_FORMAT(i.invoicedate, '%Y-%m-01'), 'REGISTERED_WORK_DELIVERY',
                       i.uuid, ii.source_item_uuid,
                       piids.delivery_date, piids.delivery_date
                FROM invoices i
                JOIN invoiceitems ii ON ii.invoiceuuid = i.uuid
                JOIN practice_invoice_item_delivery_source piids ON piids.invoice_item_uuid = ii.uuid
                WHERE i.status = 'CREATED'
                  AND i.type IN ('INVOICE', 'PHANTOM', 'CREDIT_NOTE')
                  AND i.invoicedate >= :recognizedStart
                  AND i.invoicedate < :recognizedEndExclusive
                UNION ALL
                SELECT i.uuid, ii.uuid, i.type,
                       DATE_FORMAT(i.invoicedate, '%Y-%m-01'), 'SERVICE_MONTH_DELIVERY',
                       i.uuid, ii.source_item_uuid,
                       STR_TO_DATE(CONCAT(i.year, '-', LPAD(i.month, 2, '0'), '-01'), '%Y-%m-%d'),
                       LAST_DAY(STR_TO_DATE(CONCAT(i.year, '-', LPAD(i.month, 2, '0'), '-01'), '%Y-%m-%d'))
                FROM invoices i
                LEFT JOIN invoiceitems ii ON ii.invoiceuuid = i.uuid
                WHERE i.status = 'CREATED'
                  AND i.type IN ('INVOICE', 'PHANTOM', 'CREDIT_NOTE')
                  AND i.invoicedate >= :recognizedStart
                  AND i.invoicedate < :recognizedEndExclusive
                  AND i.year BETWEEN 2000 AND 2100 AND i.month BETWEEN 1 AND 12
                UNION ALL
                SELECT i.uuid, ii.uuid, i.type,
                       DATE_FORMAT(i.invoicedate, '%Y-%m-01'), 'SERVICE_MONTH_DELIVERY',
                       src.uuid, ii.source_item_uuid,
                       STR_TO_DATE(CONCAT(src.year, '-', LPAD(src.month, 2, '0'), '-01'), '%Y-%m-%d'),
                       LAST_DAY(STR_TO_DATE(CONCAT(src.year, '-', LPAD(src.month, 2, '0'), '-01'), '%Y-%m-%d'))
                FROM invoices i
                JOIN invoices src ON src.uuid = i.creditnote_for_uuid
                LEFT JOIN invoiceitems ii ON ii.invoiceuuid = i.uuid
                WHERE i.status = 'CREATED'
                  AND i.type = 'CREDIT_NOTE'
                  AND i.invoicedate >= :recognizedStart
                  AND i.invoicedate < :recognizedEndExclusive
                  AND src.year BETWEEN 2000 AND 2100 AND src.month BETWEEN 1 AND 12
            ) document_scan
            ORDER BY recognized_document_uuid, recognized_item_uuid, dependency_kind,
                     required_start_date
            """;

    @Inject EntityManager em;

    public Manifest scan(YearMonth firstRecognizedMonth, YearMonth lastRecognizedMonth) {
        Objects.requireNonNull(firstRecognizedMonth, "firstRecognizedMonth");
        Objects.requireNonNull(lastRecognizedMonth, "lastRecognizedMonth");
        if (lastRecognizedMonth.isBefore(firstRecognizedMonth)) {
            throw new IllegalArgumentException("recognition interval is reversed");
        }
        Query query = em.createNativeQuery(DOCUMENT_SCAN_SQL)
                .setParameter("recognizedStart", firstRecognizedMonth.atDay(1))
                .setParameter("recognizedEndExclusive", lastRecognizedMonth.plusMonths(1).atDay(1));
        query.setHint("jakarta.persistence.query.timeout", QUERY_TIMEOUT_MS);
        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        List<Dependency> dependencies = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            LocalDate recognizedMonth = toDate(row[3]);
            String dependencyKind = text(row[4]);
            LocalDate requiredStart = toDate(row[7]);
            LocalDate requiredEnd = toDate(row[8]);
            dependencies.add(new Dependency(
                    text(row[0]), text(row[1]), text(row[2]), recognizedMonth,
                    dependencyKind, text(row[5]), text(row[6]), requiredStart, requiredEnd,
                    fingerprintOf(List.of(text(row[0]), text(row[1]), dependencyKind, text(row[5]),
                            text(row[6]), String.valueOf(requiredStart), String.valueOf(requiredEnd)))));
        }
        return fromDependencies(dependencies, firstRecognizedMonth.atDay(1),
                lastRecognizedMonth.atEndOfMonth());
    }

    public Manifest fromDependencies(
            List<Dependency> source,
            LocalDate recognitionStart,
            LocalDate recognitionEnd) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(recognitionStart, "recognitionStart");
        Objects.requireNonNull(recognitionEnd, "recognitionEnd");
        if (recognitionEnd.isBefore(recognitionStart)) throw new IllegalArgumentException("reversed bounds");
        List<Dependency> sorted = source.stream()
                .peek(PracticeRevenueDependencyManifestProvider::validate)
                .sorted(Comparator.comparing(Dependency::stableKey))
                .distinct()
                .toList();
        LocalDate coverageStart = recognitionStart;
        LocalDate coverageEnd = recognitionEnd;
        for (Dependency dependency : sorted) {
            if (dependency.requiredStartDate().isBefore(coverageStart)) coverageStart = dependency.requiredStartDate();
            if (dependency.requiredEndDate().isAfter(coverageEnd)) coverageEnd = dependency.requiredEndDate();
        }
        String body = sorted.stream().map(Dependency::stableKey)
                .reduce("", (left, right) -> left + right + "\n");
        return new Manifest(sorted, recognitionStart, recognitionEnd, coverageStart, coverageEnd,
                fingerprintOf(List.of(recognitionStart.toString(), recognitionEnd.toString(), body)));
    }

    public void assertCovered(Manifest manifest, List<Dependency> consumed) {
        Manifest actual = fromDependencies(consumed,
                manifest.recognitionStart(), manifest.recognitionEnd());
        if (!manifest.fingerprint().equals(actual.fingerprint())
                || actual.coverageStart().isBefore(manifest.coverageStart())
                || actual.coverageEnd().isAfter(manifest.coverageEnd())) {
            throw new BasisCoverageMissException("BASIS_COVERAGE_MISS");
        }
    }

    private static void validate(Dependency row) {
        Objects.requireNonNull(row, "dependency");
        if (blank(row.recognizedDocumentUuid()) || blank(row.recognizedDocumentType())
                || row.recognizedMonth() == null || blank(row.dependencyKind())
                || row.requiredStartDate() == null || row.requiredEndDate() == null
                || row.requiredEndDate().isBefore(row.requiredStartDate())) {
            throw new BasisCoverageMissException("INVALID_DEPENDENCY_BOUNDS");
        }
    }

    private static String fingerprintOf(List<String> values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                digest.update(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static LocalDate toDate(Object value) {
        if (value instanceof LocalDate date) return date;
        if (value instanceof java.sql.Date date) return date.toLocalDate();
        return LocalDate.parse(String.valueOf(value));
    }
    private static String text(Object value) { return value == null ? null : String.valueOf(value); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }

    public record Manifest(
            List<Dependency> dependencies,
            LocalDate recognitionStart,
            LocalDate recognitionEnd,
            LocalDate coverageStart,
            LocalDate coverageEnd,
            String fingerprint) {
        public Manifest {
            dependencies = List.copyOf(dependencies);
        }
    }

    public record Dependency(
            String recognizedDocumentUuid,
            String recognizedItemUuid,
            String recognizedDocumentType,
            LocalDate recognizedMonth,
            String dependencyKind,
            String sourceDocumentUuid,
            String sourceItemUuid,
            LocalDate requiredStartDate,
            LocalDate requiredEndDate,
            String sourceFingerprint) {
        String stableKey() {
            return String.join("|",
                    String.valueOf(recognizedDocumentUuid), String.valueOf(recognizedItemUuid),
                    String.valueOf(recognizedDocumentType), String.valueOf(recognizedMonth),
                    String.valueOf(dependencyKind), String.valueOf(sourceDocumentUuid),
                    String.valueOf(sourceItemUuid), String.valueOf(requiredStartDate),
                    String.valueOf(requiredEndDate), String.valueOf(sourceFingerprint));
        }
    }

    public static class BasisCoverageMissException extends IllegalStateException {
        public BasisCoverageMissException(String message) { super(message); }
    }
}
