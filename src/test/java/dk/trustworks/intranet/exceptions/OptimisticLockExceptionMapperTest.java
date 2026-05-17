package dk.trustworks.intranet.exceptions;

import jakarta.persistence.OptimisticLockException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class OptimisticLockExceptionMapperTest {

    private static final String EXPECTED_MESSAGE =
            "This expense was just updated by someone else. Please refresh and try again.";

    @Test
    void maps_OptimisticLockException_to_409_with_friendly_message() {
        OptimisticLockExceptionMapper mapper = new OptimisticLockExceptionMapper();

        Response response = mapper.toResponse(new OptimisticLockException("simulated stale write"));

        assertEquals(409, response.getStatus());
        assertEquals(MediaType.APPLICATION_JSON_TYPE, response.getMediaType());

        Object entity = response.getEntity();
        assertInstanceOf(ErrorResponse.class, entity);
        ErrorResponse body = (ErrorResponse) entity;
        assertEquals(EXPECTED_MESSAGE, body.error());
        assertEquals(409, body.status());
    }
}
