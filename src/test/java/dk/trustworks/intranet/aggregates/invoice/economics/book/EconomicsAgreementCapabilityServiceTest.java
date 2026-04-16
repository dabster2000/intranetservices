package dk.trustworks.intranet.aggregates.invoice.economics.book;

import dk.trustworks.intranet.aggregates.invoice.services.EconomicsAgreementResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link EconomicsAgreementCapabilityService}.
 * Plain JUnit + Mockito -- no Quarkus boot required.
 *
 * SPEC-INV-001 section 4.2 requirement #5.
 */
class EconomicsAgreementCapabilityServiceTest {

    private static final String COMPANY_1 = "co-1";
    private static final String COMPANY_2 = "co-2";

    private EconomicsBookingApiClient bookApi;
    private EconomicsAgreementResolver agreements;

    private EconomicsAgreementCapabilityService service;

    @BeforeEach
    void setUp() {
        bookApi = mock(EconomicsBookingApiClient.class);
        agreements = mock(EconomicsAgreementResolver.class);

        when(agreements.tokens(any())).thenReturn(
                new EconomicsAgreementResolver.Tokens("secret", "grant"));

        service = new EconomicsAgreementCapabilityService(bookApi, agreements);
    }

    // ----------------------- cache behaviour -----------------------

    @Test
    void caches_canSendElectronicInvoice_per_company() {
        EconomicsAgreementSelf self = makeSelf(true);
        when(bookApi.getSelf(any(), any())).thenReturn(self);

        assertTrue(service.canSendElectronicInvoice(COMPANY_1));
        assertTrue(service.canSendElectronicInvoice(COMPANY_1));

        // API called only once for the same company -- second call served from cache.
        verify(bookApi, times(1)).getSelf(any(), any());
    }

    @Test
    void separate_companies_get_separate_cache_entries() {
        EconomicsAgreementSelf selfTrue = makeSelf(true);
        EconomicsAgreementSelf selfFalse = makeSelf(false);
        when(bookApi.getSelf(any(), any())).thenReturn(selfTrue, selfFalse);

        assertTrue(service.canSendElectronicInvoice(COMPANY_1));
        assertFalse(service.canSendElectronicInvoice(COMPANY_2));

        // Two distinct companies means two API calls.
        verify(bookApi, times(2)).getSelf(any(), any());
    }

    // ----------------------- canSendElectronicInvoice -----------------------

    @Test
    void returns_true_when_capability_is_true() {
        when(bookApi.getSelf(any(), any())).thenReturn(makeSelf(true));

        assertTrue(service.canSendElectronicInvoice(COMPANY_1));
    }

    @Test
    void returns_false_when_capability_is_false() {
        when(bookApi.getSelf(any(), any())).thenReturn(makeSelf(false));

        assertFalse(service.canSendElectronicInvoice(COMPANY_1));
    }

    @Test
    void returns_false_when_capability_is_null() {
        when(bookApi.getSelf(any(), any())).thenReturn(makeSelf(null));

        assertFalse(service.canSendElectronicInvoice(COMPANY_1));
    }

    // ----------------------- selfOf -----------------------

    @Test
    void selfOf_returns_cached_agreement_self() {
        EconomicsAgreementSelf self = makeSelf(true);
        self.setCompanyVatNumber("12345678");
        self.setAgreementNumber(42);
        when(bookApi.getSelf(any(), any())).thenReturn(self);

        EconomicsAgreementSelf result = service.selfOf(COMPANY_1);

        assertNotNull(result);
        assertEquals("12345678", result.getCompanyVatNumber());
        assertEquals(42, result.getAgreementNumber());
        assertTrue(result.getCanSendElectronicInvoice());
    }

    @Test
    void selfOf_uses_cache_on_second_call() {
        when(bookApi.getSelf(any(), any())).thenReturn(makeSelf(true));

        service.selfOf(COMPANY_1);
        service.selfOf(COMPANY_1);

        verify(bookApi, times(1)).getSelf(any(), any());
    }

    @Test
    void selfOf_and_canSendElectronicInvoice_share_cache() {
        when(bookApi.getSelf(any(), any())).thenReturn(makeSelf(true));

        // Load via selfOf, then check capability -- should not trigger a second API call.
        service.selfOf(COMPANY_1);
        assertTrue(service.canSendElectronicInvoice(COMPANY_1));

        verify(bookApi, times(1)).getSelf(any(), any());
    }

    // ----------------------- token resolution -----------------------

    @Test
    void passes_resolved_tokens_to_the_api_client() {
        when(agreements.tokens(COMPANY_1)).thenReturn(
                new EconomicsAgreementResolver.Tokens("my-secret", "my-grant"));
        when(bookApi.getSelf("my-secret", "my-grant")).thenReturn(makeSelf(true));

        service.canSendElectronicInvoice(COMPANY_1);

        verify(bookApi).getSelf("my-secret", "my-grant");
    }

    // ----------------------- helpers -----------------------

    private static EconomicsAgreementSelf makeSelf(Boolean canSendElectronic) {
        EconomicsAgreementSelf self = new EconomicsAgreementSelf();
        self.setCanSendElectronicInvoice(canSendElectronic);
        return self;
    }
}
