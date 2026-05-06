package kr.eolmago.service.auction;

import kr.eolmago.domain.entity.auction.Auction;
import kr.eolmago.domain.entity.auction.Bid;
import kr.eolmago.domain.entity.auction.enums.AuctionStatus;
import kr.eolmago.domain.entity.user.User;
import kr.eolmago.dto.api.auction.request.BidCreateRequest;
import kr.eolmago.dto.api.auction.response.BidCreateResponse;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class BidTestFixture {

    // 테스트 상수
    public static final int DEFAULT_START_PRICE = 10_000;
    public static final int DEFAULT_BID_INCREMENT = 1_000;
    public static final int DEFAULT_DURATION_HOURS = 24;

    private BidTestFixture() {}


    public static User createMockUser(UUID userId) {
        User user = mock(User.class);
        lenient().when(user.getUserId()).thenReturn(userId);
        return user;
    }

    public static Auction createMockAuction(UUID auctionId, UUID sellerId, AuctionStatus status) {
        return createMockAuction(auctionId, sellerId, status, DEFAULT_START_PRICE, DEFAULT_BID_INCREMENT);
    }

    public static Auction createMockAuction(UUID auctionId, UUID sellerId, AuctionStatus status,
                                            int currentPrice, int bidIncrement) {
        Auction auction = mock(Auction.class);
        User seller = createMockUser(sellerId);

        lenient().when(auction.getAuctionId()).thenReturn(auctionId);
        lenient().when(auction.getStatus()).thenReturn(status);
        lenient().when(auction.getSeller()).thenReturn(seller);
        lenient().when(auction.getCurrentPrice()).thenReturn(currentPrice);
        lenient().when(auction.getBidIncrement()).thenReturn(bidIncrement);
        lenient().when(auction.getEndAt()).thenReturn(OffsetDateTime.now().plusHours(1));
        lenient().when(auction.getOriginalEndAt()).thenReturn(OffsetDateTime.now().plusHours(1));

        return auction;
    }

    public static Bid createMockBid(Auction auction, User bidder, int amount, String clientRequestId) {
        Bid bid = mock(Bid.class);
        lenient().when(bid.getBidId()).thenReturn(1L);
        lenient().when(bid.getAuction()).thenReturn(auction);
        lenient().when(bid.getBidder()).thenReturn(bidder);
        lenient().when(bid.getAmount()).thenReturn(amount);
        lenient().when(bid.getClientRequestId()).thenReturn(clientRequestId);
        return bid;
    }

    public static BidCreateRequest createBidRequest(int amount, String clientRequestId) {
        return new BidCreateRequest(amount, clientRequestId);
    }

    public static BidCreateResponse createBidResponse(Long bidId, UUID auctionId, int amount,
                                                       int currentHighest, int minAcceptable,
                                                       boolean extensionApplied, UUID highestBidderId) {
        return new BidCreateResponse(
                bidId, auctionId, amount, currentHighest, minAcceptable,
                OffsetDateTime.now().plusHours(1), extensionApplied, highestBidderId
        );
    }

    public static String generateClientRequestId() {
        return "req-" + UUID.randomUUID().toString().substring(0, 16);
    }

    public static UUID generateUserId() {
        return UUID.randomUUID();
    }

    public static UUID generateAuctionId() {
        return UUID.randomUUID();
    }
}
