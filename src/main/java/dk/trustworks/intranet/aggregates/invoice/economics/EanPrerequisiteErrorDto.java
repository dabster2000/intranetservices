package dk.trustworks.intranet.aggregates.invoice.economics;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Map;

/**
 * Structured error returned when one or more EAN prerequisites are not
 * satisfied. Each entry in {@code failures} maps a machine-readable check key
 * (e.g. {@code EAN_MISSING}) to a human-readable explanation.
 *
 * <p>Returned by {@link dk.trustworks.intranet.aggregates.invoice.services.EanPrerequisiteChecker}
 * so the UI can render actionable messages.
 *
 * <p>SPEC-INV-001 section 4.2, section 12.5.
 */
@Getter
@AllArgsConstructor
public class EanPrerequisiteErrorDto {
    private String reason;
    private Map<String, String> failures;
}
