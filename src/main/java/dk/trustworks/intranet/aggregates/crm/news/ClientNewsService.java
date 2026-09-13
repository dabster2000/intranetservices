package dk.trustworks.intranet.aggregates.crm.news;

import dk.trustworks.intranet.scheduling.SchedulerShutdownGuard;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import lombok.extern.jbosslog.JBossLog;
import java.time.*;
import java.util.*;
import static dk.trustworks.intranet.aggregates.crm.news.ClientNewsDTO.*;

@ApplicationScoped
@JBossLog
public class ClientNewsService {
    static final ZoneId ZONE = ZoneId.of("Europe/Copenhagen");
    static final int MAX_PAGES = 5;
    @Inject ClientNewsRepository repository;
    @Inject ClientNewsConfig config;
    @Inject NewsApiClient provider;
    @Inject ClientNewsMatcher matcher;
    @Inject SchedulerShutdownGuard shutdown;
    Clock clock = Clock.systemUTC();

    public ClientNewsDTO get(String uuid) {
        if (uuid == null || !uuid.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}"))
            throw new BadRequestException("Invalid client UUID");
        var state = repository.read(uuid);
        Status status = !state.eligible() || !config.enabled() ? Status.DISABLED
                : !config.configured() ? Status.FAILED : state.status();
        return new ClientNewsDTO(state.eligible(), status, state.coverageLimited(), state.attempted(), state.succeeded(),
                state.eligible() ? recent(state.items(), clock.instant()).stream().map(StoredItem::item).toList() : List.of());
    }

    /** May run on multiple replicas: claims and budget are coordinated in MariaDB, not process memory. */
    public void synchronize() {
        if (!config.enabled() || shutdown.isShuttingDown()) return;
        Instant now = clock.instant();
        ZonedDateTime local = now.atZone(ZONE);
        if (local.getHour() < 6) return;
        Instant due = local.toLocalDate().atTime(6, 0).atZone(ZONE).toInstant();
        int completed = 0, failed = 0;
        for (var client : repository.eligibleClients()) {
            if (shutdown.isShuttingDown()) break;
            Optional<ClientNewsRepository.Lease> claimed = repository.claim(client, due, local.toLocalDate());
            if (claimed.isEmpty()) continue;
            var lease = claimed.get();
            List<StoredItem> selected = new ArrayList<>();
            try {
                List<String> aliases = matcher.aliases(client);
                if (aliases.isEmpty()) throw new NewsApiClient.NewsFailure(NewsApiClient.Failure.CONFIGURATION);
                // Failed attempts never move this boundary; a new client starts with a full thirty-day lookback.
                LocalDate from = windowStart(lease.checkedThrough(), now);
                LocalDate through = now.atZone(ZoneOffset.UTC).toLocalDate();
                boolean exhausted = false;
                for (int page = 1; page <= MAX_PAGES; page++) {
                    if (shutdown.isShuttingDown()) throw new NewsApiClient.NewsFailure(NewsApiClient.Failure.INTERRUPTED);
                    NewsApiClient.Page result = provider.fetch(aliases, from, through, page,
                            () -> repository.reserveRequest(lease, clock.instant().atZone(ZONE).toLocalDate(), config.budget()));
                    for (var article : result.articles()) matcher.select(client, article, now).ifPresent(selected::add);
                    if (page >= result.pages()) { exhausted = true; break; }
                }
                // This is a bounded latest-news feed, not an exhaustive archive. Expose the coverage
                // limit explicitly; a valid bounded search is a successful check even for a busy company.
                List<StoredItem> latest = matcher.latest(recent(lease.items(), now), recent(selected, now));
                if (repository.complete(lease, now, latest, !exhausted)) {
                    completed++;
                    log.infof("Client news stored: client=%s items=%d coverageLimited=%s", client.uuid(), latest.size(), !exhausted);
                }
            } catch (NewsApiClient.NewsFailure e) {
                repository.fail(lease, e.code.name(), selected.isEmpty() ? List.of() : matcher.latest(lease.items(), selected));
                failed++;
                log.warnf("Client news refresh failed: client=%s code=%s", client.uuid(), e.code);
                if (Set.of(NewsApiClient.Failure.AUTHENTICATION, NewsApiClient.Failure.CONFIGURATION,
                        NewsApiClient.Failure.RATE_LIMIT, NewsApiClient.Failure.BUDGET_OR_LEASE,
                        NewsApiClient.Failure.INTERRUPTED).contains(e.code)) break;
            } catch (RuntimeException e) {
                repository.fail(lease, "INTERNAL_ERROR", List.of());
                failed++;
                log.warnf("Client news refresh failed: client=%s code=INTERNAL_ERROR", client.uuid());
            }
        }
        log.infof("Client news refresh finished: completed=%d failed=%d", completed, failed);
    }

    static LocalDate windowStart(Instant watermark, Instant now) {
        LocalDate today = now.atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate oldest = today.minusDays(29); // thirty calendar dates, inclusive; no paid archive requests
        LocalDate proposed = watermark == null ? oldest : watermark.atZone(ZoneOffset.UTC).toLocalDate().minusDays(2);
        return proposed.isBefore(oldest) ? oldest : proposed.isAfter(today) ? today : proposed;
    }

    static List<StoredItem> recent(List<StoredItem> items, Instant now) {
        Instant cutoff = windowStart(null, now).atStartOfDay(ZoneOffset.UTC).toInstant();
        return items.stream().filter(item -> !item.item().publishedAt().isBefore(cutoff)).toList();
    }
}
