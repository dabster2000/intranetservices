package dk.trustworks.intranet.contracts.services;

import dk.trustworks.intranet.contracts.dto.ContractOverlap;
import dk.trustworks.intranet.contracts.dto.ValidationReport;
import dk.trustworks.intranet.contracts.exceptions.ContractValidationException;
import dk.trustworks.intranet.contracts.exceptions.ContractValidationException.ErrorType;
import dk.trustworks.intranet.contracts.exceptions.ContractValidationException.ValidationError;
import dk.trustworks.intranet.contracts.model.Contract;
import dk.trustworks.intranet.contracts.model.ContractConsultant;
import dk.trustworks.intranet.contracts.model.ContractProject;
import dk.trustworks.intranet.contracts.model.enums.ContractStatus;
import dk.trustworks.intranet.dao.crm.model.Project;
import dk.trustworks.intranet.dao.workservice.model.Work;
import dk.trustworks.intranet.domain.user.entity.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.transaction.Transactional;
import lombok.extern.jbosslog.JBossLog;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Service responsible for validating contract-related entities to prevent
 * billing conflicts and ensure data integrity.
 */
@JBossLog
@ApplicationScoped
public class ContractValidationService {

    @Inject
    EntityManager em;

    /**
     * Validate a ContractConsultant before saving or updating.
     * Checks for overlapping assignments and date range validity.
     */
    @Transactional(Transactional.TxType.SUPPORTS)
    public ValidationReport validateContractConsultant(ContractConsultant consultant) {
        log.debugf("Validating ContractConsultant: %s", consultant.getUuid());

        ValidationReport report = new ValidationReport();
        List<ValidationError> errors = new ArrayList<>();

        // Initialize context
        ValidationReport.ValidationContext context = new ValidationReport.ValidationContext();
        context.setConsultantUuid(consultant.getUseruuid());
        context.setConsultantName(consultant.getName());
        context.setContractUuid(consultant.getContractuuid());
        report.setContext(context);

        // 1. Validate date range
        if (!validateDateRange(consultant, report, errors)) {
            report.setValid(false);
        }

        // 2. Check for overlapping assignments
        List<ContractOverlap> overlaps = findOverlappingConsultantAssignments(consultant);
        for (ContractOverlap overlap : overlaps) {
            report.addOverlap(overlap);
            errors.add(new ValidationError(
                "consultant",
                overlap.getDescription(),
                ErrorType.OVERLAP_CONFLICT
            ));
        }

        // 3. Check for work in affected period (for rate changes)
        checkForAffectedWork(consultant, report);

        // 4. Validate rate
        validateRate(consultant, report);

        // Add all errors to report
        errors.forEach(report::addError);

        if (!report.isValid() && report.hasErrors()) {
            log.warnf("Validation failed for ContractConsultant %s: %s",
                consultant.getUuid(), report.getSummary());
        }

        return report;
    }

    /**
     * Validate a ContractProject before linking.
     * Ensures no consultant conflicts arise from the linkage.
     */
    @Transactional(Transactional.TxType.SUPPORTS)
    public ValidationReport validateContractProject(ContractProject projectLink) {
        log.debugf("Validating ContractProject: contract=%s, project=%s",
            projectLink.getContractuuid(), projectLink.getProjectuuid());

        ValidationReport report = new ValidationReport();

        // Get the contract and its consultants
        Contract contract = Contract.findById(projectLink.getContractuuid());
        if (contract == null) {
            report.addError(new ValidationError(
                "contractuuid",
                "Contract not found",
                ErrorType.MISSING_REQUIRED
            ));
            return report;
        }

        // Check if project is already linked to this contract
        long existingCount = ContractProject.count(
            "contractuuid = ?1 AND projectuuid = ?2 AND uuid != ?3",
            projectLink.getContractuuid(),
            projectLink.getProjectuuid(),
            projectLink.getUuid() != null ? projectLink.getUuid() : ""
        );

        if (existingCount > 0) {
            report.addError(new ValidationError(
                "projectuuid",
                "Project is already linked to this contract",
                ErrorType.DUPLICATE_PROJECT
            ));
            return report;
        }

        // Check each consultant in this contract for conflicts with other contracts on the same project
        for (ContractConsultant consultant : contract.getContractConsultants()) {
            List<ContractOverlap> overlaps = findProjectConsultantOverlaps(
                consultant, projectLink.getProjectuuid()
            );

            for (ContractOverlap overlap : overlaps) {
                report.addOverlap(overlap);
                report.addError(new ValidationError(
                    "consultant",
                    String.format("Consultant %s has conflicting assignment for this project from %s to %s",
                        consultant.getName(), overlap.getExistingActiveFrom(), overlap.getExistingActiveTo()),
                    ErrorType.OVERLAP_CONFLICT
                ));
            }
        }

        return report;
    }

    /**
     * Validate contract activation.
     * Ensures all consultants and projects are properly configured.
     */
    @Transactional(Transactional.TxType.SUPPORTS)
    public ValidationReport validateContractActivation(Contract contract) {
        log.debugf("Validating contract activation: %s", contract.getUuid());

        ValidationReport report = new ValidationReport();

        // Only validate if changing to active status
        if (contract.getStatus() != ContractStatus.BUDGET &&
            contract.getStatus() != ContractStatus.SIGNED &&
            contract.getStatus() != ContractStatus.TIME) {
            return report; // No validation needed for inactive statuses
        }

        // Validate each consultant
        for (ContractConsultant consultant : contract.getContractConsultants()) {
            ValidationReport consultantReport = validateContractConsultant(consultant);
            report.getErrors().addAll(consultantReport.getErrors());
            report.getOverlaps().addAll(consultantReport.getOverlaps());
            report.getWarnings().addAll(consultantReport.getWarnings());
            if (!consultantReport.isValid()) {
                report.setValid(false);
            }
        }

        // Validate each project linkage
        for (ContractProject project : contract.getContractProjects()) {
            ValidationReport projectReport = validateContractProject(project);
            report.getErrors().addAll(projectReport.getErrors());
            report.getOverlaps().addAll(projectReport.getOverlaps());
            if (!projectReport.isValid()) {
                report.setValid(false);
            }
        }

        // Check for missing required elements
        if (contract.getContractConsultants().isEmpty()) {
            report.addWarning(new ValidationReport.Warning(
                "consultants",
                "Contract has no consultants assigned",
                ValidationReport.WarningType.GAP_IN_COVERAGE
            ));
        }

        if (contract.getContractProjects().isEmpty()) {
            report.addWarning(new ValidationReport.Warning(
                "projects",
                "Contract has no projects linked",
                ValidationReport.WarningType.GAP_IN_COVERAGE
            ));
        }

        return report;
    }

    /**
     * Find overlapping consultant assignments for the same projects.
     */
    private List<ContractOverlap> findOverlappingConsultantAssignments(ContractConsultant consultant) {
        List<ContractOverlap> overlaps = new ArrayList<>();

        // Get projects linked to this contract
        List<String> projectUuids = em.createQuery(
            "SELECT cp.projectuuid FROM ContractProject cp WHERE cp.contractuuid = :contractuuid",
            String.class
        )
        .setParameter("contractuuid", consultant.getContractuuid())
        .getResultList();

        if (projectUuids.isEmpty()) {
            return overlaps; // No projects, no conflicts possible
        }

        // Find other consultants with overlapping dates on the same projects
        String sql = """
            SELECT DISTINCT cc.*, c.name as contract_name, cp.projectuuid, p.name as project_name
            FROM contract_consultants cc
            JOIN contract_project cp ON cc.contractuuid = cp.contractuuid
            JOIN contracts c ON c.uuid = cc.contractuuid
            JOIN project p ON p.uuid = cp.projectuuid
            WHERE cc.useruuid = :useruuid
              AND cp.projectuuid IN :projectuuids
              AND cc.uuid != :consultantuuid
              AND cc.activeto >= :activefrom
              AND cc.activefrom <= :activeto
              AND c.status NOT IN ('INACTIVE', 'CLOSED')
            """;

        Query query = em.createNativeQuery(sql);
        query.setParameter("useruuid", consultant.getUseruuid());
        query.setParameter("projectuuids", projectUuids);
        query.setParameter("consultantuuid", consultant.getUuid() != null ? consultant.getUuid() : "");
        query.setParameter("activefrom", consultant.getActiveFrom());
        query.setParameter("activeto", consultant.getActiveTo());

        @SuppressWarnings("unchecked")
        List<Object[]> results = query.getResultList();

        for (Object[] row : results) {
            ContractOverlap overlap = new ContractOverlap();

            // Existing contract info
            overlap.setExistingConsultantUuid((String) row[0]);
            overlap.setExistingContractUuid((String) row[1]);
            overlap.setExistingActiveFrom(((java.sql.Date) row[4]).toLocalDate());
            overlap.setExistingActiveTo(((java.sql.Date) row[5]).toLocalDate());
            overlap.setExistingRate(((Number) row[6]).doubleValue());
            overlap.setExistingContractName((String) row[10]);

            // New contract info
            overlap.setNewConsultantUuid(consultant.getUuid());
            overlap.setNewContractUuid(consultant.getContractuuid());
            overlap.setNewActiveFrom(consultant.getActiveFrom());
            overlap.setNewActiveTo(consultant.getActiveTo());
            overlap.setNewRate(consultant.getRate());

            // Shared info
            overlap.setConsultantUuid(consultant.getUseruuid());
            overlap.setConsultantName(consultant.getName());
            overlap.setProjectUuid((String) row[11]);
            overlap.setProjectName((String) row[12]);

            overlap.calculateOverlap();
            overlaps.add(overlap);
        }

        return overlaps;
    }

    /**
     * Find overlapping consultant assignments for a specific project.
     */
    private List<ContractOverlap> findProjectConsultantOverlaps(ContractConsultant consultant, String projectUuid) {
        List<ContractOverlap> overlaps = new ArrayList<>();

        String sql = """
            SELECT cc.*, c.name as contract_name
            FROM contract_consultants cc
            JOIN contract_project cp ON cc.contractuuid = cp.contractuuid
            JOIN contracts c ON c.uuid = cc.contractuuid
            WHERE cc.useruuid = :useruuid
              AND cp.projectuuid = :projectuuid
              AND cc.contractuuid != :contractuuid
              AND cc.activeto >= :activefrom
              AND cc.activefrom <= :activeto
              AND c.status NOT IN ('INACTIVE', 'CLOSED')
            """;

        Query query = em.createNativeQuery(sql);
        query.setParameter("useruuid", consultant.getUseruuid());
        query.setParameter("projectuuid", projectUuid);
        query.setParameter("contractuuid", consultant.getContractuuid());
        query.setParameter("activefrom", consultant.getActiveFrom());
        query.setParameter("activeto", consultant.getActiveTo());

        @SuppressWarnings("unchecked")
        List<Object[]> results = query.getResultList();

        for (Object[] row : results) {
            ContractOverlap overlap = new ContractOverlap();

            overlap.setExistingConsultantUuid((String) row[0]);
            overlap.setExistingContractUuid((String) row[1]);
            overlap.setExistingActiveFrom(((java.sql.Date) row[4]).toLocalDate());
            overlap.setExistingActiveTo(((java.sql.Date) row[5]).toLocalDate());
            overlap.setExistingRate(((Number) row[6]).doubleValue());
            overlap.setExistingContractName((String) row[10]);

            overlap.setNewConsultantUuid(consultant.getUuid());
            overlap.setNewContractUuid(consultant.getContractuuid());
            overlap.setNewActiveFrom(consultant.getActiveFrom());
            overlap.setNewActiveTo(consultant.getActiveTo());
            overlap.setNewRate(consultant.getRate());

            overlap.setConsultantUuid(consultant.getUseruuid());
            overlap.setConsultantName(consultant.getName());
            overlap.setProjectUuid(projectUuid);

            overlap.calculateOverlap();
            overlaps.add(overlap);
        }

        return overlaps;
    }

    /**
     * Validate date range integrity.
     */
    private boolean validateDateRange(ContractConsultant consultant, ValidationReport report, List<ValidationError> errors) {
        if (consultant.getActiveFrom() == null) {
            errors.add(new ValidationError(
                "activeFrom",
                "Start date is required",
                ErrorType.MISSING_REQUIRED
            ));
            return false;
        }

        if (consultant.getActiveTo() == null) {
            errors.add(new ValidationError(
                "activeTo",
                "End date is required",
                ErrorType.MISSING_REQUIRED
            ));
            return false;
        }

        if (consultant.getActiveFrom().isAfter(consultant.getActiveTo())) {
            errors.add(new ValidationError(
                "dateRange",
                "Start date must be before or equal to end date",
                ErrorType.DATE_RANGE_INVALID
            ));
            return false;
        }

        // Check for future contracts
        if (consultant.getActiveFrom().isAfter(LocalDate.now())) {
            report.addWarning(new ValidationReport.Warning(
                "activeFrom",
                "Contract starts in the future",
                ValidationReport.WarningType.FUTURE_CONTRACT
            ));
        }

        // Check for retroactive contracts
        if (consultant.getActiveTo().isBefore(LocalDate.now().minusYears(1))) {
            report.addWarning(new ValidationReport.Warning(
                "activeTo",
                "Contract covers period more than 1 year in the past",
                ValidationReport.WarningType.RETROACTIVE_CONTRACT
            ));
        }

        return true;
    }

    /**
     * Check for work entries that would be affected by this consultant change.
     */
    private void checkForAffectedWork(ContractConsultant consultant, ValidationReport report) {
        // Check for unbilled work in the affected period
        String sql = """
            SELECT COUNT(w.uuid), COALESCE(SUM(w.workduration * :rate), 0)
            FROM work w
            JOIN task t ON w.taskuuid = t.uuid
            JOIN contract_project cp ON t.projectuuid = cp.projectuuid
            WHERE w.useruuid = :useruuid
              AND cp.contractuuid = :contractuuid
              AND w.registered BETWEEN :fromdate AND :todate
              AND w.invoice_line_uuid IS NULL
            """;

        Query query = em.createNativeQuery(sql);
        query.setParameter("useruuid", consultant.getUseruuid());
        query.setParameter("contractuuid", consultant.getContractuuid());
        query.setParameter("fromdate", consultant.getActiveFrom());
        query.setParameter("todate", consultant.getActiveTo());
        query.setParameter("rate", consultant.getRate());

        Object[] result = (Object[]) query.getSingleResult();
        long affectedWork = ((Number) result[0]).longValue();
        double unbilledAmount = ((Number) result[1]).doubleValue();

        if (affectedWork > 0) {
            report.getContext().setAffectedWorkEntries((int) affectedWork);
            report.getContext().setUnbilledAmount(unbilledAmount);

            // Check if this is a rate change on existing consultant
            if (consultant.getUuid() != null) {
                ContractConsultant existing = ContractConsultant.findById(consultant.getUuid());
                if (existing != null && Math.abs(existing.getRate() - consultant.getRate()) > 0.01) {
                    report.addWarning(new ValidationReport.Warning(
                        "rate",
                        String.format("Rate change affects %d unbilled work entries worth %.2f",
                            affectedWork, unbilledAmount),
                        ValidationReport.WarningType.RATE_CHANGE
                    ));
                }
            }
        }
    }

    /**
     * Validate consultant rate.
     */
    private void validateRate(ContractConsultant consultant, ValidationReport report) {
        if (consultant.getRate() <= 0) {
            report.addError(new ValidationError(
                "rate",
                "Rate must be greater than zero",
                ErrorType.RATE_CONFLICT
            ));
            return;
        }

        // Check for unusually high or low rates (configurable thresholds)
        double MIN_EXPECTED_RATE = 500.0;
        double MAX_EXPECTED_RATE = 2500.0;

        if (consultant.getRate() < MIN_EXPECTED_RATE) {
            report.addWarning(new ValidationReport.Warning(
                "rate",
                String.format("Rate %.2f is below expected minimum of %.2f",
                    consultant.getRate(), MIN_EXPECTED_RATE),
                ValidationReport.WarningType.LOW_RATE
            ));
        }

        if (consultant.getRate() > MAX_EXPECTED_RATE) {
            report.addWarning(new ValidationReport.Warning(
                "rate",
                String.format("Rate %.2f is above expected maximum of %.2f",
                    consultant.getRate(), MAX_EXPECTED_RATE),
                ValidationReport.WarningType.HIGH_RATE
            ));
        }
    }

    /**
     * Throw exception if validation fails (for enforcing validation).
     */
    public void enforceValidation(ValidationReport report) {
        if (!report.isValid() && report.hasErrors()) {
            throw new ContractValidationException(report.getErrors());
        }
    }
}