package dk.trustworks.intranet.aggregates.conference.resources;

import dk.trustworks.intranet.aggregates.conference.services.ConferenceDownloadCatalog;
import dk.trustworks.intranet.aggregates.conference.services.ConferenceDownloadService;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConferenceDownloadResourceTest {
    @Test
    void anonymousPostReturnsNoBodyOrCount() {
        var resource = new ConferenceDownloadResource();
        resource.service = mock(ConferenceDownloadService.class);
        var response = resource.recordRequest(ConferenceDownloadCatalog.CONFERENCE_UUID, "fart-kontrol");
        assertEquals(204, response.getStatus());
        assertNull(response.getEntity());
        assertEquals("no-store", response.getHeaderString("Cache-Control"));
        assertNull(response.getHeaderString("Set-Cookie"));
        verify(resource.service).recordRequest(ConferenceDownloadCatalog.CONFERENCE_UUID, "fart-kontrol");
        verifyNoMoreInteractions(resource.service);
    }

    @Test
    void readReturnsAggregateDtoWithoutIncrementing() {
        var resource = new ConferenceDownloadResource();
        resource.service = mock(ConferenceDownloadService.class);
        var counts = new ConferenceDownloadService.DownloadCounts(
                ConferenceDownloadCatalog.CONFERENCE_UUID, "download_requests", List.of());
        when(resource.service.counts(ConferenceDownloadCatalog.CONFERENCE_UUID)).thenReturn(counts);
        var response = resource.counts(ConferenceDownloadCatalog.CONFERENCE_UUID);
        assertEquals(200, response.getStatus());
        assertSame(counts, response.getEntity());
        assertEquals("no-store", response.getHeaderString("Cache-Control"));
        verify(resource.service).counts(ConferenceDownloadCatalog.CONFERENCE_UUID);
        verifyNoMoreInteractions(resource.service);
    }

    @Test
    void onlyPostIsPublicAndTheReadRequiresConferenceScope() throws Exception {
        var write = ConferenceDownloadResource.class.getMethod("recordRequest", String.class, String.class);
        var read = ConferenceDownloadResource.class.getMethod("counts", String.class);
        assertNotNull(write.getAnnotation(POST.class));
        assertNotNull(write.getAnnotation(PermitAll.class));
        assertNull(write.getAnnotation(GET.class));
        assertNotNull(read.getAnnotation(GET.class));
        assertNull(read.getAnnotation(PermitAll.class));
        assertNull(ConferenceDownloadResource.class.getAnnotation(PermitAll.class));
        assertArrayEquals(new String[]{"conference:read"},
                ConferenceDownloadResource.class.getAnnotation(RolesAllowed.class).value());
    }
}
