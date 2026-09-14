package dk.trustworks.intranet.aggregates.crm.enrichment.services;

import dk.trustworks.intranet.fileservice.resources.PhotoService;
import jakarta.enterprise.context.ApplicationScoped;
import lombok.extern.jbosslog.JBossLog;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

/**
 * Fetches an image from a URL a language model proposed — which is to say, from a URL
 * nobody trustworthy chose.
 *
 * <p>The model's answer is treated as attacker-controlled input. Before a byte is
 * requested the URL has to be https, name a public host, and resolve only to global
 * unicast addresses: no loopback, no RFC 1918, no link-local (the cloud metadata service
 * lives at 169.254.169.254), no carrier-grade NAT, no IPv6 ULA, no IPv4-mapped private
 * addresses. Redirects are never followed automatically; each hop is re-validated by the
 * same rule, at most three of them. The body is capped before it is buffered, the bytes
 * must pass {@link PhotoService#isStorableImageType} on their own content (never on the
 * {@code Content-Type} header) and must decode as a raster image of a plausible size.
 *
 * <p>What is NOT defended against, and why it is accepted: the host is resolved once for
 * the check and again by the HTTP client for the connection, so a DNS answer that changes
 * between the two (rebinding) could reach a private address. Closing that gap means
 * connecting to the validated IP with a hand-set {@code Host} header, which the JDK client
 * only allows with a restricted-headers system property. This job runs at night against
 * company websites the model found, fetches at most ten images, and stores nothing but
 * the decoded image — a rebinding attack here reads at most one response body that is
 * then thrown away by the image decoder.
 */
@JBossLog
@ApplicationScoped
public class SafeImageDownloader {

    /** What a rejected URL or response looks like to the caller. The reason is loggable, never the body. */
    public static class DownloadRejected extends Exception {
        public DownloadRejected(String reason) {
            super(reason);
        }
    }

    /** The image, already known to be a storable raster of a plausible size. */
    public record Downloaded(byte[] bytes, String mimeType, int width, int height) {}

    static final int MAX_REDIRECTS = 3;
    static final int MIN_WIDTH = 64;
    static final int MIN_HEIGHT = 32;
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
    static final Duration WHOLE_BODY_DEADLINE = Duration.ofSeconds(40);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public Downloaded download(String url, long maxBytes) throws DownloadRejected {
        String current = url;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            URI uri = validateUrl(current);
            HttpResponse<byte[]> response;
            try {
                HttpRequest request = HttpRequest.newBuilder(uri)
                        .timeout(REQUEST_TIMEOUT)
                        .header("User-Agent", "Trustworks-Intra/1.0 (+https://intra.trustworks.dk; client logo fetch)")
                        .header("Accept", "image/png,image/jpeg,image/webp,image/*;q=0.8")
                        .GET()
                        .build();
                CompletableFuture<HttpResponse<byte[]>> pending = http.sendAsync(request,
                        info -> new LimitedBodySubscriber(maxBytes));
                response = pending.get(WHOLE_BODY_DEADLINE.toMillis(), TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                if (cause instanceof DownloadRejected rejected) throw rejected;
                throw new DownloadRejected("fetch failed: " + cause.getClass().getSimpleName());
            }
            int status = response.statusCode();
            if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                String location = response.headers().firstValue("Location").orElse(null);
                if (location == null || location.isBlank()) throw new DownloadRejected("redirect without Location");
                current = uri.resolve(location.trim()).toString();
                continue;
            }
            if (status != 200) throw new DownloadRejected("HTTP " + status);
            return validateImage(response.body());
        }
        throw new DownloadRejected("too many redirects");
    }

    /**
     * The URL rule, on its own so the fast tier can exercise it: https, a host, no
     * credentials in the authority, no private or special-purpose address behind the name.
     */
    public static URI validateUrl(String url) throws DownloadRejected {
        if (url == null || url.isBlank()) throw new DownloadRejected("empty URL");
        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (Exception e) {
            throw new DownloadRejected("malformed URL");
        }
        if (uri.getScheme() == null || !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new DownloadRejected("not https");
        }
        if (uri.getUserInfo() != null) throw new DownloadRejected("credentials in URL");
        String host = uri.getHost();
        if (host == null || host.isBlank()) throw new DownloadRejected("no host");
        String lower = host.toLowerCase(Locale.ROOT);
        if ("localhost".equals(lower) || lower.endsWith(".localhost") || lower.endsWith(".local")
                || lower.endsWith(".internal") || lower.endsWith(".home.arpa")) {
            throw new DownloadRejected("local hostname");
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new DownloadRejected("host does not resolve");
        }
        if (addresses.length == 0) throw new DownloadRejected("host does not resolve");
        for (InetAddress address : addresses) {
            if (!isPublicUnicast(address)) throw new DownloadRejected("host resolves to a private address");
        }
        return uri;
    }

    /** Global unicast only. Package-visible for the fast tier. */
    static boolean isPublicUnicast(InetAddress address) {
        if (address == null) return false;
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        byte[] raw = address.getAddress();
        if (address instanceof Inet4Address) {
            int first = raw[0] & 0xff, second = raw[1] & 0xff;
            if (first == 0) return false;                                   // 0.0.0.0/8
            if (first == 100 && second >= 64 && second <= 127) return false; // 100.64.0.0/10 CGNAT
            if (first == 192 && second == 0 && (raw[2] & 0xff) == 0) return false; // 192.0.0.0/24
            if (first == 198 && (second == 18 || second == 19)) return false;  // 198.18.0.0/15 benchmarking
            if (first >= 240) return false;                                 // 240.0.0.0/4 reserved + broadcast
            return true;
        }
        if (address instanceof Inet6Address) {
            int first = raw[0] & 0xff;
            if ((first & 0xfe) == 0xfc) return false;                        // fc00::/7 ULA
            // IPv4-mapped (::ffff:a.b.c.d): judge the embedded IPv4 by the same rule.
            boolean mapped = true;
            for (int i = 0; i < 10; i++) if (raw[i] != 0) { mapped = false; break; }
            if (mapped && (raw[10] & 0xff) == 0xff && (raw[11] & 0xff) == 0xff) {
                try {
                    return isPublicUnicast(InetAddress.getByAddress(new byte[]{raw[12], raw[13], raw[14], raw[15]}));
                } catch (UnknownHostException e) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    /** The bytes must be a storable raster and decode to something logo-sized. */
    static Downloaded validateImage(byte[] bytes) throws DownloadRejected {
        if (bytes == null || bytes.length == 0) throw new DownloadRejected("empty body");
        String mime = new org.apache.tika.Tika().detect(bytes);
        if (!PhotoService.isStorableImageType(mime)) throw new DownloadRejected("not a storable image: " + mime);
        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (Exception e) {
            throw new DownloadRejected("image does not decode");
        }
        if (image == null) throw new DownloadRejected("image does not decode");
        if (image.getWidth() < MIN_WIDTH || image.getHeight() < MIN_HEIGHT) {
            throw new DownloadRejected("image too small: " + image.getWidth() + "x" + image.getHeight());
        }
        return new Downloaded(bytes, mime, image.getWidth(), image.getHeight());
    }

    /**
     * Flattens any transparency onto white and re-encodes as PNG. {@code PhotoService}
     * re-encodes every upload as JPEG, which has no alpha channel — a transparent logo
     * handed over as-is comes back on a black background.
     */
    public static byte[] flattenToWhite(byte[] bytes) throws DownloadRejected {
        BufferedImage source;
        try {
            source = ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (Exception e) {
            throw new DownloadRejected("image does not decode");
        }
        if (source == null) throw new DownloadRejected("image does not decode");
        BufferedImage flat = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = flat.createGraphics();
        try {
            g.setColor(java.awt.Color.WHITE);
            g.fillRect(0, 0, flat.getWidth(), flat.getHeight());
            g.drawImage(source, 0, 0, null);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            if (!ImageIO.write(flat, "png", out)) throw new DownloadRejected("PNG encoder unavailable");
        } catch (java.io.IOException e) {
            throw new DownloadRejected("PNG encoding failed");
        }
        return out.toByteArray();
    }

    /** Bound memory before allocating a whole response — the {@code NewsApiClient} shape. */
    static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final long maxBytes;
        private Flow.Subscription subscription;

        LimitedBodySubscriber(long maxBytes) {
            this.maxBytes = maxBytes;
        }

        public CompletionStage<byte[]> getBody() { return result; }

        public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }

        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if ((long) bytes.size() + buffer.remaining() > maxBytes) {
                    subscription.cancel();
                    result.completeExceptionally(new DownloadRejected("body larger than " + maxBytes + " bytes"));
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }

        public void onError(Throwable error) { result.completeExceptionally(new DownloadRejected("read failed")); }

        public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
