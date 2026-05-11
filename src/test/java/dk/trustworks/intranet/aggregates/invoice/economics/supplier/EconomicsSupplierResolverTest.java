package dk.trustworks.intranet.aggregates.invoice.economics.supplier;

import dk.trustworks.intranet.aggregates.invoice.economics.supplier.dto.SupplierDto;
import dk.trustworks.intranet.aggregates.invoice.economics.supplier.dto.SuppliersPage;
import dk.trustworks.intranet.aggregates.invoice.services.EconomicsAgreementResolver;
import dk.trustworks.intranet.aggregates.invoice.services.EconomicsAgreementResolver.Tokens;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EconomicsSupplierResolverTest {

    @InjectMocks EconomicsSupplierResolver resolver;

    @Mock EconomicsAgreementResolver agreementResolver;
    @Mock EconomicsSuppliersApiClient suppliersApi;

    private static final String DEBTOR_UUID = "debtor-1";
    private static final String ISSUER_CVR = "44232855";

    private void agreementTokens() {
        when(agreementResolver.tokens(DEBTOR_UUID))
                .thenReturn(new Tokens("app-secret", "agreement-grant"));
    }

    private SuppliersPage page(SupplierDto... items) {
        SuppliersPage p = new SuppliersPage();
        p.setCollection(List.of(items));
        return p;
    }

    private SupplierDto supplier(int number, String cvr) {
        SupplierDto s = new SupplierDto();
        s.setSupplierNumber(number);
        s.setCorporateIdentificationNumber(cvr);
        return s;
    }

    @Test
    void single_match_returns_supplier_number() {
        agreementTokens();
        when(suppliersApi.findByFilter("app-secret", "agreement-grant",
                "corporateIdentificationNumber$eq:" + ISSUER_CVR))
                .thenReturn(page(supplier(50007, ISSUER_CVR)));

        Optional<Integer> result = resolver.resolveByCvr(DEBTOR_UUID, ISSUER_CVR);

        assertTrue(result.isPresent());
        assertEquals(50007, result.get());
    }

    @Test
    void zero_matches_returns_empty() {
        agreementTokens();
        when(suppliersApi.findByFilter(anyString(), anyString(), anyString()))
                .thenReturn(page());

        Optional<Integer> result = resolver.resolveByCvr(DEBTOR_UUID, ISSUER_CVR);

        assertTrue(result.isEmpty());
    }

    @Test
    void multiple_matches_returns_empty() {
        agreementTokens();
        when(suppliersApi.findByFilter(anyString(), anyString(), anyString()))
                .thenReturn(page(supplier(50007, ISSUER_CVR), supplier(50008, ISSUER_CVR)));

        Optional<Integer> result = resolver.resolveByCvr(DEBTOR_UUID, ISSUER_CVR);

        assertTrue(result.isEmpty());
    }

    @Test
    void null_cvr_returns_empty_without_api_call() {
        Optional<Integer> result = resolver.resolveByCvr(DEBTOR_UUID, null);

        assertTrue(result.isEmpty());
        verifyNoInteractions(suppliersApi);
        verifyNoInteractions(agreementResolver);
    }

    @Test
    void blank_cvr_returns_empty_without_api_call() {
        Optional<Integer> result = resolver.resolveByCvr(DEBTOR_UUID, "   ");

        assertTrue(result.isEmpty());
        verifyNoInteractions(suppliersApi);
        verifyNoInteractions(agreementResolver);
    }

    @Test
    void api_exception_returns_empty() {
        agreementTokens();
        when(suppliersApi.findByFilter(anyString(), anyString(), anyString()))
                .thenThrow(new WebApplicationException(Response.serverError().build()));

        Optional<Integer> result = resolver.resolveByCvr(DEBTOR_UUID, ISSUER_CVR);

        assertTrue(result.isEmpty());
    }

    @Test
    void agreement_resolver_failure_returns_empty() {
        when(agreementResolver.tokens(DEBTOR_UUID))
                .thenThrow(new IllegalStateException("no integration keys"));

        Optional<Integer> result = resolver.resolveByCvr(DEBTOR_UUID, ISSUER_CVR);

        assertTrue(result.isEmpty());
        verifyNoInteractions(suppliersApi);
    }
}
