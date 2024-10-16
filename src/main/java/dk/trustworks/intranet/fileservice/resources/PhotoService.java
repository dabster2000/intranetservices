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
import org.eclipse.microprofile.config.inject.ConfigProperty;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.http.apache.ProxyConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

@ApplicationScoped
@JBossLog
public class PhotoService {

    @ConfigProperty(name = "bucket.files")
    String bucketName;

    @ConfigProperty(name = "claid.ai.apikey")
    String claidApiKey;

    private static final String BASE_DIRECTORY = "/app/photos/";

    Region regionNew = Region.EU_WEST_1;

    ProxyConfiguration.Builder proxyConfig = ProxyConfiguration.builder();
    ApacheHttpClient.Builder httpClientBuilder = ApacheHttpClient.builder().proxyConfiguration(proxyConfig.build());
    S3Client s3 = S3Client.builder().region(regionNew).httpClientBuilder(httpClientBuilder).build();

    //@Inject
    //@RestClient
    //PhotoAPI service;
/*
    public File findPhotoByType(String type) {
        List<File> photos = File.find("type like ?1", type).list();
        if(!photos.isEmpty()) {
            File photo = photos.get(new Random().nextInt(photos.size()));
            try {
                Path photoPath = Paths.get(BASE_DIRECTORY + photo.getUuid());
                byte[] fileBytes = Files.readAllBytes(photoPath);
                photo.setFile(fileBytes);
            } catch (IOException e) {
                log.error("Error loading photo from local storage", e);
            }
            return photo;
        } else {
            // Handle case where no photo is found, maybe create a new one as per your current logic
            log.debug("No photo found, returning default photo.");
            return new File();
        }
    }*/

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
/*
    public File findPhotoByRelatedUUID(String relateduuid) {
        Optional<File> photo = File.find("relateduuid like ?1 AND type like 'PHOTO'", relateduuid).firstResultOptional();
        if (photo.isPresent()) {
            try {
                Path photoPath = Paths.get(BASE_DIRECTORY + photo.get().getUuid());
                byte[] fileBytes = Files.readAllBytes(photoPath);
                photo.get().setFile(fileBytes);
            } catch (IOException e) {
                log.error("Error loading photo from local storage", e);
            }
        }
        return photo.orElseGet(() -> {
            log.debug("Photo not found, creating new photo");
            File newPhoto = new File(UUID.randomUUID().toString(), relateduuid, "PHOTO");
            QuarkusTransaction.run(() -> File.persist(newPhoto));
            return newPhoto;
        });
    }*/


    public File findPhotoByRelatedUUID(String relateduuid) {
        log.debug("PhotoResource.findPhotoByUUID");
        log.debug("relateduuid = " + relateduuid);
        Optional<File> photo = File.find("relateduuid like ?1 AND type like 'PHOTO'", relateduuid).firstResultOptional();
        if(photo.isPresent()) {
            log.debug("Is present");
            try{
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                s3.getObject(GetObjectRequest.builder().bucket(bucketName).key(photo.get().getUuid()).build(), ResponseTransformer.toOutputStream(baos));
                byte[] file = baos.toByteArray();
                photo.get().setFile(file);
            }  catch (S3Exception e) {
                log.error("Error loading "+relateduuid+" from S3: "+e.awsErrorDetails().errorMessage(), e);
            } catch (Exception e) {
                log.error(e.getMessage());
            }
        }
        return photo.orElseGet(() -> {
            log.debug("Is not present");
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


/*
    @POST
    @Path("/photos")
    @Transactional
    public void save(File photo) {
        if(photo.getUuid().equals("")) photo.setUuid(UUID.randomUUID().toString());
        photo.setType("PHOTO");
        File.persist(photo);
        s3.putObject(PutObjectRequest.builder().bucket(bucketName).key(photo.getUuid()).build(),
                RequestBody.fromBytes(photo.getFile()));
    }

 */
/*
    @Transactional
    public void update(File photo) {
        if (photo.getUuid().isEmpty()) {
            photo.setUuid(UUID.randomUUID().toString());
        }

        // Delete the old photo
        File.delete("relateduuid like ?1", photo.getRelateduuid());

        // Persist the new photo
        if (photo.getType() == null || photo.getType().isEmpty()) {
            photo.setType("PHOTO");
        }
        File.persist(photo);

        // Save the file to local storage
        try {
            Path photoPath = Paths.get(BASE_DIRECTORY + photo.getUuid());
            Files.write(photoPath, photo.getFile());
        } catch (IOException e) {
            log.error("Error updating photo in local storage", e);
        }
    }*/

    @Transactional
    public void update(File photo) {
        if(photo.getUuid().equals("")) photo.setUuid(UUID.randomUUID().toString());

        File.delete("relateduuid like ?1", photo.getRelateduuid());

        if(photo.getType()==null || photo.getType().equals("")) photo.setType("PHOTO");
        File.persist(photo);

        s3.putObject(PutObjectRequest.builder().bucket(bucketName).key(photo.getUuid()).build(),
                RequestBody.fromBytes(photo.getFile()));
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
        String jsonData = "{\n" +
                "    \"operations\": {\n" +
                "        \"resizing\": {\n" +
                "            \"width\": 800,\n" +
                "            \"height\": 400,\n" +
                "            \"fit\": \"canvas\"\n" +
                "        }\n" +
                "    },\n" +
                "    \"output\": {\n" +
                "\t  \"metadata\": {\n" +
                "\t\t\t\"dpi\": 72\n" +
                "\t\t},\n" +
                "        \"format\": {\n" +
                "            \"type\": \"jpeg\",\n" +
                "            \"quality\": 80,\n" +
                "            \"progressive\": true\n" +
                "        }\n" +
                "    }\n" +
                "}"; // Your JSON string here
        builder.addTextBody("data", jsonData, ContentType.APPLICATION_JSON);

        HttpEntity multipart = builder.build();
        uploadFile.setEntity(multipart);
        uploadFile.setHeader("Authorization", "Bearer "+claidApiKey); //264d028e952a443ba5cbd1d086b24ca5

        // Letting HttpClient set the Content-Type
        ResponseHandler<String> responseHandler = new BasicResponseHandler();
        String response = httpClient.execute(uploadFile, responseHandler);

        photo.setFile(downloadImageFromJson(response));
        update(photo);
    }

    @Transactional
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
        String jsonData = "{\n" +
                "    \"operations\": {\n" +
                "        \"resizing\": {\n" +
                "            \"width\": 800,\n" +
                "            \"height\": 400,\n" +
                "            \"fit\": \"outpaint\"\n" +
                "        }\n" +
                "    },\n" +
                "    \"output\": {\n" +
                "\t  \"metadata\": {\n" +
                "\t\t\t\"dpi\": 72\n" +
                "\t\t},\n" +
                "        \"format\": {\n" +
                "            \"type\": \"jpeg\",\n" +
                "            \"quality\": 80,\n" +
                "            \"progressive\": true\n" +
                "        }\n" +
                "    }\n" +
                "}"; // Your JSON string here
        builder.addTextBody("data", jsonData, ContentType.APPLICATION_JSON);

        HttpEntity multipart = builder.build();
        uploadFile.setEntity(multipart);
        uploadFile.setHeader("Authorization", "Bearer "+claidApiKey); //264d028e952a443ba5cbd1d086b24ca5

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
/*
    @Transactional
    public void delete(String uuid) {
        File.deleteById(uuid);
        try {
            Path photoPath = Paths.get(BASE_DIRECTORY + uuid);
            Files.deleteIfExists(photoPath); // Remove file from local storage
        } catch (IOException e) {
            log.error("Error deleting photo from local storage", e);
        }
    }
     */



    @Transactional
    public void delete(String uuid) {
        File.deleteById(uuid);
        //s3.deleteObject(DeleteObjectRequest.builder().bucket(bucketName).key(uuid).build());
    }



}