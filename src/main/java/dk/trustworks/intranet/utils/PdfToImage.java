package dk.trustworks.intranet.utils;

public final class PdfToImage {
    private PdfToImage() {}

    public static byte[] firstPageAsPng(byte[] pdfBytes) {
        // PDF-to-image conversion intentionally disabled to avoid extra deps.
        // Returning null will cause the AI call to use PDF base64 directly (handled by caller).
        return null;
    }
}
