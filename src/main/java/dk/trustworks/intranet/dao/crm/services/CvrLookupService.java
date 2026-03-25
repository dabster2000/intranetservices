package dk.trustworks.intranet.dao.crm.services;

import dk.trustworks.intranet.dao.crm.client.CvrApiClient;
import dk.trustworks.intranet.dao.crm.client.CvrApiResponse;
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
 * <p>Wraps the {@link CvrApiClient} with caching, input validation,
 * and error-code-to-HTTP-status mapping. CVR API failures never block
 * client creation — this is a convenience lookup, not a gate.
 *
 * <p>Error code mapping from cvrapi.dk:
 * <ul>
 *   <li>{@code NOT_FOUND} → 404 Not Found</li>
 *   <li>{@code INVALID_VAT} → 400 Bad Request</li>
 *   <li>{@code QUOTA_EXCEEDED} → 429 Too Many Requests</li>
 *   <li>{@code BANNED} → 502 Bad Gateway</li>
 *   <li>{@code INVALID_UA} → 502 Bad Gateway (configuration error)</li>
 *   <li>{@code INTERNAL_ERROR} → 502 Bad Gateway</li>
 * </ul>
 */
@JBossLog
@ApplicationScoped
public class CvrLookupService {

    private static final String CVR_PATTERN = "^\\d{8}$";
    private static final java.util.Set<String> ALLOWED_COUNTRIES = java.util.Set.of("dk", "no");

    @Inject
    @RestClient
    CvrApiClient cvrApiClient;

    /**
     * Looks up a company by CVR number. Results are cached to avoid
     * redundant API calls against the 50/day free-tier limit.
     *
     * @param cvr     the 8-digit CVR number
     * @param country the country code (default: "dk")
     * @return the CVR API response with company data
     * @throws WebApplicationException with appropriate HTTP status on error
     */
    @CacheResult(cacheName = "cvr-lookup")
    public CvrApiResponse lookupByCvr(@CacheKey String cvr, @CacheKey String country) {
        validateCvrFormat(cvr);
        validateCountry(country);
        log.infof("CVR lookup by VAT: cvr=%s, country=%s", cvr, country);
        try {
            CvrApiResponse response = cvrApiClient.lookupByVat(cvr, country);
            if (response.hasError()) {
                log.warnf("CVR API returned error for vat=%s: %s", cvr, response.error);
                throw mapCvrError(response.error, cvr);
            }
            log.infof("CVR lookup success: cvr=%s, name=%s", cvr, response.name);
            return response;
        } catch (WebApplicationException e) {
            throw e;
        } catch (Exception e) {
            log.errorf(e, "CVR API call failed for vat=%s", cvr);
            throw new WebApplicationException(
                    "CVR API is unavailable. Please enter company details manually.",
                    Response.Status.BAD_GATEWAY);
        }
    }

    /**
     * Searches for a company by name. Not cached because name searches
     * are less deterministic and less likely to be repeated.
     *
     * @param name    the company name to search for
     * @param country the country code (default: "dk")
     * @return the CVR API response with company data
     * @throws WebApplicationException with appropriate HTTP status on error
     */
    public CvrApiResponse searchByName(String name, String country) {
        if (name == null || name.isBlank()) {
            throw new WebApplicationException("Company name is required for search",
                    Response.Status.BAD_REQUEST);
        }
        validateCountry(country);
        log.infof("CVR search by name: name=%s, country=%s", name, country);
        try {
            CvrApiResponse response = cvrApiClient.searchByName(name, country);
            if (response.hasError()) {
                log.warnf("CVR API returned error for name=%s: %s", name, response.error);
                throw mapCvrError(response.error, name);
            }
            log.infof("CVR search success: name=%s, found=%s (cvr=%d)", name, response.name, response.vat);
            return response;
        } catch (WebApplicationException e) {
            throw e;
        } catch (Exception e) {
            log.errorf(e, "CVR API call failed for name=%s", name);
            throw new WebApplicationException(
                    "CVR API is unavailable. Please enter company details manually.",
                    Response.Status.BAD_GATEWAY);
        }
    }

    /**
     * Validates that the country code is in the allowlist.
     */
    private void validateCountry(String country) {
        if (country == null || !ALLOWED_COUNTRIES.contains(country.toLowerCase())) {
            throw new WebApplicationException(
                    "Unsupported country code. Allowed values: dk, no",
                    Response.Status.BAD_REQUEST);
        }
    }

    /**
     * Validates that the CVR number is exactly 8 digits.
     */
    private void validateCvrFormat(String cvr) {
        if (cvr == null || !cvr.matches(CVR_PATTERN)) {
            throw new WebApplicationException(
                    "Invalid CVR number. Must be exactly 8 digits.",
                    Response.Status.BAD_REQUEST);
        }
    }

    /**
     * Maps CVR API error codes to appropriate HTTP exceptions.
     *
     * @param errorCode the error code from the CVR API response
     * @param query     the search query (for error messages)
     * @return a WebApplicationException with the mapped HTTP status
     */
    private WebApplicationException mapCvrError(String errorCode, String query) {
        return switch (errorCode) {
            case "NOT_FOUND" -> new WebApplicationException(
                    "No company found for: " + query,
                    Response.Status.NOT_FOUND);
            case "INVALID_VAT" -> new WebApplicationException(
                    "Invalid CVR number format: " + query,
                    Response.Status.BAD_REQUEST);
            case "QUOTA_EXCEEDED" -> new WebApplicationException(
                    "CVR API daily quota exceeded. Please try again tomorrow or enter details manually.",
                    429);
            case "BANNED", "INVALID_UA", "INTERNAL_ERROR" -> new WebApplicationException(
                    "CVR API is unavailable. Please enter company details manually.",
                    Response.Status.BAD_GATEWAY);
            default -> new WebApplicationException(
                    "CVR API returned an unexpected error: " + errorCode,
                    Response.Status.BAD_GATEWAY);
        };
    }
}
