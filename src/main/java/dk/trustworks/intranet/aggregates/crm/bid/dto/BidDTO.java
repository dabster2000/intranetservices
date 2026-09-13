package dk.trustworks.intranet.aggregates.crm.bid.dto;

import dk.trustworks.intranet.aggregates.crm.account.dto.PersonDTO;
import dk.trustworks.intranet.aggregates.crm.bid.model.Bid;
import dk.trustworks.intranet.domain.user.entity.User;

import java.time.LocalDate;

/** One bid as the Bids tab renders it. Mirrors {@code IAccountBid} in the frontend. */
public record BidDTO(
        String uuid,
        String clientUuid,
        String leadUuid,
        String title,
        String type,
        String goNoGo,
        Double price,
        LocalDate dueDate,
        String outcome,
        String competitor,
        String postMortem,
        PersonDTO owner) {

    public static BidDTO from(Bid bid) {
        return new BidDTO(
                bid.getUuid(),
                bid.getClientUuid(),
                bid.getLeadUuid(),
                bid.getTitle(),
                bid.getType().name(),
                bid.getGoNoGo().name(),
                bid.getPrice(),
                bid.getDueDate(),
                bid.getOutcome().name(),
                bid.getCompetitor(),
                bid.getPostMortem(),
                PersonDTO.from(User.findById(bid.getOwnerUuid())));
    }
}
