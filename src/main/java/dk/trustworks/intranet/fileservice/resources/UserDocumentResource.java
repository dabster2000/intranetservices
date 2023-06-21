package dk.trustworks.intranet.fileservice.resources;

import dk.trustworks.intranet.fileservice.model.File;
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
import javax.ws.rs.QueryParam;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
@JBossLog
public class UserDocumentResource {

    @ConfigProperty(name = "bucket.files")
    String bucketName;

    Region regionNew = Region.EU_WEST_1;

    ProxyConfiguration.Builder proxyConfig = ProxyConfiguration.builder();
    ApacheHttpClient.Builder httpClientBuilder = ApacheHttpClient.builder().proxyConfiguration(proxyConfig.build());
    S3Client s3 = S3Client.builder().region(regionNew).httpClientBuilder(httpClientBuilder).build();

    public List<File> findDocuments() {
        log.debug("UserDocumentResource.findDocuments");
        return File.find("type like 'DOCUMENT'").list();
    }

    public List<File> findDocumentsByUserUUID(@QueryParam("useruuid") String useruuid) {
        log.debug("UserDocumentResource.findDocumentsByUserUUID");
        log.debug("useruuid = " + useruuid);
        return File.find("relateduuid like ?1 AND type like 'DOCUMENT'", useruuid).list();
    }

    public File findDocumentByUUID(@PathParam("uuid") String uuid) {
        log.debug("UserDocumentResource.findDocumentByUUID");
        log.debug("uuid = " + uuid);

        File file = File.findById(uuid);

        try{
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            s3.getObject(GetObjectRequest.builder().bucket(bucketName).key(uuid).build(), ResponseTransformer.toOutputStream(baos));
            byte[] bytes = baos.toByteArray();
            file.setFile(bytes);
            return file;//new File(uuid, "", "DOCUMENT", "", "", null, file);
        }  catch (S3Exception e) {
            log.error("Error loading "+uuid+" from S3: "+e.awsErrorDetails().errorMessage(), e);
            return file;//new File(uuid, "", "DOCUMENT", "", "", null, new byte[0]);
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
        //s3.deleteObject(DeleteObjectRequest.builder().bucket(bucketName).key(uuid).build());
    }
}