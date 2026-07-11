package dk.trustworks.intranet.contracts.services;

import dk.trustworks.intranet.contracts.exceptions.ContractValidationException.ErrorType;
import dk.trustworks.intranet.contracts.model.ContractTypeDefinition;
import dk.trustworks.intranet.contracts.model.enums.LifecycleStatus;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Classifies agreement availability for contract create/update operations.
 * Contract type codes are DB-backed only; the former enum bypass was removed once
 * V397 guaranteed definitions for all five legacy codes.
 */
@JBossLog
@ApplicationScoped
public class ContractTypeValidationService {

    private static final DateTimeFormatter DISPLAY_DATE =
            DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH);

    public boolean isValidContractType(String contractTypeCode) {
        return validate(contractTypeCode, null).valid();
    }

    public boolean isValidContractType(String contractTypeCode, LocalDate validationDate) {
        return validate(contractTypeCode, validationDate).valid();
    }

    /**
     * Resolve the stable machine code and human-readable reason for a contract type.
     * A null validation date means today in Europe/Copenhagen.
     */
    public ContractTypeValidationResult validate(String contractTypeCode, LocalDate validationDate) {
        LocalDate effectiveDate = validationDate != null ? validationDate : LifecycleStatus.today();
        if (contractTypeCode == null || contractTypeCode.isBlank()) {
            return invalid(ErrorType.CONTRACT_TYPE_REQUIRED, "Contract type code is required.");
        }

        String code = contractTypeCode.trim();
        ContractTypeDefinition definition = ContractTypeDefinition.findByCode(code);
        if (definition == null) {
            log.warnf("Agreement %s was not found during contract validation", code);
            return invalid(ErrorType.CONTRACT_TYPE_NOT_FOUND,
                    "Agreement '" + code + "' was not found.");
        }

        LifecycleStatus status = LifecycleStatus.forAgreement(
                definition.isActive(), definition.getValidFrom(), definition.getValidUntil(), effectiveDate);
        return switch (status) {
            case ACTIVE -> new ContractTypeValidationResult(true, status, null, null);
            case ARCHIVED -> invalid(status, ErrorType.CONTRACT_TYPE_ARCHIVED,
                    "This contract's agreement is archived — restore it or change the agreement to save.");
            case SCHEDULED -> invalid(status, ErrorType.CONTRACT_TYPE_NOT_YET_VALID,
                    "This agreement starts on " + format(definition.getValidFrom())
                            + " and is not available for this contract.");
            case EXPIRED -> invalid(status, ErrorType.CONTRACT_TYPE_EXPIRED,
                    "This agreement expired on " + formatInclusiveEnd(definition.getValidUntil())
                            + " and is not available for this contract.");
            case DISABLED -> throw new IllegalStateException("DISABLED is not an agreement lifecycle status");
        };
    }

    public boolean existsInDatabase(String code) {
        return validate(code, null).valid();
    }

    public boolean existsInDatabase(String code, LocalDate validationDate) {
        return validate(code, validationDate).valid();
    }

    public String getValidationErrorMessage(String contractTypeCode) {
        return getValidationErrorMessage(contractTypeCode, null);
    }

    public String getValidationErrorMessage(String contractTypeCode, LocalDate validationDate) {
        ContractTypeValidationResult result = validate(contractTypeCode, validationDate);
        return result.valid() ? null : result.message();
    }

    private static String format(LocalDate date) {
        return date != null ? DISPLAY_DATE.format(date) : "the configured start date";
    }

    private static String formatInclusiveEnd(LocalDate endExclusive) {
        return endExclusive != null
                ? DISPLAY_DATE.format(endExclusive.minusDays(1))
                : "the configured end date";
    }

    private static ContractTypeValidationResult invalid(ErrorType type, String message) {
        return invalid(null, type, message);
    }

    private static ContractTypeValidationResult invalid(LifecycleStatus status, ErrorType type, String message) {
        return new ContractTypeValidationResult(false, status, type, message);
    }

    public record ContractTypeValidationResult(
            boolean valid,
            LifecycleStatus status,
            ErrorType errorType,
            String message) {
    }
}
