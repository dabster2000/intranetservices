package dk.trustworks.intranet.dao.workservice.validation;

import dk.trustworks.intranet.dao.workservice.model.Work;
import dk.trustworks.intranet.dao.workservice.validation.TimesheetContractResolver.EligibleContract;
import dk.trustworks.intranet.dao.workservice.validation.TimesheetContractResolver.PersistedAssociation;
import dk.trustworks.intranet.dao.workservice.validation.TimesheetContractResolver.Resolution;
import dk.trustworks.intranet.dao.workservice.validation.TimesheetContractResolver.ResolutionStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

class TimesheetContractResolverTest {

    private static final EligibleContract FIRST =
            new EligibleContract("contract-a", "TYPE_A", "Agreement A", "project", "client");
    private static final EligibleContract SECOND =
            new EligibleContract("contract-b", "TYPE_B", "Agreement B", "project", "client");

    @Test
    void suppliedContractWinsOnMultiContractTask() {
        Work work = work();
        work.setContractuuid("contract-b");
        TimesheetContractResolver resolver = resolverWith(FIRST, SECOND);

        Resolution result = resolver.resolve(work, "consultant");

        assertTrue(result.isResolved());
        assertEquals(SECOND, result.contract());
    }

    @Test
    void suppliedContractMustBelongToEligibleProjectConsultantDateAndClientSet() {
        Work work = work();
        work.setContractuuid("unrelated-contract");
        TimesheetContractResolver resolver = resolverWith(FIRST, SECOND);

        Resolution result = resolver.resolve(work, "consultant");

        assertFalse(result.isResolved());
        assertEquals(ResolutionStatus.UNRESOLVED, result.status());
        assertTrue(result.message().contains("not eligible"));
    }

    @Test
    void absentContractDerivesOnlyWhenExactlyOneCandidateExists() {
        Work work = work();

        Resolution one = resolverWith(FIRST).resolve(work, "consultant");
        Resolution many = resolverWith(FIRST, SECOND).resolve(work, "consultant");
        Resolution none = resolverWith().resolve(work, "consultant");

        assertEquals(FIRST, one.contract());
        assertEquals(ResolutionStatus.AMBIGUOUS, many.status());
        assertEquals(2, many.candidateCount());
        assertEquals(ResolutionStatus.NO_APPLICABLE_AGREEMENT, none.status());
    }

    @Test
    void requestedProjectAndClientArePartOfAssociationValidation() {
        Work work = work();
        work.setProjectuuid("different-project");
        work.setClientuuid("different-client");

        Resolution result = resolverWith(FIRST).resolve(work, "consultant");

        assertEquals(ResolutionStatus.UNRESOLVED, result.status());
    }

    @Test
    void exactPersistedContractIsAuthoritativeForHistoricalEdit() {
        Work work = work();
        work.setContractuuid("legacy-contract");
        EligibleContract historical = new EligibleContract(
                "legacy-contract", "LEGACY", "Legacy agreement", "old-project", "old-client");
        TimesheetContractResolver resolver = resolverWith();
        doReturn(new PersistedAssociation("legacy-contract", historical, false))
                .when(resolver).findPersistedAssociation(work);

        Resolution result = resolver.resolve(work, "consultant");

        assertTrue(result.isResolved());
        assertEquals(historical, result.contract());
    }

    @Test
    void deletedPersistedContractHasNoApplicableAgreementButReplacementMustBeEligible() {
        Work historicalEdit = work();
        historicalEdit.setContractuuid("deleted-contract");
        TimesheetContractResolver resolver = resolverWith(FIRST);
        doReturn(new PersistedAssociation("deleted-contract", null, false))
                .when(resolver).findPersistedAssociation(historicalEdit);

        assertEquals(ResolutionStatus.NO_APPLICABLE_AGREEMENT,
                resolver.resolve(historicalEdit, "consultant").status());

        historicalEdit.setContractuuid("arbitrary-replacement");
        assertEquals(ResolutionStatus.UNRESOLVED,
                resolver.resolve(historicalEdit, "consultant").status());
    }

    @Test
    void paidOutExistingEntryIsAnExplicitNoOp() {
        Work work = work();
        TimesheetContractResolver resolver = resolverWith(FIRST);
        doReturn(new PersistedAssociation("contract-a", FIRST, true))
                .when(resolver).findPersistedAssociation(work);

        assertEquals(ResolutionStatus.PAID_OUT_NOOP, resolver.resolve(work, "consultant").status());
    }

    private static TimesheetContractResolver resolverWith(EligibleContract... contracts) {
        TimesheetContractResolver resolver = spy(new TimesheetContractResolver());
        doReturn(List.of(contracts)).when(resolver).findEligibleCandidates(
                org.mockito.ArgumentMatchers.any(Work.class),
                org.mockito.ArgumentMatchers.anyString());
        doReturn(null).when(resolver).findPersistedAssociation(
                org.mockito.ArgumentMatchers.any(Work.class));
        return resolver;
    }

    private static Work work() {
        Work work = new Work();
        work.setTaskuuid("task");
        work.setRegistered(LocalDate.of(2026, 7, 10));
        return work;
    }
}
