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

import javax.enterprise.context.ApplicationScoped;
import javax.transaction.Transactional;
import javax.ws.rs.PathParam;
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

    Region regionNew = Region.EU_WEST_1;

    ProxyConfiguration.Builder proxyConfig = ProxyConfiguration.builder();
    ApacheHttpClient.Builder httpClientBuilder = ApacheHttpClient.builder().proxyConfiguration(proxyConfig.build());
    S3Client s3 = S3Client.builder().region(regionNew).httpClientBuilder(httpClientBuilder).build();

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
        File file = null;
        if(optionalFile.isPresent()) {
            file = optionalFile.get();
        } else {
            file = new File(uuid, "", "", "", "", null, null);
        }
        System.out.println("file = " + file);
        try{
            Tika tika = new Tika();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            s3.getObject(GetObjectRequest.builder().bucket(bucketName).key(uuid).build(), ResponseTransformer.toOutputStream(baos));
            byte[] bytes = baos.toByteArray();
            System.out.println("bytes.length = " + bytes.length);
            String fileType = tika.detect(bytes);
            System.out.println("fileType = " + fileType);
            file.setFile(bytes);
            file.setType(fileType);
            System.out.println("Returning...");
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
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucketName).key(uuid).build());
    }
}