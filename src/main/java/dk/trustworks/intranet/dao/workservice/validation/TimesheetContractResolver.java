package dk.trustworks.intranet.dao.workservice.validation;

import dk.trustworks.intranet.dao.workservice.model.Work;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import java.util.List;
import java.util.Objects;

/**
 * Resolves and validates the concrete contract used by one timesheet cell.
 * Contract status is deliberately not filtered: an unlocked historical entry can still belong to
 * a contract that is CLOSED today, and fixed-price BUDGET contracts also carry registered work.
 */
@ApplicationScoped
public class TimesheetContractResolver {

    private static final String ELIGIBLE_CONTRACTS_SQL = """
            SELECT DISTINCT
                c.uuid,
                c.contracttype,
                COALESCE(ctd.name, c.contracttype),
                t.projectuuid,
                p.clientuuid
            FROM task t
            JOIN project p ON p.uuid = t.projectuuid
            JOIN contract_project cp ON cp.projectuuid = t.projectuuid
            JOIN contracts c ON c.uuid = cp.contractuuid
            JOIN contract_consultants cc ON cc.contractuuid = c.uuid
            LEFT JOIN contract_type_definitions ctd ON ctd.code = c.contracttype
            WHERE t.uuid = :taskUuid
              AND cc.useruuid = :effectiveUserUuid
              AND cc.activefrom <= :registered
              AND cc.activeto >= :registered
              AND c.clientuuid = p.clientuuid
            ORDER BY c.uuid
            """;

    @Inject
    EntityManager em;

    public Resolution resolve(Work work, String effectiveUserUuid) {
        String requestedContractUuid = normalize(work.getContractuuid());
        if (requestedContractUuid != null && requestedContractUuid.length() > 36) {
            return Resolution.unresolved(null,
                    "The supplied contractUuid is not eligible for this work entry.", 0);
        }

        PersistedAssociation persisted = findPersistedAssociation(work);
        if (persisted != null && persisted.paidOut()) {
            return Resolution.paidOut(persisted.storedContractUuid());
        }
        if (persisted != null
                && (requestedContractUuid == null
                || Objects.equals(requestedContractUuid, persisted.storedContractUuid()))) {
            return persisted.contract() != null
                    ? Resolution.resolved(persisted.contract())
                    : Resolution.noApplicableAgreement(persisted.storedContractUuid());
        }

        if (work.getRegistered() == null || normalize(work.getTaskuuid()) == null
                || normalize(effectiveUserUuid) == null) {
            return requestedContractUuid == null
                    ? Resolution.noApplicableAgreement(null)
                    : Resolution.unresolved(requestedContractUuid,
                            "No eligible contract could be resolved for this work entry.", 0);
        }

        List<EligibleContract> eligibleCandidates = findEligibleCandidates(work, effectiveUserUuid);
        List<EligibleContract> candidates = eligibleCandidates.stream()
                .filter(candidate -> matchesRequestedRouting(work, candidate))
                .toList();

        // The task/project/client relationship is server-derived. Caller-supplied routing must
        // not filter a real eligible agreement out of the candidate set and thereby bypass rules.
        if (candidates.isEmpty() && !eligibleCandidates.isEmpty()) {
            return Resolution.unresolved(
                    requestedContractUuid,
                    "The supplied project/client routing does not match this work entry.",
                    eligibleCandidates.size());
        }

        if (requestedContractUuid != null) {
            return candidates.stream()
                    .filter(candidate -> requestedContractUuid.equals(candidate.contractUuid()))
                    .findFirst()
                    .map(Resolution::resolved)
                    .orElseGet(() -> Resolution.unresolved(
                            requestedContractUuid,
                            "The supplied contractUuid is not eligible for this work entry.",
                            candidates.size()));
        }

        if (candidates.size() == 1) {
            return Resolution.resolved(candidates.getFirst());
        }
        if (candidates.isEmpty()) {
            return Resolution.noApplicableAgreement(null);
        }
        return Resolution.ambiguous(
                "Multiple eligible contracts match this work entry; supply contractUuid.",
                candidates.size());
    }

    /**
     * Existing tuple state is authoritative for an edit. Consultant assignments, project links, or
     * even the contract row itself may have been removed after the work was registered; resaving
     * that historical cell must not turn a pre-existing no-op into a Phase 4 rejection.
     */
    @SuppressWarnings("unchecked")
    PersistedAssociation findPersistedAssociation(Work work) {
        if (work == null || work.getRegistered() == null
                || normalize(work.getUseruuid()) == null || normalize(work.getTaskuuid()) == null) {
            return null;
        }

        List<Object[]> rows = em.createNativeQuery("""
                        SELECT w.contractuuid,
                               c.contracttype,
                               COALESCE(ctd.name, c.contracttype),
                               COALESCE(w.projectuuid, t.projectuuid),
                               COALESCE(w.clientuuid, p.clientuuid),
                               w.paid_out
                        FROM work w
                        LEFT JOIN task t ON t.uuid = w.taskuuid
                        LEFT JOIN project p ON p.uuid = t.projectuuid
                        LEFT JOIN contracts c ON c.uuid = w.contractuuid
                        LEFT JOIN contract_type_definitions ctd ON ctd.code = c.contracttype
                        WHERE w.registered = :registered
                          AND w.useruuid = :userUuid
                          AND w.taskuuid = :taskUuid
                        LIMIT 1
                        """)
                .setParameter("registered", work.getRegistered())
                .setParameter("userUuid", normalize(work.getUseruuid()))
                .setParameter("taskUuid", normalize(work.getTaskuuid()))
                .getResultList();

        if (rows.isEmpty()) {
            return null;
        }
        Object[] row = rows.getFirst();
        String storedContractUuid = normalize(stringValue(row[0]));
        EligibleContract contract = row[1] == null || storedContractUuid == null
                ? null
                : new EligibleContract(
                        storedContractUuid,
                        stringValue(row[1]),
                        stringValue(row[2]),
                        stringValue(row[3]),
                        stringValue(row[4]));
        return new PersistedAssociation(storedContractUuid, contract, row[5] != null);
    }

    /** Package-private seam: association selection is unit-tested without a live database. */
    @SuppressWarnings("unchecked")
    List<EligibleContract> findEligibleCandidates(Work work, String effectiveUserUuid) {
        List<Object[]> rows = em.createNativeQuery(ELIGIBLE_CONTRACTS_SQL)
                .setParameter("taskUuid", normalize(work.getTaskuuid()))
                .setParameter("effectiveUserUuid", normalize(effectiveUserUuid))
                .setParameter("registered", work.getRegistered())
                .getResultList();

        return rows.stream()
                .map(row -> new EligibleContract(
                        stringValue(row[0]),
                        stringValue(row[1]),
                        stringValue(row[2]),
                        stringValue(row[3]),
                        stringValue(row[4])))
                .toList();
    }

    private static boolean matchesRequestedRouting(Work work, EligibleContract candidate) {
        String requestedProjectUuid = normalize(work.getProjectuuid());
        String requestedClientUuid = normalize(work.getClientuuid());
        return (requestedProjectUuid == null || requestedProjectUuid.equals(candidate.projectUuid()))
                && (requestedClientUuid == null || requestedClientUuid.equals(candidate.clientUuid()));
    }

    private static String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    public record EligibleContract(
            String contractUuid,
            String contractTypeCode,
            String agreementName,
            String projectUuid,
            String clientUuid) {
    }

    record PersistedAssociation(
            String storedContractUuid,
            EligibleContract contract,
            boolean paidOut) {
    }

    public record Resolution(
            ResolutionStatus status,
            EligibleContract contract,
            String requestedContractUuid,
            String message,
            int candidateCount) {

        static Resolution resolved(EligibleContract contract) {
            return new Resolution(ResolutionStatus.RESOLVED, contract, contract.contractUuid(), null, 1);
        }

        static Resolution unresolved(String requestedContractUuid, String message, int candidateCount) {
            return new Resolution(ResolutionStatus.UNRESOLVED, null, requestedContractUuid, message, candidateCount);
        }

        static Resolution ambiguous(String message, int candidateCount) {
            return new Resolution(ResolutionStatus.AMBIGUOUS, null, null, message, candidateCount);
        }

        static Resolution noApplicableAgreement(String storedContractUuid) {
            return new Resolution(
                    ResolutionStatus.NO_APPLICABLE_AGREEMENT,
                    null,
                    storedContractUuid,
                    "No framework agreement applies to this work entry.",
                    0);
        }

        static Resolution paidOut(String storedContractUuid) {
            return new Resolution(
                    ResolutionStatus.PAID_OUT_NOOP,
                    null,
                    storedContractUuid,
                    "The existing work entry is already paid out and will not be updated.",
                    0);
        }

        public boolean isResolved() {
            return status == ResolutionStatus.RESOLVED;
        }

        public boolean skipsAgreementValidation() {
            return status == ResolutionStatus.NO_APPLICABLE_AGREEMENT
                    || status == ResolutionStatus.PAID_OUT_NOOP;
        }
    }

    public enum ResolutionStatus {
        RESOLVED,
        NO_APPLICABLE_AGREEMENT,
        PAID_OUT_NOOP,
        UNRESOLVED,
        AMBIGUOUS
    }
}
