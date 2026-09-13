package dk.trustworks.intranet.aggregates.crm.trustlink.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.trustworks.intranet.aggregates.crm.trustlink.dto.TrustLinkConnectionDTO;
import dk.trustworks.intranet.aggregates.crm.trustlink.dto.TrustLinkSearchResponse;
import dk.trustworks.intranet.aggregates.crm.trustlink.dto.TrustLinkTrustworkerDTO;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * The only place Intra talks to TrustLink.
 *
 * <h2>Why this class distrusts its own upstream</h2>
 * TrustLink ignores request fields it does not recognise. It does not reject them, it does
 * not warn, it answers. So a single misspelt field name — {@code companynames} instead of
 * {@code companyNames}, {@code q} instead of {@code term} — does not look like a bug. It
 * looks like a successful HTTP 200 carrying a well-formed page of people, and "no filter
 * applied" is indistinguishable from "filter applied, this is the answer". The filtered
 * query returns about 1,600 people across the whole client base; the unfiltered one returns
 * 47,436. Ingesting the unfiltered answer would not throw: it would quietly attach tens of
 * thousands of strangers to arbitrary accounts and tell 246 colleagues they know them.
 *
 * <p>That is why every response here is checked against the request that produced it before
 * it is allowed to become data:
 * <ul>
 *   <li>{@code page} must equal the page asked for, and {@code pageSize} must equal the page
 *       size asked for — the server echoing our own parameters back is the cheapest proof
 *       available that it read them;</li>
 *   <li>{@code items.size()} must not exceed {@code pageSize};</li>
 *   <li>every returned {@code companyName} must be one of the names asked for, compared
 *       exactly (the API is case-sensitive and does no prefix matching, so exact is the
 *       only honest comparison);</li>
 *   <li>every returned {@code tier} must be {@value #TIER_5}, the tier this integration
 *       asks for and the only tier it is willing to store.</li>
 * </ul>
 * A response failing any of these is {@link Failure#INVALID_RESPONSE} — not data. It is
 * better for a night's run to fail loudly and change nothing than for it to succeed at
 * writing the wrong thing, because this feature never deletes: a bad ingest is permanent.
 *
 * <h2>Failure posture</h2>
 * Copied deliberately from {@code NewsApiClient}. Redirects are never followed, so a
 * hijacked or reconfigured host cannot bounce a request carrying the API key somewhere
 * else. There is a connect timeout AND a whole-response deadline, because Java's per-request
 * timeout can be satisfied the moment headers arrive and leave a worker parked on a body
 * that never ends. The body subscriber refuses to buffer more than {@value #MAX_RESPONSE_BYTES}
 * bytes, so an upstream that starts streaming cannot take the JVM's heap with it. There is
 * exactly one retry, on 429 and 5xx, and only when {@code Retry-After} asks for a couple of
 * seconds; a longer backoff is the next nightly run's problem, not this thread's.
 *
 * <p>{@link TrustLinkFailure} carries a {@link Failure} code and nothing else — no upstream
 * message, no response body, no cause. An error body from an API that echoes request
 * parameters is a request body in disguise, and this request body names our clients.
 *
 * <h2>What this class does not decide</h2>
 * It does not read the feature flag. Whether a disabled integration means "skip the run"
 * (the nightly job) or "the editor typeahead is unavailable" (the resource) is the caller's
 * judgement, and a client that silently returns nothing when switched off is a client that
 * hides a misconfiguration.
 */
@ApplicationScoped
public class TrustLinkClient {

    static final String SEARCH_PATH = "/api/connections/search";
    static final String COMPANY_SEARCH_PATH = "/api/connections/companies/search";
    static final String TRUSTWORKERS_PATH = "/api/trustworkers";

    /** The only tier worth mirroring: TrustLink's grade for a real, personal connection. */
    public static final int TIER_5 = 5;

    static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;

    /** The largest page size the API has been seen to honour, and it must honour it exactly. */
    static final int MAX_PAGE_SIZE = 1000;

    /** A typeahead term longer than this is not a company name, it is a payload. */
    static final int MAX_TERM_LENGTH = 100;

    static final int MAX_TYPEAHEAD_LIMIT = 50;

    /** 61 trustworkers today; anything near this is a different API answering. */
    static final int MAX_TRUSTWORKERS = 5_000;

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration RESPONSE_DEADLINE = Duration.ofSeconds(30);

    @Inject
    TrustLinkConfig config;

    @Inject
    ObjectMapper mapper;

    HttpClient http = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    public enum Failure {
        /** No usable base URL configured. */
        CONFIGURATION,
        /** The caller asked for something this client refuses to ask upstream. */
        INVALID_REQUEST,
        RATE_LIMIT,
        HTTP_ERROR,
        /** The answer did not match the question. See the class Javadoc — this is the important one. */
        INVALID_RESPONSE,
        RESPONSE_TOO_LARGE,
        TIMEOUT_OR_IO,
        INTERRUPTED
    }

    /** Carries a code and nothing else: an upstream error body can echo back our request. */
    public static class TrustLinkFailure extends RuntimeException {
        private final Failure code;

        public TrustLinkFailure(Failure code) {
            super(code.name());
            this.code = code;
        }

        public Failure code() {
            return code;
        }
    }

    /**
     * One page of tier-5 connections for the given TrustLink company names.
     *
     * <p>The company names are sent exactly as given. TrustLink matches them
     * case-sensitively with no prefix and no contains, so "Novo" and "novo nordisk" both
     * return nothing while "Novo Nordisk" returns 203 people — names belong in the alias
     * table, chosen from the typeahead, not typed by hand here.
     *
     * <p>An empty name list is rejected rather than sent. Upstream would read it as "no
     * company filter" and answer with the entire database, which is the exact accident this
     * class exists to prevent.
     *
     * @param page 1-based
     * @throws TrustLinkFailure with {@link Failure#INVALID_RESPONSE} if the answer does not
     *                          demonstrably belong to this request
     */
    /**
     * One page at the configured page size — what the nightly sync uses.
     *
     * <p>The page size is configuration rather than a caller's choice on purpose: it is the
     * value the server is required to echo back, so the run that asks and the check that
     * verifies must read it from the same place or the check tests nothing.
     *
     * @param page 1-based
     */
    public TrustLinkSearchResponse search(List<String> companyNames, int page) {
        return search(companyNames, page, config.pageSize());
    }

    public TrustLinkSearchResponse search(List<String> companyNames, int page, int pageSize) {
        Set<String> requested = distinctNames(companyNames);
        if (requested.isEmpty() || page < 1 || pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new TrustLinkFailure(Failure.INVALID_REQUEST);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("companyNames", List.copyOf(requested));
        payload.put("tiers", List.of(TIER_5));
        payload.put("sortBy", "fullName");
        payload.put("sortDescending", false);
        payload.put("page", page);
        payload.put("pageSize", pageSize);

        byte[] body = exchange(post(SEARCH_PATH, payload));
        TrustLinkSearchResponse response = read(body, TrustLinkSearchResponse.class);
        assertAnswersRequest(response, page, pageSize, requested);
        return response;
    }

    /**
     * Company-name typeahead, used to seed AUTO aliases and to back the alias editor.
     *
     * <p>The query parameter is {@code term}. {@code q}, {@code query}, {@code search} and
     * {@code name} are all accepted, ignored, and answered with arbitrary companies — which
     * is the same silent failure as above, wearing a different hat. A blank or oversized
     * term is refused here rather than forwarded, because "no term" upstream means "some
     * companies", not "no companies".
     *
     * @return the matching company names, TrustLink's spelling, never null
     */
    public List<String> searchCompanies(String term, int limit) {
        String trimmed = term == null ? "" : term.strip();
        if (trimmed.isEmpty() || trimmed.length() > MAX_TERM_LENGTH
                || limit < 1 || limit > MAX_TYPEAHEAD_LIMIT) {
            throw new TrustLinkFailure(Failure.INVALID_REQUEST);
        }
        String query = COMPANY_SEARCH_PATH
                + "?limit=" + limit
                + "&term=" + URLEncoder.encode(trimmed, StandardCharsets.UTF_8);

        byte[] body = exchange(get(query));
        List<String> names = read(body, new TypeReference<List<String>>() { });
        if (names == null || names.size() > limit) throw new TrustLinkFailure(Failure.INVALID_RESPONSE);
        List<String> usable = new ArrayList<>(names.size());
        for (String name : names) {
            if (name != null && !name.isBlank()) usable.add(name);
        }
        return List.copyOf(usable);
    }

    /**
     * Every trustworker TrustLink knows about — 61 rows today, and the input to the
     * name-to-{@code User} ladder.
     *
     * <p>The size ceiling is a plausibility check of the same family as the others: this
     * endpoint returns a bare array with no envelope to verify against, so its only
     * available sanity signal is that the firm does not have thousands of employees.
     */
    public List<TrustLinkTrustworkerDTO> trustworkers() {
        byte[] body = exchange(get(TRUSTWORKERS_PATH));
        List<TrustLinkTrustworkerDTO> trustworkers =
                read(body, new TypeReference<List<TrustLinkTrustworkerDTO>>() { });
        if (trustworkers == null || trustworkers.size() > MAX_TRUSTWORKERS) {
            throw new TrustLinkFailure(Failure.INVALID_RESPONSE);
        }
        List<TrustLinkTrustworkerDTO> named = new ArrayList<>(trustworkers.size());
        for (TrustLinkTrustworkerDTO trustworker : trustworkers) {
            if (trustworker != null && trustworker.name() != null && !trustworker.name().isBlank()) {
                named.add(trustworker);
            }
        }
        return List.copyOf(named);
    }

    /**
     * The check the whole feature rests on: does this response actually answer the question
     * that was asked?
     *
     * <p>Package-visible and static so it can be exercised against a captured payload
     * without a socket. Read the class Javadoc for why each clause is here.
     */
    static void assertAnswersRequest(TrustLinkSearchResponse response, int expectedPage,
                                     int expectedPageSize, Set<String> requestedCompanies) {
        if (response == null) throw new TrustLinkFailure(Failure.INVALID_RESPONSE);
        if (response.page() != expectedPage || response.pageSize() != expectedPageSize) {
            throw new TrustLinkFailure(Failure.INVALID_RESPONSE);
        }
        List<TrustLinkConnectionDTO> items = response.items();
        // NOT checked: totalCount >= items.size(). It looks like an obvious invariant and it
        // is not one upstream holds. TrustLink counts DISTINCT people but returns one row per
        // matching company, and the same person genuinely sits under several — "Netcompany"
        // and "Netcompany A/S" are two company rows for one organisation, which is the exact
        // fragmentation the alias table exists to paper over. A batch spanning both came back
        // with 470 items against totalCount 457, and asserting the invariant threw away all
        // 100 companies in that batch. It guarded nothing either: an ignored filter returns a
        // LARGER totalCount (47,436), never a smaller one. The filter guards are the tier and
        // company-membership checks below, and they are untouched.
        if (items.size() > expectedPageSize
                || response.totalPages() < 0 || (!items.isEmpty() && response.totalPages() < expectedPage)) {
            throw new TrustLinkFailure(Failure.INVALID_RESPONSE);
        }
        for (TrustLinkConnectionDTO item : items) {
            if (item == null || item.personId() == null || item.personId().isBlank()
                    || item.fullName() == null || item.fullName().isBlank()) {
                throw new TrustLinkFailure(Failure.INVALID_RESPONSE);
            }
            if (item.tier() != TIER_5 || !requestedCompanies.contains(item.companyName())) {
                throw new TrustLinkFailure(Failure.INVALID_RESPONSE);
            }
        }
    }

    /** Order-preserving, blank-free, duplicate-free. Case is preserved: the API is case-sensitive. */
    static Set<String> distinctNames(Collection<String> companyNames) {
        if (companyNames == null) return Set.of();
        Set<String> distinct = new LinkedHashSet<>();
        for (String name : companyNames) {
            if (name != null && !name.isBlank()) distinct.add(name.strip());
        }
        return distinct;
    }

    HttpRequest post(String path, Map<String, Object> payload) {
        try {
            return headers(HttpRequest.newBuilder(uri(path)))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(payload)))
                    .header("Content-Type", "application/json")
                    .build();
        } catch (TrustLinkFailure e) {
            throw e;
        } catch (Exception e) {
            throw new TrustLinkFailure(Failure.INVALID_REQUEST);
        }
    }

    HttpRequest get(String pathAndQuery) {
        return headers(HttpRequest.newBuilder(uri(pathAndQuery))).GET().build();
    }

    private HttpRequest.Builder headers(HttpRequest.Builder builder) {
        builder.timeout(RESPONSE_DEADLINE).header("Accept", "application/json");
        // Wired but dormant: TrustLink requires no authentication today. When a key appears
        // it is an environment variable, not a release. Never logged, never persisted.
        config.apiKey().ifPresent(key -> builder.header("X-Api-Key", key));
        return builder;
    }

    private URI uri(String pathAndQuery) {
        String base = config.baseUrl();
        if (base.isBlank()) throw new TrustLinkFailure(Failure.CONFIGURATION);
        try {
            URI uri = URI.create(base + pathAndQuery);
            String scheme = uri.getScheme();
            if (uri.getHost() == null || scheme == null
                    || !(scheme.equals("https") || scheme.equals("http"))) {
                throw new TrustLinkFailure(Failure.CONFIGURATION);
            }
            return uri;
        } catch (TrustLinkFailure e) {
            throw e;
        } catch (IllegalArgumentException e) {
            throw new TrustLinkFailure(Failure.CONFIGURATION);
        }
    }

    /** One attempt, then one retry on 429/5xx when Retry-After asks for a short wait. */
    byte[] exchange(HttpRequest request) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                HttpResponse<byte[]> response = send(request);
                int status = response.statusCode();
                if (status == 429 || status >= 500) {
                    if (attempt == 0) {
                        int wait = retryAfter(response.headers().firstValue("Retry-After").orElse("1"));
                        if (wait <= 2) {
                            Thread.sleep(wait * 1000L);
                            continue;
                        }
                    }
                    throw new TrustLinkFailure(status == 429 ? Failure.RATE_LIMIT : Failure.HTTP_ERROR);
                }
                if (status != 200) throw new TrustLinkFailure(Failure.HTTP_ERROR);
                return response.body();
            } catch (TrustLinkFailure e) {
                throw e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new TrustLinkFailure(Failure.INTERRUPTED);
            } catch (Exception e) {
                if (attempt == 1) throw new TrustLinkFailure(Failure.TIMEOUT_OR_IO);
                // No message and no cause propagated: HTTP diagnostics can contain the request body,
                // and this request body names the firm's clients.
            }
        }
        throw new TrustLinkFailure(Failure.TIMEOUT_OR_IO);
    }

    HttpResponse<byte[]> send(HttpRequest request) throws Exception {
        // Java's request timeout can be satisfied when headers arrive. This deadline covers the
        // whole body, and cancelling aborts a stalled publisher instead of stranding a worker.
        CompletableFuture<HttpResponse<byte[]>> pending =
                http.sendAsync(request, ignored -> new LimitedBodySubscriber());
        try {
            return pending.get(RESPONSE_DEADLINE.toSeconds(), TimeUnit.SECONDS);
        } catch (TimeoutException | InterruptedException e) {
            pending.cancel(true);
            throw e;
        } catch (ExecutionException e) {
            if (e.getCause() instanceof TrustLinkFailure failure) throw failure;
            throw e;
        }
    }

    private <T> T read(byte[] body, Class<T> type) {
        try {
            return mapper.readValue(body, type);
        } catch (Exception e) {
            throw new TrustLinkFailure(Failure.INVALID_RESPONSE);
        }
    }

    private <T> T read(byte[] body, TypeReference<T> type) {
        try {
            return mapper.readValue(body, type);
        } catch (Exception e) {
            throw new TrustLinkFailure(Failure.INVALID_RESPONSE);
        }
    }

    static int retryAfter(String text) {
        try {
            return Math.max(1, Integer.parseInt(text.strip()));
        } catch (RuntimeException e) {
            return 60;
        }
    }

    /** Bound memory before allocating a whole response. send() supplies the whole-body deadline. */
    static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        public CompletionStage<byte[]> getBody() {
            return result;
        }

        public void onSubscribe(Flow.Subscription value) {
            subscription = value;
            value.request(1);
        }

        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if ((long) bytes.size() + buffer.remaining() > MAX_RESPONSE_BYTES) {
                    subscription.cancel();
                    result.completeExceptionally(new TrustLinkFailure(Failure.RESPONSE_TOO_LARGE));
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }

        public void onError(Throwable error) {
            result.completeExceptionally(new TrustLinkFailure(Failure.TIMEOUT_OR_IO));
        }

        public void onComplete() {
            result.complete(bytes.toByteArray());
        }
    }
}
