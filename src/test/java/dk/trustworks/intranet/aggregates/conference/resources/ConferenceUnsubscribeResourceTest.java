package dk.trustworks.intranet.aggregates.conference.resources;

import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.aggregates.conference.services.ConferenceUnsubscribeService;
import jakarta.annotation.security.RolesAllowed;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConferenceUnsubscribeResourceTest {
    private ConferenceUnsubscribeResource resource;
    private ConferenceUnsubscribeService service;
    private static final String TOKEN = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
    @BeforeEach
    void setup() {
        service = mock(ConferenceUnsubscribeService.class);
        resource = new ConferenceUnsubscribeResource();
        resource.service = service;
        resource.mapper = new ObjectMapper();
    }
    private ByteArrayInputStream body(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void getOnlyReadsAndResponsesPreventCachingAndReferrers() {
        when(service.status(TOKEN)).thenReturn(new ConferenceUnsubscribeService.PublicState("TechTalk", "m••••@example.com", "NOT_UNSUBSCRIBED"));
        var response = resource.status(TOKEN);
        assertEquals(200, response.getStatus());
        assertEquals("no-store", response.getHeaderString("Cache-Control"));
        assertEquals("no-referrer", response.getHeaderString("Referrer-Policy"));
        assertTrue(response.getHeaderString("X-Robots-Tag").contains("noindex"));
        verify(service).status(TOKEN);
        verifyNoMoreInteractions(service);
    }

    @Test
    void onlyExactActionIsAcceptedAndIdentityInjectionNeverReachesMutation() {
        for (String invalid : new String[]{"{}", "null", "[]", "{\"action\":\"OTHER\"}", "{\"action\":\"UNSUBSCRIBE\",\"email\":\"other@example.com\"}",
                "{\"action\":\"UNSUBSCRIBE\"}{}", "{\"action\":\"UNSUBSCRIBE\",\"action\":\"UNSUBSCRIBE\"}", " ".repeat(129)}) {
            assertEquals(400, resource.unsubscribe(TOKEN, body(invalid)).getStatus(), invalid);
        }
        verifyNoInteractions(service);
    }

    @Test
    void storageFailureNeverReportsSuccess() {
        when(service.unsubscribe(TOKEN)).thenThrow(new IllegalStateException("CONFERENCE_POLICY_UNAVAILABLE"));
        var response = resource.unsubscribe(TOKEN, body("{\"action\":\"UNSUBSCRIBE\"}"));
        assertEquals(503, response.getStatus());
        assertEquals(Map.of("error", "UNAVAILABLE"), response.getEntity());
    }

    @Test
    void bothMethodsRequireDomainScopeInAdditionToToken() throws Exception {
        assertArrayEquals(new String[]{"conference:read"}, ConferenceUnsubscribeResource.class.getAnnotation(RolesAllowed.class).value());
        assertArrayEquals(new String[]{"conference:write"}, ConferenceUnsubscribeResource.class.getMethod("unsubscribe", String.class, java.io.InputStream.class)
                .getAnnotation(RolesAllowed.class).value());
        assertEquals(404, resource.unsubscribe("bad", body("{\"action\":\"UNSUBSCRIBE\"}")).getStatus());
        verifyNoInteractions(service);
    }
}
