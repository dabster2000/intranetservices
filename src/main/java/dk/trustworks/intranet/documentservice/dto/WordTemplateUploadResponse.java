package dk.trustworks.intranet.documentservice.dto;

import java.util.Set;

/**
 * Response DTO for Word template upload.
 *
 * @param fileUuid UUID of the saved file in S3
 * @param filename Original filename
 * @param fileSize Size of the file in bytes
 * @param placeholders Set of placeholder keys found in the document
 */
public record WordTemplateUploadResponse(
    String fileUuid,
    String filename,
    long fileSize,
    Set<String> placeholders
) {}
