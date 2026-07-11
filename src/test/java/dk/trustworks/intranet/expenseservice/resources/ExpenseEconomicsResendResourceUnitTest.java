package dk.trustworks.intranet.expenseservice.resources;

import dk.trustworks.intranet.expenseservice.dto.ExpenseResendRequestDTO;
import dk.trustworks.intranet.expenseservice.services.ExpenseEconomicResendService;
import dk.trustworks.intranet.security.RequestHeaderHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExpenseEconomicsResendResourceUnitTest {

    private static final String UUID = "11111111-2222-3333-4444-555555555555";

    @Mock
    ExpenseEconomicResendService resendService;

    @Mock
    RequestHeaderHolder header;

    ExpenseEconomicsResendResource resource;

    @BeforeEach
    void setUp() {
        resource = new ExpenseEconomicsResendResource();
        resource.resend = resendService;
        resource.header = header;
    }

    @Test
    void resendDoesNotExposeProviderErrorDetails() {
        when(header.getUserUuid()).thenReturn("accountant");
        doThrow(new RuntimeException("arn:aws:sts::123456789012:assumed-role/example cannot s3:GetObject"))
                .when(resendService).resendOne(UUID, "accountant");

        var result = resource.resend(new ExpenseResendRequestDTO(List.of(UUID)));

        assertEquals(1, result.failed().size());
        assertEquals("re-send failed", result.failed().getFirst().error());
    }
}
