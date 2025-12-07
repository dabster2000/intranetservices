package dk.trustworks.intranet.utils.dto.nextsign;

/**
 * Request for obtaining a presigned URL to download a signed document from NextSign.
 * Sent to POST /v3/company/{company}/file/view-presigned-url
 *
 * @param url Document URL from signedDocuments[].document_id field in case status response
 */
public record GetPresignedUrlRequest(
    String url
) {}
