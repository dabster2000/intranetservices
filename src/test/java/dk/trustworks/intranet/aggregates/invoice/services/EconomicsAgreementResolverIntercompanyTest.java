package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.economics.IntercompanyAccountMapping;
import dk.trustworks.intranet.aggregates.invoice.economics.IntercompanyAccountMappingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Pure Mockito unit test for EconomicsAgreementResolver#intercompanyCostAccount.
 * No DB / no Quarkus container. The resolver's other @Inject fields
 * (paymentTermsRepo, vatZoneRepo, em) are left null — this method uses only
 * intercompanyAccountRepo.
 */
@ExtendWith(MockitoExtension.class)
class EconomicsAgreementResolverIntercompanyTest {

    private static final String DEBTOR = "d8894494-2fb4-4f72-9e05-e6032e6dd691"; // A/S
    private static final String ISSUER = "44592d3b-2be5-4b29-bfaf-4fafc60b0fa3"; // Technology

    @InjectMocks
    EconomicsAgreementResolver resolver;

    @Mock
    IntercompanyAccountMappingRepository intercompanyAccountRepo;

    @Test
    void returns_mapped_account_when_pair_is_mapped() {
        IntercompanyAccountMapping m = new IntercompanyAccountMapping();
        m.setEconomicsCostAccountNumber(3050);
        when(intercompanyAccountRepo.findByDebtorAndIssuer(DEBTOR, ISSUER))
                .thenReturn(Optional.of(m));

        assertEquals(Optional.of(3050), resolver.intercompanyCostAccount(DEBTOR, ISSUER));
    }

    @Test
    void returns_empty_when_pair_is_unmapped() {
        when(intercompanyAccountRepo.findByDebtorAndIssuer(DEBTOR, ISSUER))
                .thenReturn(Optional.empty());

        assertTrue(resolver.intercompanyCostAccount(DEBTOR, ISSUER).isEmpty());
    }

    @Test
    void returns_empty_and_skips_repo_when_either_uuid_is_null() {
        assertTrue(resolver.intercompanyCostAccount(null, ISSUER).isEmpty());
        assertTrue(resolver.intercompanyCostAccount(DEBTOR, null).isEmpty());
        verifyNoInteractions(intercompanyAccountRepo);
    }
}
