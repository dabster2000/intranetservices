package dk.trustworks.intranet.fileservice.resources;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.fileservice.model.File;
import io.quarkus.cache.CacheKey;
import io.quarkus.cache.CacheResult;
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
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
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

    // S3Client is thread-safe for operations, initialized once in constructor
    private final S3Client s3;

    public PhotoService() {
        // Initialize S3Client once as singleton (thread-safe)
        Region regionNew = Region.EU_WEST_1;
        ProxyConfiguration.Builder proxyConfig = ProxyConfiguration.builder();
        ApacheHttpClient.Builder httpClientBuilder = ApacheHttpClient.builder()
                .proxyConfiguration(proxyConfig.build());

        this.s3 = S3Client.builder()
                .region(regionNew)
                .httpClientBuilder(httpClientBuilder)
                .build();
    }

    @CacheResult(cacheName = "photo-cache")
    byte[] loadFromS3(@CacheKey String uuid) {
        log.debug("Fetching photo from S3 uuid=" + uuid);
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            s3.getObject(GetObjectRequest.builder().bucket(bucketName).key(uuid).build(), ResponseTransformer.toOutputStream(baos));
            return baos.toByteArray();
        } catch (S3Exception e) {
            log.error("Error loading " + uuid + " from S3: " + e.awsErrorDetails().errorMessage(), e);
            return new byte[0];
        }
    }

    public File findPhotoByType(String type) {
        log.debug("findPhotoByType type=" + type);
        List<File> photos = File.find("type = ?1", type).list();
        if(!photos.isEmpty()) {
            File photo = photos.get(new Random().nextInt(photos.size()));
            photo.setFile(loadFromS3(photo.getUuid()));
            return photo;
        } else {
            log.debug("Is not present");
            File newPhoto = new File(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "PHOTO");
            QuarkusTransaction.run(() -> File.persist(newPhoto));

            newPhoto.setFile(loadFromS3("c297e216-e5cf-437d-9a1f-de840c7557e9"));
            return newPhoto;
        }
    }

    public List<File> findPhotosByType(String type) {
        return File.find("type = ?1", type).list();
    }

    public File findPhotoByRelatedUUID(String relateduuid) {
        log.debug("findPhotoByRelatedUUID uuid=" + relateduuid);
        Optional<File> photo = File.find("relateduuid = ?1 AND type = 'PHOTO'", relateduuid).firstResultOptional();
        if(photo.isPresent()) {
            byte[] file = loadFromS3(photo.get().getUuid());
            photo.get().setFile(file);
            String mimeType = detectMimeType(file);
            if (!mimeType.equals("image/webp")) {
                return photo.get();
            }
        }
        return photo.orElseGet(() -> {
            File newPhoto = new File(UUID.randomUUID().toString(), relateduuid, "PHOTO");
            QuarkusTransaction.run(() -> File.persist(newPhoto));
            newPhoto.setFile(loadFromS3("c297e216-e5cf-437d-9a1f-de840c7557e9"));
            return newPhoto;
        });
    }

    public String detectMimeType(byte[] data) {
        Tika tika = new Tika();
        try {
            return tika.detect(data);
        } catch (Exception e) {
            log.error("Error detecting MIME type: " + e.getMessage());
            return "application/octet-stream"; // Default to binary data if detection fails
        }
    }

    public String extensionFromMimeType(String mimeType) {
        return MIME_TO_EXTENSION.getOrDefault(mimeType, ".bin");
    }

    private long getFileSize(byte[] fileData) {
        return fileData.length / 1024; // Convert bytes to kilobytes
    }

    private byte[] resizeWithClaid(byte[] data, int width) {
        log.debug("Resizing image with Claid to width=" + width);
        try {
            HttpClient httpClient = HttpClientBuilder.create().build();
            HttpPost uploadFile = new HttpPost("https://api.claid.ai/v1-beta1/image/edit/upload");
            MultipartEntityBuilder builder = MultipartEntityBuilder.create();
            builder.setBoundary("Boundary-Unique-Identifier");
            builder.setMode(HttpMultipartMode.BROWSER_COMPATIBLE);
            builder.addBinaryBody(
                    "file",
                    new ByteArrayInputStream(data),
                    ContentType.APPLICATION_OCTET_STREAM,
                    "photo"
            );

            // Build JSON body programmatically to keep it consistent with other Claid requests
            ObjectNode root = objectMapper.createObjectNode();
            ObjectNode operations = root.putObject("operations");
            ObjectNode restorations = operations.putObject("restorations");
            restorations.put("upscale", "faces");
            restorations.put("polish", false);
            ObjectNode resizing = operations.putObject("resizing");
            resizing.put("width", width);
            resizing.put("height", width);
            ObjectNode fit = resizing.putObject("fit");
            fit.put("crop", "smart");
            ObjectNode output = root.putObject("output");
            output.put("format", "webp");

            String jsonData = objectMapper.writeValueAsString(root);
            builder.addTextBody("data", jsonData, ContentType.APPLICATION_JSON);

            HttpEntity multipart = builder.build();
            uploadFile.setEntity(multipart);
            uploadFile.setHeader("Authorization", "Bearer " + claidApiKey);

            ResponseHandler<String> responseHandler = new BasicResponseHandler();
            String response = httpClient.execute(uploadFile, responseHandler);
            byte[] resized = downloadImageFromJson(response);
            log.debug("Claid resize complete, size=" + getFileSize(resized) + "KB");
            return resized;
        } catch (Exception e) {
            log.error("Claid resize failed", e);
            return data;
        }
    }

    private boolean s3ObjectExists(String key) {
        log.debug("Checking S3 for " + key);
        try {
            s3.headObject(HeadObjectRequest.builder().bucket(bucketName).key(key).build());
            log.debug("S3 hit for " + key);
            return true;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) return false;
            log.error("Error checking " + key + " on S3: " + e.awsErrorDetails().errorMessage(), e);
            return false;
        }
    }

    private String resizedKey(String uuid, int width) {
        return "resized/" + width + "/" + uuid;
    }

    private void saveToS3Async(String key, byte[] data) {
        CompletableFuture.runAsync(() -> {
            try {
                s3.putObject(PutObjectRequest.builder().bucket(bucketName).key(key).build(),
                        RequestBody.fromBytes(data));
                log.debug("Stored resized photo in S3 key=" + key);
            } catch (Exception e) {
                log.error("Failed to store resized photo in S3 key=" + key, e);
            }
        });
    }

    @CacheResult(cacheName = "photo-resize-cache")
    public byte[] getResizedPhoto(@CacheKey String relateduuid, @CacheKey int width) {
        String key = resizedKey(relateduuid, width);
        log.debug("Retrieving resized photo " + key);

        if (s3ObjectExists(key)) {
            return loadFromS3(key);
        }

        File photo = findPhotoByRelatedUUID(relateduuid);
        try {
            byte[] resized = resizeWithClaid(photo.getFile(), width);
            saveToS3Async(key, resized);
            return resized;
        } catch (Exception e) {
            log.error("Error resizing photo", e);
            return photo.getFile();
        }
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