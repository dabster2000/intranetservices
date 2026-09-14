package dk.trustworks.intranet.dao.crm.services;

import dk.trustworks.intranet.dao.crm.model.Client;
import dk.trustworks.intranet.utils.EanValidator;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * What a row has to carry before it can be billed.
 *
 * <p>Two places ask this question and they must ask it the same way: the client form, when
 * somebody creates or edits a CLIENT or a PARTNER, and {@code ContractService.save}, when a
 * PROSPECT's first contract makes it a customer. A prospect is deliberately NOT asked for
 * any of it — requiring a CVR to write down that somebody had a coffee with a company is
 * exactly what stopped people writing it down — so graduation is the first minute these
 * rules are true, and the rules have to be the same ones or a company could pass the form
 * and fail the contract, or worse, the other way round.
 *
 * <p>Pure static methods with no CDI: the fast tier that gates every deploy holds them
 * without booting Quarkus or a database.
 */
public final class ClientBillingValidator {

    public static final Set<String> VALID_CURRENCIES = Set.of("DKK", "EUR", "NOK", "SEK", "USD", "GBP");
    public static final Pattern CVR_PATTERN = Pattern.compile("^\\d{8}$");
    public static final Pattern COUNTRY_PATTERN = Pattern.compile("^[A-Z]{2}$");

    private ClientBillingValidator() {
    }

    /**
     * Format checks that apply to whatever has been filled in, prospect or not. A wrong
     * currency code or a mistyped EAN is wrong whoever typed it.
     *
     * @return the sentence to show, or null when everything present is well formed
     */
    public static String formatProblem(Client client) {
        if (client == null) {
            return "A client is required";
        }
        if (client.getName() == null || client.getName().trim().length() < 2) {
            return "Client name is required (min 2 characters)";
        }
        String country = client.getBillingCountry();
        if (country != null && !country.isBlank() && !COUNTRY_PATTERN.matcher(country).matches()) {
            return "Invalid country code";
        }
        String currency = client.getCurrency();
        if (currency != null && !currency.isBlank() && !VALID_CURRENCIES.contains(currency)) {
            return "Invalid currency code";
        }
        String cvr = client.getCvr();
        if (cvr != null && !cvr.isBlank() && !CVR_PATTERN.matcher(cvr.trim()).matches()) {
            return "CVR must be exactly 8 digits";
        }
        String ean = client.getEan();
        if (ean != null && !ean.isBlank() && !EanValidator.isValid(ean)) {
            return "EAN must be exactly 13 digits and pass GS1 Modulo 10";
        }
        return null;
    }

    /**
     * Everything {@link #formatProblem}, plus what a billable row must actually HAVE: a
     * Danish customer needs a CVR.
     *
     * @return the sentence to show, naming the missing field so a wizard can send the
     *         person to the right form, or null when the row can be billed
     */
    public static String billingProblem(Client client) {
        String format = formatProblem(client);
        if (format != null) {
            return format;
        }
        boolean isDanish = "DK".equals(client.getBillingCountry());
        String cvr = client.getCvr();
        if (isDanish && (cvr == null || cvr.isBlank())) {
            return "CVR is required for Danish clients";
        }
        return null;
    }
}
