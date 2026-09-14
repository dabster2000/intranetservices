package dk.trustworks.intranet.dao.crm.model.enums;

/**
 * What a {@code client} row may be billed for.
 *
 * <p>{@link #CLIENT} is an end customer, {@link #PARTNER} an intermediary billing entity.
 * PARTNERs are filtered out of all non-billing contexts (timesheets, sales leads, contract
 * clientuuid, staffing) — SPEC-INV-001 §3.1.
 *
 * <p>{@link #PROSPECT} (2026-09-14) is a company Intra knows and has <b>never billed</b>:
 * somebody had a coffee with them, a colleague heard something, a lead was created. It is
 * the one stored fact behind the customers / former / prospects / contacts split; the four
 * RELATIONSHIPS themselves are derived per read from contracts, leads and the band and are
 * never stored (see {@code AccountService.relationshipsForAll()}).
 *
 * <p><b>Why a third value on this enum rather than a flag or a table.</b> Every billing
 * consumer already keys on {@code type}: {@code GET /clients} defaults to
 * {@code ?type=CLIENT}, and the invoice picker, devices, project descriptions and the AM
 * alert summary all pass it explicitly. A third value therefore means every existing
 * consumer keeps excluding prospects until it opts in — safe by default, and each opt-in
 * is one greppable string. A separate table would have meant either a second kind of key
 * on every CRM table that hangs off {@code client.uuid}, or a conversion ritual the moment
 * a prospect wins.
 *
 * <p><b>The transition is one-way and automatic.</b> {@code ContractService.save} flips
 * PROSPECT → CLIENT on the first persisted contract, validates billing completeness there,
 * and syncs to e-conomic there. Nothing flips it back, and
 * {@code AgreementDefaults.groupNumberFor} refuses a PROSPECT outright so no other path
 * can create one as an e-conomic customer by accident. This is deliberately NOT the
 * {@code active} flag removed on 2026-09-01: that was an opinion with no consumer, this is
 * a fact with three.
 *
 * SPEC-INV-001 §3.1; docs/specs/intra-crm-customers-former-prospects-contacts-2026-09-14.md §2.2.
 */
public enum ClientType {
    CLIENT,
    PARTNER,
    PROSPECT
}
