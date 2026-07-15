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
    static final String DOCUMENT_SCAN_SQL = """
            SELECT i.uuid, ii.uuid, i.type,
                   DATE_FORMAT(i.invoicedate, '%Y-%m-01'),
                   COALESCE(src.uuid, i.uuid), ii.source_item_uuid,
                   COALESCE(src.invoicedate, i.invoicedate)
            FROM invoices i
            LEFT JOIN invoiceitems ii ON ii.invoiceuuid = i.uuid
            LEFT JOIN invoices src ON src.uuid = i.creditnote_for_uuid
            WHERE i.status = 'CREATED'
              AND i.type IN ('INVOICE', 'PHANTOM', 'CREDIT_NOTE')
              AND i.invoicedate >= :recognizedStart
              AND i.invoicedate < :recognizedEndExclusive
            ORDER BY i.uuid, ii.uuid
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
            LocalDate dependencyDate = toDate(row[6]);
            String documentType = text(row[2]);
            boolean credit = "CREDIT_NOTE".equals(documentType);
            dependencies.add(new Dependency(
                    text(row[0]), text(row[1]), documentType, recognizedMonth,
                    credit ? "CREDIT_SOURCE_DOCUMENT" : "DIRECT_BILLING_PERIOD",
                    text(row[4]), text(row[5]), dependencyDate, dependencyDate,
                    fingerprintOf(List.of(text(row[0]), text(row[1]), text(row[4]), text(row[5]),
                            String.valueOf(dependencyDate)))));
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
