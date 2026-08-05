package dk.trustworks.intranet.aggregates.invoice.economics.customer;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract guard for {@link EconomicsContactApiClient} against the e-conomic
 * Customers API v3.1.0 OpenAPI spec
 * ({@code docs/finalized/external-apis/e-conomics-open-api/openapi-Customers.json}).
 *
 * <p>The spec's method surface for contacts differs from customers:
 * <ul>
 *   <li>{@code /Contacts/{number}} accepts only GET and DELETE. A PUT there is
 *       answered with 405 Method Not Allowed — which production hit on
 *       2026-08-04, silently dropping every contact update.</li>
 *   <li>Updates go to {@code PUT /Contacts} (operation {@code UpdateContactById})
 *       with the contact identity in the body's {@code number} field.</li>
 * </ul>
 *
 * These tests pin the client's verb/URL shape and the DTO's wire field names so
 * a regression fails the build instead of 405-ing in production.
 */
class EconomicsContactApiClientContractTest {

    private final ObjectMapper mapper = new ObjectMapper();

    // ----------------------- verb/URL shape -----------------------

    @Test
    void updateContact_puts_to_the_contacts_collection_url() throws Exception {
        Method m = EconomicsContactApiClient.class.getMethod("updateContact", EconomicsContactDto.class);
        assertNotNull(m.getAnnotation(PUT.class), "updateContact must use PUT");
        assertEquals("/Contacts", m.getAnnotation(Path.class).value(),
                "Contact updates must go to the /Contacts collection URL — "
                        + "/Contacts/{number} only accepts GET and DELETE (405 otherwise)");
    }

    @Test
    void no_method_puts_to_the_contacts_item_url() {
        for (Method m : EconomicsContactApiClient.class.getMethods()) {
            Path path = m.getAnnotation(Path.class);
            if (path == null || !path.value().contains("{")) continue;
            assertTrue(m.isAnnotationPresent(GET.class) || m.isAnnotationPresent(DELETE.class),
                    m.getName() + " targets item URL " + path.value()
                            + " with a verb the spec does not allow there (GET/DELETE only)");
        }
    }

    @Test
    void getContact_gets_the_item_url_and_createContact_posts_the_collection() throws Exception {
        Method get = EconomicsContactApiClient.class.getMethod("getContact", int.class);
        assertNotNull(get.getAnnotation(GET.class));
        assertEquals("/Contacts/{number}", get.getAnnotation(Path.class).value());

        Method post = EconomicsContactApiClient.class.getMethod("createContact", EconomicsContactDto.class);
        assertNotNull(post.getAnnotation(POST.class));
        assertEquals("/Contacts", post.getAnnotation(Path.class).value());
    }

    // ----------------------- DTO wire shape -----------------------

    @Test
    void dto_serialises_contact_identity_as_number() throws Exception {
        EconomicsContactDto dto = new EconomicsContactDto();
        dto.setCustomerContactNumber(777);
        dto.setCustomerNumber(101);
        dto.setName("Karin Spillemose");
        dto.setObjectVersion("v-fresh");

        String json = mapper.writeValueAsString(dto);

        assertTrue(json.contains("\"number\":777"),
                "PUT /Contacts addresses the contact via the body's 'number' field. Got: " + json);
        assertFalse(json.contains("customerContactNumber"),
                "'customerContactNumber' is not a Customers v3.1.0 wire field. Got: " + json);
    }

    @Test
    void dto_reads_contact_identity_from_number() throws Exception {
        // Response shape recorded from the live sandbox (Phase G0 probe
        // g0-3-contacts-filter.http.json).
        String json = "{\"number\":46,\"customerNumber\":128,\"name\":\"Karin Spillemose\","
                + "\"objectVersion\":\"1328f1018353b89b\",\"isDeleted\":false,\"active\":true}";

        EconomicsContactDto dto = mapper.readValue(json, EconomicsContactDto.class);

        assertEquals(46, dto.getCustomerContactNumber());
        assertEquals(128, dto.getCustomerNumber());
        assertEquals("1328f1018353b89b", dto.getObjectVersion());
    }
}
