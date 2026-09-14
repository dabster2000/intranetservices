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
 * <p><b>No billable row exists without a company registration number</b> (Hans,
 * 2026-09-14). The rule used to end at the Danish border: {@code billingProblem} asked for
 * a CVR only when {@code billingCountry} was DK, so picking any other country was a way to
 * create a customer identified by nothing but a name somebody typed. Every client and
 * partner now needs a registry identifier, and the country decides only its FORMAT — eight
 * digits for a Danish CVR, a registry number of the issuing country's own shape for
 * anywhere else. It is the same {@code cvr} column either way; renaming it would mean
 * touching sixty call sites to say nothing new.
 *
 * <p>Pure static methods with no CDI: the fast tier that gates every deploy holds them
 * without booting Quarkus or a database.
 */
public final class ClientBillingValidator {

    public static final Set<String> VALID_CURRENCIES = Set.of("DKK", "EUR", "NOK", "SEK", "USD", "GBP");
    public static final Pattern CVR_PATTERN = Pattern.compile("^\\d{8}$");
    public static final Pattern COUNTRY_PATTERN = Pattern.compile("^[A-Z]{2}$");

    /**
     * A foreign registry number: 4 to 20 characters, opening on a letter or a digit, and
     * carrying only separators a registry actually prints — the space, dot, hyphen and
     * slash of a Swedish {@code 556016-0680}, a German {@code HRB 12345} or a Dutch
     * {@code 12345678}. The ceiling is the {@code client.cvr} column, {@code varchar(20)};
     * a longer value would be truncated on the way in and then never match the registry
     * again.
     *
     * <p>Deliberately not a per-country format table. Intra has no foreign clients today,
     * and eleven guessed national formats would refuse the first real one on a technicality
     * nobody could explain — the point of the rule is that a customer carries a registry
     * identifier at all, not that Intra validates the registries of Europe.
     */
    public static final Pattern FOREIGN_REGISTRATION_PATTERN =
            Pattern.compile("^[A-Za-z0-9][A-Za-z0-9 ./-]{3,19}$");

    private ClientBillingValidator() {
    }

    /**
     * Whether this row's registry identifier is read as a Danish CVR.
     *
     * <p><b>An absent country counts as Danish</b>, which is the strict reading: it applies
     * the eight-digit format rather than the lax foreign one. {@code ClientResource} defaults
     * the country to DK before it validates anything, so a blank one reaches here only from
     * a caller that never set it — {@code ContractService.graduateProspect}, reading a row
     * straight out of the database — and every row in that table is Danish.
     */
    public static boolean isDanish(Client client) {
        String country = client.getBillingCountry();
        return country == null || country.isBlank() || "DK".equalsIgnoreCase(country.trim());
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
        if (cvr != null && !cvr.isBlank()) {
            // The country picks the format, never whether one is asked for. A prospect is
            // not asked for a number, but a wrong one is wrong whoever typed it — storing
            // it would mean the graduation gate later accepts eight junk digits.
            if (isDanish(client)) {
                if (!CVR_PATTERN.matcher(cvr.trim()).matches()) {
                    return "CVR must be exactly 8 digits";
                }
            } else if (!FOREIGN_REGISTRATION_PATTERN.matcher(cvr.trim()).matches()) {
                return "Company registration number must be 4-20 characters (letters, digits, and . - / )";
            }
        }
        String ean = client.getEan();
        if (ean != null && !ean.isBlank() && !EanValidator.isValid(ean)) {
            return "EAN must be exactly 13 digits and pass GS1 Modulo 10";
        }
        return null;
    }

    /**
     * Everything {@link #formatProblem}, plus what a billable row must actually HAVE: a
     * company registration number, whatever country it is registered in.
     *
     * <p>The country used to decide whether the question was asked at all. It now decides
     * only what the answer has to look like — a client whose country is SE needs a Swedish
     * organisationsnummer exactly as firmly as a Danish one needs its CVR, because the
     * reason for the rule is that a company Intra invoices is identified in a public
     * registry, and that reason does not stop at Kruså.
     *
     * @return the sentence to show, naming the missing field so a wizard can send the
     *         person to the right form, or null when the row can be billed
     */
    public static String billingProblem(Client client) {
        String format = formatProblem(client);
        if (format != null) {
            return format;
        }
        String cvr = client.getCvr();
        if (cvr == null || cvr.isBlank()) {
            return isDanish(client)
                    ? "CVR is required"
                    : "A company registration number is required for clients outside Denmark";
        }
        return null;
    }
}
