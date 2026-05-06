package dk.trustworks.intranet.fileservice.services;

import dk.trustworks.intranet.fileservice.model.File;
import lombok.extern.jbosslog.JBossLog;
import org.apache.tika.Tika;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.http.apache.ProxyConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.PathParam;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static dk.trustworks.intranet.utils.DateUtils.ConvertInstantToLocalDate;

@ApplicationScoped
@JBossLog
public class S3FileService {

    @ConfigProperty(name = "bucket.files")
    String bucketName;

    @Inject
    EntityManager em;

    // S3Client is thread-safe for operations, initialized once in constructor
    private final S3Client s3;

    public S3FileService() {
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

    public List<File> findAll() {
        ListObjectsRequest listObjects = ListObjectsRequest.builder().bucket(bucketName).build();
        ListObjectsResponse res = s3.listObjects(listObjects);

        List<File> files = new ArrayList<>();
        for (S3Object content : res.contents()) {
            String key = content.key();
            Optional<File> optionalFile = File.findByIdOptional(key);
            if(optionalFile.isPresent()) {
                files.add(optionalFile.get());
            } else {
                File one = findOne(content.key());
                files.add(new File(content.key(), "", one.getType(), content.toString(), "UNMAPPED", ConvertInstantToLocalDate(content.lastModified()), null));
            }
        }

        return files;
    }

    public File findOne(String uuid) {
        Optional<File> optionalFile = File.findByIdOptional(uuid);
        boolean transientFile = optionalFile.isEmpty();
        File file;
        if (transientFile) {
            file = new File(uuid, "", "", "", "", null, null);
        } else {
            file = optionalFile.get();
            // Detach so the byte payload (and any incidental setter below) is not
            // dirty-flushed when the caller's transaction commits. The DB columns
            // are narrow (e.g. files.type VARCHAR(20)) and Tika MIME strings can
            // exceed them, which previously caused "Data too long for column
            // 'type'" errors during recruitment PDF generation.
            em.detach(file);
        }
        try {
            Tika tika = new Tika();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            s3.getObject(GetObjectRequest.builder().bucket(bucketName).key(uuid).build(), ResponseTransformer.toOutputStream(baos));
            byte[] bytes = baos.toByteArray();
            file.setFile(bytes);
            if (transientFile) {
                // Only populate `type` for synthetic instances that aren't tracked in DB.
                // Persisted rows keep their stored category ("DOCUMENT", "WORD_TEMPLATE", "PHOTO").
                file.setType(tika.detect(bytes));
            }
            return file;
        } catch (S3Exception e) {
            log.error("Error loading "+uuid+" from S3: "+e.awsErrorDetails().errorMessage(), e);
            return file;
        }
    }


    @Transactional
    public void save(File document) {
        if(document.getUuid()==null || document.getUuid().equals("")) document.setUuid(UUID.randomUUID().toString());
        document.setType("DOCUMENT");
        File.persist(document);
        s3.putObject(PutObjectRequest.builder().bucket(bucketName).key(document.getUuid()).build(),
                RequestBody.fromBytes(document.getFile()));
    }

    @Transactional
    public void delete(@PathParam("uuid") String uuid) {
        File.deleteById(uuid);
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucketName).key(uuid).build());
    }
}