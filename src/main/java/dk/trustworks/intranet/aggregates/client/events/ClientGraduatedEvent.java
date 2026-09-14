package dk.trustworks.intranet.aggregates.client.events;

/**
 * A prospect became a customer: its first contract was persisted and
 * {@code ContractService.save} flipped {@code client.type} from PROSPECT to CLIENT.
 *
 * <p>Fired inside that transaction and observed at
 * {@code TransactionPhase.AFTER_SUCCESS}, so the e-conomic customer is created only once
 * the contract that justifies it has actually committed — and, just as importantly, the
 * HTTP round trip to e-conomic happens with no transaction open. A model call inside a
 * transaction holds a pooled connection for its whole duration, which is the §P9 M1 rule
 * the rest of this codebase already enforces.
 *
 * @param clientUuid the client that graduated
 * @param clientName its name, for the log line — the observer re-reads the row itself
 */
public record ClientGraduatedEvent(String clientUuid, String clientName) {
}
