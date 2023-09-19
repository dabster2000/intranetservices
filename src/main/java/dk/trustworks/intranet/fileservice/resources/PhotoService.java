package dk.trustworks.intranet.fileservice.resources;

import dk.trustworks.intranet.fileservice.model.File;
import io.quarkus.narayana.jta.QuarkusTransaction;
import lombok.extern.jbosslog.JBossLog;
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

import javax.enterprise.context.ApplicationScoped;
import javax.transaction.Transactional;
import javax.ws.rs.PathParam;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

@ApplicationScoped
@JBossLog
public class PhotoService {

    @ConfigProperty(name = "bucket.files")
    String bucketName;

    Region regionNew = Region.EU_WEST_1;

    ProxyConfiguration.Builder proxyConfig = ProxyConfiguration.builder();
    ApacheHttpClient.Builder httpClientBuilder = ApacheHttpClient.builder().proxyConfiguration(proxyConfig.build());
    S3Client s3 = S3Client.builder().region(regionNew).httpClientBuilder(httpClientBuilder).build();

    public File findPhotoByType(String type) {
        List<File> photos = File.find("type like ?1", type).list();
        if(photos.size()>0) {
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
                //log.error("Error loading "+relateduuid+" from S3: "+e.awsErrorDetails().errorMessage(), e);
            } catch (Exception e) {
                //log.error(e.getMessage());
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
    public void delete(@PathParam("uuid") String uuid) {
        File.deleteById(uuid);
        //s3.deleteObject(DeleteObjectRequest.builder().bucket(bucketName).key(uuid).build());
    }

}