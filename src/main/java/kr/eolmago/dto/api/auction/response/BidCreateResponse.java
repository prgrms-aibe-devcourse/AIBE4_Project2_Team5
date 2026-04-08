package kr.eolmago.dto.api.auction.response;

import kr.eolmago.domain.entity.auction.Auction;
import kr.eolmago.domain.entity.auction.Bid;

import java.time.OffsetDateTime;
import java.util.UUID;

public record BidCreateResponse(
        Long bidId,
        UUID auctionId,
        int acceptedAmount,
        int currentHighestAmount,
        int minAcceptableAmount,
        OffsetDateTime endAt,
        boolean extensionApplied,
        UUID highestBidderId
) {

    public static BidCreateResponse from(
            Auction auction,
            Bid bid,
            boolean extensionApplied,
            UUID highestBidderId
    ) {
        int currentHighest = auction.getCurrentPrice();
        int minAcceptable = currentHighest + auction.getBidIncrement();

        return new BidCreateResponse(
                bid.getBidId(),
                auction.getAuctionId(),
                bid.getAmount(),
                currentHighest,
                minAcceptable,
                auction.getEndAt(),
                extensionApplied,
                highestBidderId
        );
    }
}