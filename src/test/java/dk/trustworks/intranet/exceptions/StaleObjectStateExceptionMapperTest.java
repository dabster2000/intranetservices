package dk.trustworks.intranet.exceptions;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.hibernate.StaleObjectStateException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class StaleObjectStateExceptionMapperTest {

    @Test
    void maps_StaleObjectStateException_to_409_with_friendly_message() {
        StaleObjectStateExceptionMapper mapper = new StaleObjectStateExceptionMapper();

        Response response = mapper.toResponse(new StaleObjectStateException("Expense", "some-uuid"));

        assertEquals(409, response.getStatus());
        assertEquals(MediaType.APPLICATION_JSON_TYPE, response.getMediaType());

        Object entity = response.getEntity();
        assertInstanceOf(ErrorResponse.class, entity);
        ErrorResponse body = (ErrorResponse) entity;
        assertEquals(OptimisticLockExceptionMapper.STALE_WRITE_MESSAGE, body.error());
        assertEquals(409, body.status());
    }
}
