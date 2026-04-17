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
    private EconomicsAgreementResolver resolver;

    @BeforeEach
    void setUp() {
        vatZoneRepo = mock(VatZoneMappingRepository.class);
        resolver = new EconomicsAgreementResolver();
        resolver.vatZoneRepo = vatZoneRepo;
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
