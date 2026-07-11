package dk.trustworks.intranet.apigateway.resources;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * C3: GET /contracts/count-by-type/{code} must return {@code {"count": n}}
 * instead of the previous bare JSON number (framework-agreements redesign §9.2).
 * The FE BFF tolerates both shapes; this pins the new one.
 *
 * Plain JUnit — serializes the response record directly with Jackson.
 */
class ContractCountResponseTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void serializesAsCountObject() throws Exception {
        String json = MAPPER.writeValueAsString(new ContractResource.ContractCountResponse(42L));
        assertEquals("{\"count\":42}", json);
    }

    @Test
    void serializesZero() throws Exception {
        String json = MAPPER.writeValueAsString(new ContractResource.ContractCountResponse(0L));
        assertEquals("{\"count\":0}", json);
    }
}
