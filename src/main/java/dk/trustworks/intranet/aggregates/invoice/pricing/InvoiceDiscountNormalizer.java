package dk.trustworks.intranet.aggregates.invoice.pricing;

import jakarta.enterprise.context.ApplicationScoped;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Canonical scale and range boundary for the legacy {@code invoices.discount} DOUBLE. */
@ApplicationScoped
public class InvoiceDiscountNormalizer {

    public static final int SCALE = 6;
    private static final BigDecimal MIN = new BigDecimal("0.000000");
    private static final BigDecimal MAX = new BigDecimal("100.000000");
    private static final int MAX_INTEGER_DIGITS = 18;

    public NormalizedDiscount normalizeForNewInput(Double source) {
        NormalizedDiscount normalized = normalize(source);
        if (!normalized.valid()) {
            throw new IllegalArgumentException(normalized.reason());
        }
        return normalized;
    }

    public NormalizedDiscount normalizeHistorical(Double source) {
        return normalize(source);
    }

    private NormalizedDiscount normalize(Double source) {
        if (source == null) {
            return valid("0", MIN, false);
        }
        if (!Double.isFinite(source)) {
            return invalid(String.valueOf(source), "HEADER_DISCOUNT_OUT_OF_RANGE");
        }
        return normalizeText(Double.toString(source));
    }

    public NormalizedDiscount normalizeText(String sourceText) {
        if (sourceText == null || sourceText.isBlank()) {
            return invalid(sourceText, "HEADER_DISCOUNT_OUT_OF_RANGE");
        }
        final BigDecimal original;
        try {
            original = new BigDecimal(sourceText.trim());
        } catch (NumberFormatException e) {
            return invalid(sourceText, "HEADER_DISCOUNT_OUT_OF_RANGE");
        }
        int integerDigits = Math.max(0, original.precision() - original.scale());
        if (integerDigits > MAX_INTEGER_DIGITS) {
            return invalid(sourceText, "HEADER_DISCOUNT_OUT_OF_RANGE");
        }
        BigDecimal normalized = original.setScale(SCALE, RoundingMode.HALF_UP);
        if (normalized.compareTo(MIN) < 0 || normalized.compareTo(MAX) > 0) {
            return invalid(sourceText, "HEADER_DISCOUNT_OUT_OF_RANGE");
        }
        return valid(sourceText, normalized, original.compareTo(normalized) != 0);
    }

    private static NormalizedDiscount valid(String original, BigDecimal normalized, boolean changed) {
        return new NormalizedDiscount(original, normalized, changed, true, null);
    }

    private static NormalizedDiscount invalid(String original, String reason) {
        return new NormalizedDiscount(original, null, false, false, reason);
    }

    public record NormalizedDiscount(
            String originalRepresentation,
            BigDecimal value,
            boolean normalizationChanged,
            boolean valid,
            String reason) {
    }
}
