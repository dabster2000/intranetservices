package dk.trustworks.intranet.apis;

import dk.trustworks.intranet.apis.openai.OpenAIService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;
import java.util.UUID;

@ApplicationScoped
@JBossLog
public class ResumeParserService {

    @ConfigProperty(name = "edenai.api.key")
    String edenaiApiKey;

    @Inject
    OpenAIService openAIService;

    private static final String EDEN_AI_RESUME_PARSER_URL = "https://api.edenai.run/v2/ocr/resume_parser";

    private static final String CONVERT_TO_HTML_MODEL = """
            Please create a short text in english language, translating if necessary, from the JSON below, that can be used as a rich mouse over text for use on a web site, summing an employees resume.
            Find a suitable role title for the employee, extracted from the previous assignments and roles in projects.
            You may come up with a better title, than the ones listed here.
            Add 3 bullits extracting the employees main competencies.
            Add 3 bullits extracting the main industries the employee has been working in from the previous assignments, fx. Green Energi, Healthcare, Pharma, Public Sector, Financial Sector, etc.
            The result should be formattet as HTML, do not write anything other than the html content.
            Use this template
            <div><div><strong>[Employee Name]</strong><br /><em>[Role Title]</em><br />[Short Description]</div>
              <p><strong>Main Competencies:</strong></p>
            <ul>
            <li>[One]</li>
            <li>[Two]</li>
            <li>[Three]</li>
            </ul>
            <p><strong>Main Industries</strong></p>
            <ul>
            <li>[One]</li>
            <li>[Two]</li>
            <li>[Three]</li>
            </ul>
            </div>. \n""";

    public String convertResultToHTML(String resume) {
        // The question we want to ask OpenAI
        String question = CONVERT_TO_HTML_MODEL + resume;

        try {
            // Call the OpenAI service and get the response
            String result = openAIService.askQuestion(question);

            log.info(result);

            return result;

        } catch (Exception e) {
            log.log(Logger.Level.ERROR, "Error while generating resume html", e);
        }

        return "";
    }


    public String parseResume(byte[] fileData, String fileName) throws IOException {
        log.info("Parsing resume file: " + fileName + " (" + fileData.length + " bytes)");

        // Generate a unique boundary for the multipart request
        String boundary = "EdenAIBoundary" + UUID.randomUUID().toString().replace("-", "");

        try {
            // Create connection
            URL url = new URI(EDEN_AI_RESUME_PARSER_URL).toURL();
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);

            // Set headers
            connection.setRequestProperty("Authorization", "Bearer " + edenaiApiKey);
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

            // Create multipart request body
            try (OutputStream outputStream = connection.getOutputStream()) {
                // Add providers parameter
                writeFormField(outputStream, boundary, "providers", "openai");

                // Add show_base_64 parameter
                writeFormField(outputStream, boundary, "show_base_64", "true");

                // Add file content
                writeFileField(outputStream, boundary, "file", fileName, fileData);

                // Close multipart body
                byte[] closingBoundary = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
                outputStream.write(closingBoundary);
                outputStream.flush();
            }

            // Get response code
            int responseCode = connection.getResponseCode();
            log.debug("Response code: " + responseCode);

            // Read response
            String response;
            if (responseCode >= 200 && responseCode < 300) {
                // Read successful response
                try (Scanner scanner = new Scanner(connection.getInputStream(), StandardCharsets.UTF_8)) {
                    response = scanner.useDelimiter("\\A").next();
                }
                log.info("Successfully parsed resume with Eden AI");
                log.info("Response: " + response);
            } else {
                // Read error response
                try (Scanner scanner = new Scanner(connection.getErrorStream(), StandardCharsets.UTF_8)) {
                    response = scanner.useDelimiter("\\A").hasNext() ? scanner.next() : "";
                }
                log.error("Failed to parse resume. Status code: " + responseCode);
                log.error("Error response: " + response);
                throw new IOException("Failed to parse resume. Status code: " + responseCode +
                        ", Response: " + response);
            }

            return response;

        } catch (Exception e) {
            log.error("Error while parsing resume with Eden AI", e);
            throw new IOException("Error while parsing resume with Eden AI: " + e.getMessage(), e);
        }
    }

    /**
     * Writes a form field to the multipart request
     */
    private void writeFormField(OutputStream outputStream, String boundary, String name, String value) throws IOException {
        String fieldHeader = "--" + boundary + "\r\n" +
                "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" +
                value;

        outputStream.write(fieldHeader.getBytes(StandardCharsets.UTF_8));
        outputStream.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Writes a file field to the multipart request
     */
    private void writeFileField(OutputStream outputStream, String boundary, String name, String fileName, byte[] fileData) throws IOException {
        String fileHeader = "--" + boundary + "\r\n" +
                "Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" +
                fileName + "\"\r\n" +
                "Content-Type: application/pdf\r\n\r\n";

        outputStream.write(fileHeader.getBytes(StandardCharsets.UTF_8));
        outputStream.write(fileData);
    }
}
