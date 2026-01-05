package dk.trustworks.intranet.apigateway.resources.gate;

import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.dao.crm.services.ClientService;
import dk.trustworks.intranet.fileservice.model.File;
import dk.trustworks.intranet.fileservice.resources.PhotoService;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PublicResourceTest {

    private PublicResource resource;
    private ClientService clientService;
    private PhotoService photoService;

    @BeforeEach
    void setup() {
        resource = new PublicResource();
        clientService = Mockito.mock(ClientService.class);
        photoService = Mockito.mock(PhotoService.class);

        resource.clientAPI = clientService;
        resource.photoAPI = photoService;
    }

    @Test
    void updateClientLogoSavesPhotoWithSanitizedFilename() throws Exception {
        String clientuuid = "client-uuid";
        Client client = new Client();
        client.setUuid(clientuuid);
        client.setName("Acme inc!");

        Mockito.when(clientService.findByUuid(clientuuid)).thenReturn(client);

        byte[] bytes = "hello".getBytes(StandardCharsets.UTF_8);
        String base64 = Base64.getEncoder().encodeToString(bytes);

        Mockito.when(photoService.detectMimeType(bytes)).thenReturn("image/png");
        Mockito.when(photoService.extensionFromMimeType("image/png")).thenReturn(".png");

        ArgumentCaptor<File> captor = ArgumentCaptor.forClass(File.class);
        Mockito.doNothing().when(photoService).updateLogo(captor.capture());

        PublicResource.UpdateClientLogoRequest request = new PublicResource.UpdateClientLogoRequest();
        request.setFile(base64);

        Response response = resource.updateClientLogo(clientuuid, request);

        assertEquals(Response.Status.NO_CONTENT.getStatusCode(), response.getStatus());

        File saved = captor.getValue();
        assertEquals(clientuuid, saved.getRelateduuid());
        assertEquals("PHOTO", saved.getType());
        assertEquals("Acme inc!", saved.getName());
        assertEquals("Acme_inc_.png", saved.getFilename());
    }

    @Test
    void updateClientLogoRejectsInvalidBase64() {
        String clientuuid = "client-uuid";
        Client client = new Client();
        client.setUuid(clientuuid);
        client.setName("Client");

        Mockito.when(clientService.findByUuid(clientuuid)).thenReturn(client);

        PublicResource.UpdateClientLogoRequest request = new PublicResource.UpdateClientLogoRequest();
        request.setFile("not-base64");

        WebApplicationException ex = assertThrows(WebApplicationException.class,
                () -> resource.updateClientLogo(clientuuid, request));

        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), ex.getResponse().getStatus());
    }

    @Test
    void updateClientLogoReturns404WhenClientMissing() {
        String clientuuid = "missing";
        Mockito.when(clientService.findByUuid(clientuuid)).thenReturn(null);

        PublicResource.UpdateClientLogoRequest request = new PublicResource.UpdateClientLogoRequest();
        request.setFile(Base64.getEncoder().encodeToString("hello".getBytes(StandardCharsets.UTF_8)));

        WebApplicationException ex = assertThrows(WebApplicationException.class,
                () -> resource.updateClientLogo(clientuuid, request));

        assertEquals(Response.Status.NOT_FOUND.getStatusCode(), ex.getResponse().getStatus());
    }

    @Test
    void updateClientLogoRequiresFile() {
        PublicResource.UpdateClientLogoRequest request = new PublicResource.UpdateClientLogoRequest();

        WebApplicationException ex = assertThrows(WebApplicationException.class,
                () -> resource.updateClientLogo("client-uuid", request));

        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), ex.getResponse().getStatus());
    }
}