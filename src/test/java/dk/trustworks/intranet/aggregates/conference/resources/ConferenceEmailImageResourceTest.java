package dk.trustworks.intranet.aggregates.conference.resources;

import dk.trustworks.intranet.fileservice.model.File;
import dk.trustworks.intranet.fileservice.resources.PhotoService;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConferenceEmailImageResourceTest {

    @Mock
    PhotoService photoService;

    ConferenceResource resource;

    @BeforeEach
    void setUp() {
        resource = new ConferenceResource();
        resource.photoService = photoService;
    }

    private static String b64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    @Test
    void uploadRejectsMissingFile() {
        var e = assertThrows(WebApplicationException.class, () ->
                resource.uploadEmailImage(new ConferenceResource.EmailImageUploadRequest("logo.png", null)));
        assertEquals(400, e.getResponse().getStatus());
        verify(photoService, never()).storeEmailImage(any());
    }

    @Test
    void uploadRejectsInvalidBase64() {
        var e = assertThrows(WebApplicationException.class, () ->
                resource.uploadEmailImage(new ConferenceResource.EmailImageUploadRequest("logo.png", "not@base64!!")));
        assertEquals(400, e.getResponse().getStatus());
        verify(photoService, never()).storeEmailImage(any());
    }

    @Test
    void uploadRejectsNonImagePayload() {
        byte[] pdf = "%PDF-1.4 not an image".getBytes();
        when(photoService.detectMimeType(pdf)).thenReturn("application/pdf");
        var e = assertThrows(WebApplicationException.class, () ->
                resource.uploadEmailImage(new ConferenceResource.EmailImageUploadRequest("evil.pdf", b64(pdf))));
        assertEquals(400, e.getResponse().getStatus());
        verify(photoService, never()).storeEmailImage(any());
    }

    @Test
    void uploadRejectsOversizedImage() {
        byte[] tooBig = new byte[5 * 1024 * 1024 + 1];
        var e = assertThrows(WebApplicationException.class, () ->
                resource.uploadEmailImage(new ConferenceResource.EmailImageUploadRequest("huge.png", b64(tooBig))));
        assertEquals(413, e.getResponse().getStatus());
        verify(photoService, never()).storeEmailImage(any());
    }

    @Test
    void uploadStoresImmutableEmailImageRow() {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};
        when(photoService.detectMimeType(png)).thenReturn("image/png");
        when(photoService.extensionFromMimeType("image/png")).thenReturn(".png");

        var response = resource.uploadEmailImage(
                new ConferenceResource.EmailImageUploadRequest("Logo Final.PNG", b64(png)));

        ArgumentCaptor<File> stored = ArgumentCaptor.forClass(File.class);
        verify(photoService).storeEmailImage(stored.capture());
        File image = stored.getValue();
        assertNotNull(image.getUuid());
        assertEquals(response.uuid(), image.getUuid());
        assertEquals("EMAIL_IMAGE", image.getType());
        assertEquals("Logo_Final.png", image.getFilename());
    }

    @Test
    void getReturns404ForUnknownUuid() {
        when(photoService.findEmailImage("nope")).thenReturn(null);
        var e = assertThrows(WebApplicationException.class, () -> resource.getEmailImage("nope"));
        assertEquals(404, e.getResponse().getStatus());
    }

    @Test
    void getReturns404WhenS3BytesAreMissing() {
        File row = new File();
        row.setUuid("u1");
        row.setType("EMAIL_IMAGE");
        row.setFile(new byte[0]);
        when(photoService.findEmailImage("u1")).thenReturn(row);
        var e = assertThrows(WebApplicationException.class, () -> resource.getEmailImage("u1"));
        assertEquals(404, e.getResponse().getStatus());
    }

    @Test
    void getServesBytesWithDetectedTypeAndImmutableCaching() {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 9, 9};
        File row = new File();
        row.setUuid("u2");
        row.setType("EMAIL_IMAGE");
        row.setFile(png);
        when(photoService.findEmailImage("u2")).thenReturn(row);
        when(photoService.detectMimeType(png)).thenReturn("image/png");

        Response response = resource.getEmailImage("u2");

        assertEquals(200, response.getStatus());
        assertEquals("image/png", response.getMediaType().toString());
        assertEquals("public, max-age=31536000, immutable", response.getHeaderString("Cache-Control"));
        assertEquals("nosniff", response.getHeaderString("X-Content-Type-Options"));
        assertEquals(png, response.getEntity());
    }
}
