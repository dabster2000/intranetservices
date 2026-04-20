package dk.trustworks.intranet.dao.crm.services;

import dk.trustworks.intranet.dao.crm.client.CvrApiClient;
import dk.trustworks.intranet.dao.crm.client.CvrApiResponse;
import dk.trustworks.intranet.dao.crm.client.VirkdataResponse;
import io.quarkus.cache.CacheKey;
import io.quarkus.cache.CacheResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.extern.jbosslog.JBossLog;
import org.eclipse.microprofile.rest.client.inject.RestClient;

/**
 * Application service for CVR (Danish company registry) lookups.
 *
 * <p>Wraps the Virkdata REST client with caching, input validation, and
 * error-code-to-HTTP-status mapping. Upstream failures never block client
 * creation — this is a convenience lookup, not a gate.
 *
 * <p>Virkdata error-code mapping (see virkdata.dk docs):
 * <ul>
 *   <li>{@code 1001/1002/1003} (auth problems) → 502 (server configuration issue)</li>
 *   <li>{@code 2001/2002} (invalid / unauthorized property) → 502</li>
 *   <li>{@code 3001} (missing request value) → 400</li>
 *   <li>{@code 3002} (no_results) → 404</li>
 *   <li>{@code 4001} (invalid format) → 502</li>
 *   <li>{@code 5001/5002} (invalid / not-allowed country) → 400</li>
 *   <li>{@code 6001} (test connection failed) → 502</li>
 *   <li>{@code 7001/7002/8001} (quota / throttling) → 429</li>
 * </ul>
 */
@JBossLog
@ApplicationScoped
public class CvrLookupService {

    private static final String CVR_PATTERN = "^\\d{8}$";

    /** Subscription covers Denmark only (Virkdata 5002 if other code used). */
    private static final java.util.Set<String> ALLOWED_COUNTRIES = java.util.Set.of("dk");

    @Inject
    @RestClient
    CvrApiClient cvrApiClient;

    /**
     * Looks up a company by CVR number. Results are cached.
     *
     * @param cvr     the 8-digit CVR number
     * @param country the country code (only {@code dk} is supported)
     * @return the CVR response with company data
     * @throws WebApplicationException with appropriate HTTP status on error
     */
    @CacheResult(cacheName = "cvr-lookup")
    public CvrApiResponse lookupByCvr(@CacheKey String cvr, @CacheKey String country) {
        validateCvrFormat(cvr);
        validateCountry(country);
        log.infof("CVR lookup by number: cvr=%s, country=%s", cvr, country);
        VirkdataResponse raw = callVirkdata(cvr, country);
        CvrApiResponse result = toCvrApiResponse(raw);
        log.infof("CVR lookup success: cvr=%s, name=%s", cvr, result.name);
        return result;
    }

    /**
     * Searches for a company by name. Not cached — name searches are
     * less deterministic and less likely to be repeated.
     *
     * @param name    the company name to search for
     * @param country the country code (only {@code dk} is supported)
     * @return the CVR response with company data
     * @throws WebApplicationException with appropriate HTTP status on error
     */
    public CvrApiResponse searchByName(String name, String country) {
        if (name == null || name.isBlank()) {
            throw new WebApplicationException("Company name is required for search",
                    Response.Status.BAD_REQUEST);
        }
        validateCountry(country);
        log.infof("CVR search by name: name=%s, country=%s", name, country);
        VirkdataResponse raw = callVirkdata(name, country);
        CvrApiResponse result = toCvrApiResponse(raw);
        log.infof("CVR search success: name=%s, found=%s (cvr=%d)", name, result.name, result.vat);
        return result;
    }

    /**
     * Invokes Virkdata and translates transport / soft errors into
     * {@link WebApplicationException}s with appropriate HTTP statuses.
     */
    private VirkdataResponse callVirkdata(String search, String country) {
        try {
            VirkdataResponse response = cvrApiClient.search(search, country, "json");
            if (response.hasError()) {
                log.warnf("Virkdata returned error for search=%s: code=%d response=%s",
                        search, response.errorCode, response.response);
                throw mapVirkdataError(response.errorCode, response.response, search);
            }
            return response;
        } catch (WebApplicationException e) {
            throw e;
        } catch (Exception e) {
            log.errorf(e, "Virkdata API call failed for search=%s", search);
            throw new WebApplicationException(
                    "CVR API is unavailable. Please enter company details manually.",
                    Response.Status.BAD_GATEWAY);
        }
    }

    /**
     * Projects the Virkdata wire format onto the stable outgoing
     * {@link CvrApiResponse} contract consumed by the frontend.
     *
     * <p>Virkdata returns {@code vat}/{@code zipcode}/{@code industrycode} as
     * JSON strings despite its docs describing them as integers; we parse
     * them defensively. {@code companycode} has no direct equivalent in
     * Virkdata and is always {@code 0}; use {@code companydesc}/{@code
     * companytype} for display.
     */
    private CvrApiResponse toCvrApiResponse(VirkdataResponse raw) {
        CvrApiResponse dto = new CvrApiResponse();
        dto.vat = parseLongOrZero(raw.vat);
        dto.name = raw.name;
        dto.address = raw.address;
        dto.zipcode = raw.zipcode;
        dto.city = raw.city;
        dto.isProtected = raw.isProtected;
        dto.phone = raw.phone;
        dto.email = raw.email;
        dto.startdate = raw.startdate;
        dto.industrycode = parseIntOrZero(raw.industrycode);
        dto.industrydesc = raw.industrydesc;
        dto.companycode = 0; // Not provided by Virkdata
        dto.companydesc = raw.companydesc;
        dto.error = null;
        return dto;
    }

    private static long parseLongOrZero(String s) {
        if (s == null || s.isBlank()) return 0L;
        try { return Long.parseLong(s.trim()); }
        catch (NumberFormatException e) { return 0L; }
    }

    private static int parseIntOrZero(String s) {
        if (s == null || s.isBlank()) return 0;
        try { return Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { return 0; }
    }

    private void validateCountry(String country) {
        if (country == null || !ALLOWED_COUNTRIES.contains(country.toLowerCase())) {
            throw new WebApplicationException(
                    "Unsupported country code. Allowed values: dk",
                    Response.Status.BAD_REQUEST);
        }
    }

    private void validateCvrFormat(String cvr) {
        if (cvr == null || !cvr.matches(CVR_PATTERN)) {
            throw new WebApplicationException(
                    "Invalid CVR number. Must be exactly 8 digits.",
                    Response.Status.BAD_REQUEST);
        }
    }

    private WebApplicationException mapVirkdataError(Integer code, String responseKey, String query) {
        int c = code == null ? -1 : code;
        return switch (c) {
            case 3002 -> new WebApplicationException(
                    "No company found for: " + query,
                    Response.Status.NOT_FOUND);
            case 3001 -> new WebApplicationException(
                    "Missing search value",
                    Response.Status.BAD_REQUEST);
            case 5001, 5002 -> new WebApplicationException(
                    "Invalid or unsupported country code",
                    Response.Status.BAD_REQUEST);
            case 7001, 7002, 8001 -> new WebApplicationException(
                    "CVR API quota exceeded. Please try again later or enter details manually.",
                    429);
            case 1001, 1002, 1003, 2001, 2002, 4001, 6001 -> new WebApplicationException(
                    "CVR API is unavailable (upstream configuration issue). Please enter company details manually.",
                    Response.Status.BAD_GATEWAY);
            default -> new WebApplicationException(
                    "CVR API returned an unexpected error: " + responseKey + " (" + code + ")",
                    Response.Status.BAD_GATEWAY);
        };
    }
}
