package dk.trustworks.intranet.resources;

import dk.trustworks.intranet.model.RegistrationRequest;
import dk.trustworks.intranet.services.GuestRegistrationService;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Guards the fix for the production incident of 2026-08-20, where the reception
 * kiosk posted a guest whose host had been free-typed rather than picked from
 * the dropdown:
 *
 *   {"guestName":"...","employee":"Ditte Hjorth","employeeId":null}
 *
 * The null uuid travelled through the service into {@code User.findById(null)},
 * which Hibernate rejects with {@code IllegalArgumentException: Identifier may
 * not be null}. Nothing mapped that, so {@code GenericExceptionMapper} turned a
 * malformed request into an HTTP 500 — and the guest, seeing a failure, simply
 * tried again and got a second 500 nine seconds later.
 *
 * A missing employeeId is a bad request, not a server fault: it must be a 400
 * carrying a message the caller can act on, and the service must never be
 * invoked with it.
 */
@ExtendWith(MockitoExtension.class)
class GuestRegistrationResourceTest {

    @Mock
    private GuestRegistrationService service;

    @InjectMocks
    private GuestRegistrationResource resource;

    /**
     * The regression guard. On the old resource this test fails: register()
     * delegated straight to the service, which threw IllegalArgumentException
     * (mapped to 500) rather than BadRequestException (mapped to 400).
     */
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "\t"})
    void register_rejectsMissingEmployeeId_asBadRequestAndNeverCallsService(String employeeId) {
        RegistrationRequest request = request("Rasmus Helmer-Villadsen", "Konsulent", "Ditte Hjorth", employeeId);

        BadRequestException thrown = assertThrows(BadRequestException.class, () -> resource.register(request));

        assertEquals(400, thrown.getResponse().getStatus(), "a malformed payload must not surface as a 500");
        assertEquals("employeeId is required", thrown.getMessage());
        verify(service, never()).register(request);
    }

    @Test
    void register_rejectsNullBody_asBadRequest() {
        BadRequestException thrown = assertThrows(BadRequestException.class, () -> resource.register(null));

        assertEquals(400, thrown.getResponse().getStatus());
        assertEquals("Request body is required", thrown.getMessage());
        verify(service, never()).register(null);
    }

    /**
     * The happy path the four successful check-ins of 2026-08-20 took, where the
     * guest picked the host from the dropdown and a real uuid came through.
     */
    @Test
    void register_withResolvedEmployeeId_delegatesToService() {
        RegistrationRequest request = request(
                "Casper Jensen", "Pandora", "Jakob Geitner-Andersen", "fb49badd-cc78-46c5-8ac7-1c447a4053cd");

        resource.register(request);

        verify(service).register(request);
    }

    private static RegistrationRequest request(String guestName, String company, String employee, String employeeId) {
        RegistrationRequest request = new RegistrationRequest();
        request.setGuestName(guestName);
        request.setGuestCompany(company);
        request.setEmployee(employee);
        request.setEmployeeId(employeeId);
        return request;
    }
}
