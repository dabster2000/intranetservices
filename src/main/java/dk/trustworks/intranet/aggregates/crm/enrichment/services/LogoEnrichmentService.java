package dk.trustworks.intranet.aggregates.crm.enrichment.services;

import dk.trustworks.intranet.aggregates.crm.enrichment.ClientEnrichmentConfig;
import dk.trustworks.intranet.aggregates.crm.enrichment.ai.CvrCandidateFinder;
import dk.trustworks.intranet.aggregates.crm.enrichment.ai.LogoFinder;
import dk.trustworks.intranet.aggregates.crm.enrichment.ai.LogoGenerator;
import dk.trustworks.intranet.aggregates.crm.enrichment.model.ClientEnrichment;
import dk.trustworks.intranet.aggregates.crm.enrichment.model.enums.LogoEnrichmentStatus;
import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.dao.crm.model.ClientActivityLog;
import dk.trustworks.intranet.dao.crm.model.enums.ClientSegment;
import dk.trustworks.intranet.dao.crm.model.enums.ClientType;
import dk.trustworks.intranet.dao.crm.services.ClientActivityLogService;
import dk.trustworks.intranet.fileservice.model.File;
import dk.trustworks.intranet.fileservice.resources.PhotoService;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.jbosslog.JBossLog;

import java.net.URI;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * The nightly logo hunt for one client (clients only, by decision; applied at once, by
 * decision).
 *
 * <p>Find first, draw second: the model with web search names a file, {@link
 * SafeImageDownloader} fetches it under the private-address, size and image-type rules,
 * and only when that yields nothing storable does {@link LogoGenerator} draw one. Either
 * way the result goes through {@link PhotoService#updateLogo} — the same chokepoint the
 * upload dialog uses, so the allowlist, the resize and the cache invalidation are the
 * same — and the activity log records where it came from. The account page shows that
 * provenance beside the logo, so a person who knows the real mark can replace it.
 *
 * <p>Transparency is flattened onto white before storage because {@code PhotoService}
 * re-encodes as JPEG, which would otherwise turn a transparent PNG black.
 */
@JBossLog
@ApplicationScoped
public class LogoEnrichmentService {

    static final String LOGO_FIELD = "logo";
    static final String FILE_TYPE_PHOTO = "PHOTO";

    record Snapshot(String uuid, String name, ClientType type, String cvr, String city, String industryDesc, ClientSegment segment) {}

    @Inject
    ClientEnrichmentConfig config;

    @Inject
    LogoFinder finder;

    @Inject
    LogoGenerator generator;

    @Inject
    SafeImageDownloader downloader;

    @Inject
    PhotoService photoService;

    @Inject
    ClientEnrichmentRepository repository;

    @Inject
    ClientActivityLogService activityLog;

    /** Runs outside any transaction; returns the status filed, or empty when the client is gone. */
    public Optional<LogoEnrichmentStatus> enrich(String clientUuid) {
        Snapshot s = QuarkusTransaction.requiringNew().call(() -> snapshot(clientUuid));
        if (s == null) return Optional.empty();
        if (s.type() != ClientType.CLIENT) {
            return Optional.of(record(s.uuid(), LogoEnrichmentStatus.SKIPPED, null, null, false));
        }
        byte[] existing = photoService.findPhotoByRelatedUUID(s.uuid()).getFile();
        if (existing != null && existing.length > 0) {
            return Optional.of(record(s.uuid(), LogoEnrichmentStatus.PRESENT, null, null, false));
        }

        String lastProblem = "no logo found online";
        Optional<LogoFinder.LogoLocation> location = finder.find(s.name(), s.cvr(), s.city());
        if (location.isPresent()) {
            LogoFinder.LogoLocation found = location.get();
            try {
                SafeImageDownloader.Downloaded image = downloader.download(found.imageUrl(), config.logoMaxDownloadBytes());
                byte[] flat = SafeImageDownloader.flattenToWhite(image.bytes());
                String source = found.pageUrl() != null ? found.pageUrl() : found.imageUrl();
                store(s, flat, "AI found: " + hostOf(source));
                return Optional.of(record(s.uuid(), LogoEnrichmentStatus.FOUND, null, source, true));
            } catch (SafeImageDownloader.DownloadRejected rejected) {
                lastProblem = "found a logo online but could not use it (" + rejected.getMessage() + ")";
                log.infof("Logo download rejected for client=%s: %s", s.uuid(), rejected.getMessage());
            } catch (RuntimeException e) {
                lastProblem = "found a logo online but storing it failed (" + e.getClass().getSimpleName() + ")";
                log.warnf(e, "Storing a found logo failed for client=%s", s.uuid());
            }
        }

        byte[] generated = generator.generate(s.name(), s.industryDesc(), s.segment());
        if (generated != null && generated.length > 0) {
            try {
                byte[] flat = SafeImageDownloader.flattenToWhite(generated);
                store(s, flat, "AI generated");
                return Optional.of(record(s.uuid(), LogoEnrichmentStatus.GENERATED, null, null, true));
            } catch (SafeImageDownloader.DownloadRejected rejected) {
                lastProblem += "; the generated image did not decode";
            } catch (RuntimeException e) {
                lastProblem += "; storing the generated image failed (" + e.getClass().getSimpleName() + ")";
                log.warnf(e, "Storing a generated logo failed for client=%s", s.uuid());
            }
        } else {
            lastProblem += "; image generation produced nothing";
        }
        return Optional.of(record(s.uuid(), LogoEnrichmentStatus.FAILED, lastProblem, null, true));
    }

    /** Through {@code PhotoService}, exactly as the upload dialog's bytes go — then the activity log. */
    private void store(Snapshot s, byte[] png, String label) {
        File file = new File(UUID.randomUUID().toString(), s.uuid(), FILE_TYPE_PHOTO);
        file.setName(label);
        file.setFilename("logo.png");
        file.setUploaddate(LocalDate.now());
        file.setFile(png);
        photoService.updateLogo(file);
        QuarkusTransaction.requiringNew().run(() ->
                activityLog.logChange(s.uuid(), ClientActivityLog.TYPE_CLIENT, s.uuid(), s.name(),
                        ClientActivityLog.ACTION_MODIFIED, LOGO_FIELD, null, label));
        log.infof("Logo stored: client=%s %s", s.uuid(), label);
    }

    static String hostOf(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null ? url : host.replaceFirst("^www\\.", "");
        } catch (RuntimeException e) {
            return url;
        }
    }

    LogoEnrichmentStatus record(String clientUuid, LogoEnrichmentStatus status, String error, String sourceUrl, boolean countAttempt) {
        QuarkusTransaction.requiringNew().run(() -> {
            ClientEnrichment row = repository.ensure(clientUuid);
            if (row == null) return;
            row.setLogo(status);
            row.setLogoCheckedAt(LocalDateTime.now());
            row.setLogoError(error == null ? null : CvrCandidateFinder.truncate(error, 255));
            row.setLogoSourceUrl(sourceUrl == null ? null : CvrCandidateFinder.truncate(sourceUrl, 1000));
            if (countAttempt) row.setLogoAttempts(row.getLogoAttempts() + 1);
        });
        if (status != LogoEnrichmentStatus.SKIPPED && status != LogoEnrichmentStatus.PRESENT) {
            log.infof("Logo check filed: client=%s status=%s%s", clientUuid, status, error == null ? "" : " (" + error + ")");
        }
        return status;
    }

    static Snapshot snapshot(String clientUuid) {
        Client c = Client.findById(clientUuid);
        if (c == null) return null;
        return new Snapshot(c.getUuid(), c.getName(), c.getType(), c.getCvr(), c.getBillingCity(), c.getIndustryDesc(), c.getSegment());
    }
}
