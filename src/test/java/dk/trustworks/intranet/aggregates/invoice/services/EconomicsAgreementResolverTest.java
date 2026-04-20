package dk.trustworks.intranet.aggregates.invoice.services;

import dk.trustworks.intranet.aggregates.invoice.economics.VatZoneMapping;
import dk.trustworks.intranet.aggregates.invoice.economics.VatZoneMappingRepository;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link EconomicsAgreementResolver#vatZoneDetailsFor}.
 * Plain JUnit + Mockito — no Quarkus boot required.
 */
class EconomicsAgreementResolverTest {

    private VatZoneMappingRepository vatZoneRepo;
    private dk.trustworks.intranet.aggregates.invoice.economics.PaymentTermsMappingRepository paymentTermsRepo;
    private EconomicsAgreementResolver resolver;

    @BeforeEach
    void setUp() {
        vatZoneRepo = mock(VatZoneMappingRepository.class);
        paymentTermsRepo = mock(dk.trustworks.intranet.aggregates.invoice.economics.PaymentTermsMappingRepository.class);
        resolver = new EconomicsAgreementResolver();
        resolver.vatZoneRepo = vatZoneRepo;
        resolver.paymentTermsRepo = paymentTermsRepo;
    }

    @Test
    void paymentTermFor_throwsWhenMappingCompanyMismatchesContractCompany() {
        dk.trustworks.intranet.model.Company contractCompany = new dk.trustworks.intranet.model.Company();
        contractCompany.setUuid("C-CONTRACT");
        contractCompany.setName("Trustworks A/S");

        dk.trustworks.intranet.model.Company otherCompany = new dk.trustworks.intranet.model.Company();
        otherCompany.setUuid("C-OTHER");
        otherCompany.setName("Trustworks Technology ApS");

        dk.trustworks.intranet.aggregates.invoice.economics.PaymentTermsMapping mapping =
                new dk.trustworks.intranet.aggregates.invoice.economics.PaymentTermsMapping();
        mapping.setUuid("pt-1");
        mapping.setCompany(otherCompany);
        mapping.setEconomicsPaymentTermsNumber(5);
        when(paymentTermsRepo.findById("pt-1")).thenReturn(mapping);

        dk.trustworks.intranet.contracts.model.Contract c = new dk.trustworks.intranet.contracts.model.Contract();
        c.setName("C1");
        c.setCompany(contractCompany);
        c.setPaymentTermsUuid("pt-1");

        BadRequestException ex = assertThrows(BadRequestException.class, () -> resolver.paymentTermFor(c));
        assertTrue(ex.getMessage().toLowerCase().contains("payment terms"));
        assertTrue(ex.getMessage().contains("Trustworks A/S"));
        assertTrue(ex.getMessage().contains("Trustworks Technology ApS"));
    }

    @Test
    void vatZoneDetailsForReturnsNumberAndRate() {
        VatZoneMapping m = new VatZoneMapping();
        m.setEconomicsVatZoneNumber(7);
        m.setVatRatePercent(new BigDecimal("19.00"));
        when(vatZoneRepo.findByCurrency("XTS", null)).thenReturn(Optional.of(m));

        EconomicsAgreementResolver.VatZoneDetails details =
                resolver.vatZoneDetailsFor("XTS", null);

        assertEquals(7, details.economicsVatZoneNumber());
        assertEquals(0, new BigDecimal("19.00").compareTo(details.vatRatePercent()));
    }

    @Test
    void vatZoneDetailsForThrowsBadRequestWhenMissing() {
        when(vatZoneRepo.findByCurrency("ZZZ", null)).thenReturn(Optional.empty());

        BadRequestException ex = assertThrows(
                BadRequestException.class,
                () -> resolver.vatZoneDetailsFor("ZZZ", null));
        assertTrue(ex.getMessage().contains("VAT zone"));
        assertTrue(ex.getMessage().contains("ZZZ"));
    }
}
