package dk.trustworks.intranet.aggregates.crm.trustlink.model.enums;

/**
 * How a {@code trustlink_company_alias} row — one TrustLink company name mapped onto one
 * Intra client — came to exist.
 *
 * <p>The distinction is what makes the alias table safe to re-seed every night. The
 * nightly matcher owns {@link #AUTO} rows completely: it may add them, and a later run may
 * conclude something different. It must never write, disable or delete a {@link #MANUAL}
 * row, in either direction — a person looked at the account and asserted something, and an
 * unattended job does not get to overrule that at 02:40.
 *
 * <p>The suppression case is the reason {@code enabled} is a separate column rather than a
 * third source value: a {@code MANUAL} row with {@code enabled = 0} is a person saying
 * "this TrustLink company is not this client, stop adding it", and it has to survive the
 * seeder re-discovering the same name through the typeahead on the next run.
 *
 * <p>Why the table exists at all: TrustLink fragments a company across several names.
 * "Novo Nordisk" carries 203 tier-5 connections and "Novo Nordisk A/S" carries 2, while
 * the Intra client is named "NOVO NORDISK A/S". A one-name mapping would silently throw
 * away 203 relationships, so a client points at a set of names, not one.
 */
public enum AliasSource {

    /** Seeded by the nightly matcher from an exact or normalised name match. Re-derivable. */
    AUTO,

    /** Added or suppressed by a person in the alias editor. The job never touches it. */
    MANUAL
}
