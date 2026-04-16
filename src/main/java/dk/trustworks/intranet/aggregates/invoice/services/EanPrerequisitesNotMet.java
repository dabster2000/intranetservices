package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.economics.EanPrerequisiteErrorDto;
import lombok.Getter;

/**
 * Thrown when one or more EAN sending prerequisites are not satisfied.
 * Carries a structured {@link EanPrerequisiteErrorDto} with all failures
 * so the UI can render actionable messages.
 *
 * <p>Mapped to HTTP 400 by {@link EanPrerequisitesNotMetMapper}.
 *
 * <p>SPEC-INV-001 section 4.2, section 4.3.
 */
@Getter
public class EanPrerequisitesNotMet extends RuntimeException {

    private final EanPrerequisiteErrorDto errorDto;

    public EanPrerequisitesNotMet(EanPrerequisiteErrorDto errorDto) {
        super(errorDto.getReason());
        this.errorDto = errorDto;
    }
}
