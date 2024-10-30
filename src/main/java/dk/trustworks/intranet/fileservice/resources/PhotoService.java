package dk.trustworks.intranet.fileservice.resources;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.fileservice.model.File;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;
import org.apache.http.HttpEntity;
import org.apache.http.client.HttpClient;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.mime.HttpMultipartMode;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.impl.client.BasicResponseHandler;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.tika.Tika;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.http.apache.ProxyConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;

@ApplicationScoped
@JBossLog
public class PhotoService {

    private static final Map<String, String> MIME_TO_EXTENSION = new HashMap<>();

    static {
        // Image formats
        MIME_TO_EXTENSION.put("image/jpeg", ".jpg");
        MIME_TO_EXTENSION.put("image/jpg", ".jpg");
        MIME_TO_EXTENSION.put("image/png", ".png");
        MIME_TO_EXTENSION.put("image/gif", ".gif");
        MIME_TO_EXTENSION.put("image/bmp", ".bmp");
        MIME_TO_EXTENSION.put("image/tiff", ".tiff");
        MIME_TO_EXTENSION.put("image/webp", ".webp");
        MIME_TO_EXTENSION.put("image/svg+xml", ".svg");
        MIME_TO_EXTENSION.put("image/x-icon", ".ico");

        // Default binary
        MIME_TO_EXTENSION.put("application/octet-stream", ".bin");
    }

    @ConfigProperty(name = "bucket.files")
    String bucketName;

    @ConfigProperty(name = "claid.ai.apikey")
    String claidApiKey;

    Region regionNew = Region.EU_WEST_1;

    ProxyConfiguration.Builder proxyConfig = ProxyConfiguration.builder();
    ApacheHttpClient.Builder httpClientBuilder = ApacheHttpClient.builder().proxyConfiguration(proxyConfig.build());
    S3Client s3 = S3Client.builder().region(regionNew).httpClientBuilder(httpClientBuilder).build();

    public File findPhotoByType(String type) {
        List<File> photos = File.find("type like ?1", type).list();
        if(!photos.isEmpty()) {
            File photo = photos.get(new Random().nextInt(photos.size()));
            try{
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                s3.getObject(GetObjectRequest.builder().bucket(bucketName).key(photo.getUuid()).build(), ResponseTransformer.toOutputStream(baos));
                byte[] file = baos.toByteArray();
                photo.setFile(file);
            }  catch (S3Exception e) {
                //log.error("Error loading "+photo.getUuid()+" from S3: "+e.awsErrorDetails().errorMessage(), e);
            } catch (Exception e) {
                log.error(e.getMessage());
            }
            return photo;
        } else {
            log.debug("Is not present");
            File newPhoto = new File(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "PHOTO");
            QuarkusTransaction.run(() -> File.persist(newPhoto));

            try{
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                s3.getObject(GetObjectRequest.builder().bucket(bucketName).key("c297e216-e5cf-437d-9a1f-de840c7557e9").build(), ResponseTransformer.toOutputStream(baos));
                byte[] file = baos.toByteArray();

                newPhoto.setFile(file);
            }  catch (S3Exception e) {
                //log.error("Error loading "+type+" from S3: "+e.awsErrorDetails().errorMessage(), e);
            } catch (Exception e) {
                log.error(e.getMessage());
            }
            return newPhoto;
        }
    }

    public List<File> findPhotosByType(String type) {
        return File.find("type like ?1", type).list();
    }

    public File findPhotoByRelatedUUID(String relateduuid) {
        Optional<File> photo = File.find("relateduuid like ?1 AND type like 'PHOTO'", relateduuid).firstResultOptional();
        if(photo.isPresent()) {
            try {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                s3.getObject(GetObjectRequest.builder().bucket(bucketName).key(photo.get().getUuid()).build(), ResponseTransformer.toOutputStream(baos));
                byte[] file = baos.toByteArray();
                photo.get().setFile(file);

                long fileSizeKB = getFileSize(file);
                String mimeType = detectMimeType(file);

                if (!mimeType.equals("image/webp")) {
                    return photo.get();
                }
            } catch (S3Exception e) {
                log.error("Error loading "+relateduuid+" from S3: "+e.awsErrorDetails().errorMessage(), e);
            } catch (Exception e) {
                log.error(e.getMessage());
            }
        }
        return photo.orElseGet(() -> {
            File newPhoto = new File(UUID.randomUUID().toString(), relateduuid, "PHOTO");
            QuarkusTransaction.run(() -> File.persist(newPhoto));
            try{
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                s3.getObject(GetObjectRequest.builder().bucket(bucketName).key("c297e216-e5cf-437d-9a1f-de840c7557e9").build(), ResponseTransformer.toOutputStream(baos));
                byte[] file = baos.toByteArray();

                newPhoto.setFile(file);
            }  catch (S3Exception e) {
                log.error("Error loading default 'c297e216-e5cf-437d-9a1f-de840c7557e9' from S3: "+e.awsErrorDetails().errorMessage(), e);
            } catch (Exception e) {
                log.error(e.getMessage());
            }
            return newPhoto;
        });
    }

    private String detectMimeType(byte[] data) {
        Tika tika = new Tika();
        try {
            return tika.detect(data);
        } catch (Exception e) {
            log.error("Error detecting MIME type: " + e.getMessage());
            return "application/octet-stream"; // Default to binary data if detection fails
        }
    }

    private long getFileSize(byte[] fileData) {
        return fileData.length / 1024; // Convert bytes to kilobytes
    }

    private void update(File photo) {
        if(photo.getUuid().isEmpty()) {
            photo.setUuid(UUID.randomUUID().toString());
        }

        // First, try to find existing photo
        File existingPhoto = File.find("uuid", photo.getUuid()).firstResult();

        if(photo.getType()==null || photo.getType().isEmpty()) {
            photo.setType("PHOTO");
        }

        // If photo exists, update it instead of trying to insert
        if (existingPhoto != null) {
            // Use Panache's persist() for updates in Quarkus 3
            existingPhoto.setFilename(photo.getFilename());
            existingPhoto.setName(photo.getName());
            existingPhoto.setRelateduuid(photo.getRelateduuid());
            existingPhoto.setType(photo.getType());
            existingPhoto.setUploaddate(photo.getUploaddate());
        } else {
            // Delete old photos with same relateduuid
            File.delete("relateduuid = ?1", photo.getRelateduuid());
            // Insert new photo using Panache persist
            photo.persist();
        }

        // Upload to S3 regardless of whether it's an update or new insert
        s3.putObject(PutObjectRequest.builder()
                        .bucket(bucketName)
                        .key(photo.getUuid())
                        .build(),
                RequestBody.fromBytes(photo.getFile()));
    }

    @Transactional
    public void updatePhoto(File photo) throws IOException {
        HttpClient httpClient = HttpClientBuilder.create().build();
        HttpPost uploadFile = new HttpPost("https://api.claid.ai/v1-beta1/image/edit/upload");
        MultipartEntityBuilder builder = MultipartEntityBuilder.create();

        // Explicit boundary setting (if necessary)
        builder.setBoundary("Boundary-Unique-Identifier");
        builder.setMode(HttpMultipartMode.BROWSER_COMPATIBLE);

        // Adding file part
        builder.addBinaryBody(
                "file",
                new ByteArrayInputStream(photo.getFile()),
                ContentType.APPLICATION_OCTET_STREAM,
                photo.getName()
        );

        // Adding JSON data part
        String jsonData = """
                {
                    "operations": {
                        "restorations": {
                            "upscale": "smart_enhance"
                        },
                        "resizing": {
                            "width": 1600,
                            "height": 800,
                            "fit": "outpaint"
                        }
                    },
                    "output": {
                        "format": "webp"
                    }
                }""";
        builder.addTextBody("data", jsonData, ContentType.APPLICATION_JSON);

        HttpEntity multipart = builder.build();
        uploadFile.setEntity(multipart);
        uploadFile.setHeader("Authorization", "Bearer "+claidApiKey);

        // Letting HttpClient set the Content-Type
        ResponseHandler<String> responseHandler = new BasicResponseHandler();
        String response = httpClient.execute(uploadFile, responseHandler);

        photo.setFile(downloadImageFromJson(response));
        update(photo);
    }

    @Transactional
    public void updateLogo(File photo) throws IOException {
        HttpClient httpClient = HttpClientBuilder.create().build();
        HttpPost uploadFile = new HttpPost("https://api.claid.ai/v1-beta1/image/edit/upload");
        MultipartEntityBuilder builder = MultipartEntityBuilder.create();

        // Explicit boundary setting (if necessary)
        builder.setBoundary("Boundary-Unique-Identifier");
        builder.setMode(HttpMultipartMode.BROWSER_COMPATIBLE);

        // Adding file part
        builder.addBinaryBody(
                "file",
                new ByteArrayInputStream(photo.getFile()),
                ContentType.APPLICATION_OCTET_STREAM,
                photo.getName()
        );

        // Adding JSON data part
        String jsonData = """
                {
                    "operations": {
                        "restorations": {
                            "upscale": "smart_enhance"
                        },
                        "resizing": {
                            "width": 1600,
                            "height": 800,
                            "fit": "outpaint"
                        }
                    },
                    "output": {
                        "format": "webp"
                    }
                }""";
        builder.addTextBody("data", jsonData, ContentType.APPLICATION_JSON);

        HttpEntity multipart = builder.build();
        uploadFile.setEntity(multipart);
        uploadFile.setHeader("Authorization", "Bearer "+claidApiKey);

        // Letting HttpClient set the Content-Type
        ResponseHandler<String> responseHandler = new BasicResponseHandler();
        String response = httpClient.execute(uploadFile, responseHandler);

        photo.setFile(downloadImageFromJson(response));
        update(photo);
    }

    @Transactional
    //@CacheInvalidate(cacheName = "photo-cache")
    public void updatePortrait(File photo) throws IOException {
        HttpClient httpClient = HttpClientBuilder.create().build();
        HttpPost uploadFile = new HttpPost("https://api.claid.ai/v1-beta1/image/edit/upload");
        MultipartEntityBuilder builder = MultipartEntityBuilder.create();

        // Explicit boundary setting (if necessary)
        builder.setBoundary("Boundary-Unique-Identifier");
        builder.setMode(HttpMultipartMode.BROWSER_COMPATIBLE);

        // Adding file part
        builder.addBinaryBody(
                "file",
                new ByteArrayInputStream(photo.getFile()),
                ContentType.APPLICATION_OCTET_STREAM,
                photo.getName()
        );

        // Adding JSON data part
        String jsonData = """
                {
                    "operations": {
                        "restorations": {
                            "upscale": "faces"
                        },
                        "resizing": {
                            "width": 1600,
                            "height": 800,
                            "fit": "outpaint"
                        }
                    },
                    "output": {
                        "format": "webp"
                    }
                }""";
        builder.addTextBody("data", jsonData, ContentType.APPLICATION_JSON);

        HttpEntity multipart = builder.build();
        uploadFile.setEntity(multipart);
        uploadFile.setHeader("Authorization", "Bearer "+claidApiKey);

        // Letting HttpClient set the Content-Type
        ResponseHandler<String> responseHandler = new BasicResponseHandler();
        String response = httpClient.execute(uploadFile, responseHandler);

        log.info("Response: " + response);
        photo.setFile(downloadImageFromJson(response));
        update(photo);
        log.info("Photo updated");
    }

    private static final ObjectMapper objectMapper = new ObjectMapper();

    public byte[] downloadImageFromJson(String jsonResponse) {
        try {
            // Parse the JSON response
            JsonNode rootNode = objectMapper.readTree(jsonResponse);
            JsonNode tmpUrlNode = rootNode.path("data").path("output").path("tmp_url");
            String tmpUrl = tmpUrlNode.asText();

            if (tmpUrl == null || tmpUrl.isEmpty()) {
                throw new IllegalArgumentException("tmp_url is missing in the JSON response.");
            }

            // Fetch the image using a HTTP client
            Client client = ClientBuilder.newClient();
            Response response = client.target(tmpUrl)
                    .request(MediaType.APPLICATION_OCTET_STREAM)
                    .get();

            if (response.getStatus() != 200) {
                throw new RuntimeException("Failed to download image: HTTP error code " + response.getStatus());
            }

            // Convert the response to byte array
            byte[] imageBytes = response.readEntity(byte[].class);
            response.close(); // Important to close the response
            return imageBytes;

        } catch (IOException e) {
            throw new RuntimeException("Error processing JSON or fetching the image.", e);
        }
    }

    @Transactional
    public void delete(String uuid) {
        File.deleteById(uuid);
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucketName).key(uuid).build());
    }
}