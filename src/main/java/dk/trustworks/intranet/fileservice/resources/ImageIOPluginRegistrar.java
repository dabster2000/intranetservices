package dk.trustworks.intranet.fileservice.resources;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import lombok.extern.jbosslog.JBossLog;

import javax.imageio.ImageIO;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Makes the {@code javax.imageio} plugins on the classpath discoverable from Quarkus's runtime
 * classloader, and records at boot which formats this JVM can actually decode.
 * <p>
 * <b>Why this exists.</b> {@link javax.imageio.spi.IIORegistry} discovers
 * {@code META-INF/services/javax.imageio.spi.ImageReaderSpi} entries by reading the <em>context
 * classloader of the thread that triggers the scan</em>, and the initial scan happens once, when
 * {@link ImageIO} is class-initialised. Under a Quarkus fast-jar the application classes live in a
 * {@code RunnerClassLoader} that is not the system classloader, so whether {@code imageio-webp} is
 * found depends on which thread happened to touch ImageIO first — initialise it from a thread
 * whose context classloader cannot see {@code quarkus-app/lib}, and the registry is permanently
 * missing the plugin. Calling {@link ImageIO#scanForPlugins()} from a startup observer removes
 * that ordering dependency, because this runs on a Quarkus main thread whose context classloader
 * is the application's.
 * <p>
 * What makes the repair total rather than best-effort: {@code ImageIO.theRegistry} is a
 * {@code private static final} field, so there is exactly one registry behind every static ImageIO
 * call in the process, and {@code scanForPlugins()} mutates that one. Re-scanning therefore fixes
 * the registry the whole application already shares — including the lookups Thumbnailator makes
 * from request threads — rather than building a second one alongside it.
 * <p>
 * <b>Why it logs.</b> A missing reader is silent by construction — {@code PhotoService.resizeImage}
 * falls back to the original bytes rather than failing — so the previous defect degraded production
 * for months without ever erroring. The boot line is the positive signal that the plugin shipped:
 * if {@code image/webp} is absent from it, thumbnails are being served unresized again and the
 * packaging is the thing to look at, not the photo code.
 *
 * @see PhotoService#resizeImage(byte[], int, int, String)
 */
@JBossLog
@ApplicationScoped
public class ImageIOPluginRegistrar {

    /** The formats this service depends on being decodable. */
    private static final String WEBP_MIME_TYPE = "image/webp";

    void onStart(@Observes StartupEvent event) {
        ImageIO.scanForPlugins();

        String mimeTypes = Arrays.stream(ImageIO.getReaderMIMETypes())
                .sorted()
                .collect(Collectors.joining(", "));

        if (hasReaderFor(WEBP_MIME_TYPE)) {
            log.infof("ImageIO readers registered: %s", mimeTypes);
        } else {
            // Not fatal — every avatar still renders, just unresized — but it is the exact
            // condition that made production serve full-size portraits, so it must be loud.
            log.errorf("No ImageIO reader for %s — webp thumbnails will be served at full size. "
                            + "Check that com.twelvemonkeys.imageio:imageio-webp is on the runtime "
                            + "classpath. Readers registered: %s",
                    WEBP_MIME_TYPE, mimeTypes);
        }
    }

    /** Visible for testing: whether this JVM can decode {@code mimeType}. */
    static boolean hasReaderFor(String mimeType) {
        return ImageIO.getImageReadersByMIMEType(mimeType).hasNext();
    }
}
