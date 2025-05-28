package dk.trustworks.intranet.expenseservice.services;

import dk.trustworks.intranet.dto.ExpenseFile;
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
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

import jakarta.enterprise.context.RequestScoped;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

@JBossLog
@RequestScoped
public class ExpenseFileService {

    @ConfigProperty(name = "bucket.expenses")
    String bucketName;

    // Region regionNew = Region.EU_WEST_1;
    Region regionNew = Region.EU_WEST_1;

    ProxyConfiguration.Builder proxyConfig = ProxyConfiguration.builder();
    ApacheHttpClient.Builder httpClientBuilder = ApacheHttpClient.builder().proxyConfiguration(proxyConfig.build());
    S3Client s3 = S3Client.builder().region(regionNew).httpClientBuilder(httpClientBuilder).build();

    public PutObjectResponse saveFile(ExpenseFile expenseFile) {
        log.info("Uploading expense file to S3: " + expenseFile.getUuid());
        PutObjectResponse response = s3.putObject(
                PutObjectRequest.builder().bucket(bucketName).key(expenseFile.getUuid()).build(),
                RequestBody.fromString(expenseFile.getExpensefile()));
        log.info("S3 upload response: " + response);
        return response;
    }

    public ExpenseFile getFileById(String uuid) throws S3Exception {
        ExpenseFile file = new ExpenseFile(uuid,"");
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try{
            log.info("Downloading expense file from S3: " + uuid);
            s3.getObject(GetObjectRequest.builder().bucket(bucketName).key(uuid).build(), ResponseTransformer.toOutputStream(baos));

            String str = baos.toString(StandardCharsets.UTF_8);
            file.setExpensefile(str);

            log.info("Loaded expense file from S3: " + uuid);

        }  catch (S3Exception e) {
            log.error("Could not load file from S3", e.fillInStackTrace());
            throw e;
        }
        return file;
    }
}



