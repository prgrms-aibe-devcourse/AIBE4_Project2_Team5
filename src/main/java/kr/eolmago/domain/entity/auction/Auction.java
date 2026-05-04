package kr.eolmago.domain.entity.auction;

import kr.eolmago.domain.entity.auction.enums.AuctionEndReason;
import kr.eolmago.domain.entity.auction.enums.AuctionStatus;
import kr.eolmago.domain.entity.common.CreatedAtEntity;
import kr.eolmago.domain.entity.user.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(
        name = "auctions",
        indexes = {
                @Index(name = "idx_auctions_status_end_at", columnList = "status,end_at"),
                @Index(name = "idx_auctions_seller", columnList = "seller_id")
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Auction extends CreatedAtEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(nullable = false, updatable = false)
    private UUID auctionId;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "auction_item_id", nullable = false)
    private AuctionItem auctionItem;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "seller_id", nullable = false)
    private User seller;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "buyer_id")
    private User buyer;

    @Column(nullable = false, length = 100)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AuctionStatus status;

    @Column(nullable = false)
    private Integer startPrice;

    @Column(nullable = false)
    private Integer currentPrice;

    @Column(nullable = false)
    private Integer bidIncrement;

    @Column(nullable = false)
    private int bidCount;

    @Column(nullable = false)
    private int favoriteCount;

    @Column
    private OffsetDateTime startAt;

    @Column
    private OffsetDateTime endAt;

    @Column
    private OffsetDateTime originalEndAt;

    @Column(nullable = false)
    private Integer durationHours;

    @Column(nullable = false)
    private int extendCount;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private AuctionEndReason endReason;

    @Column
    private Long finalPrice;

    public static Auction create (
            AuctionItem auctionItem,
            User seller,
            String title,
            String description,
            AuctionStatus status,
            int startPrice,
            int bidIncrement,
            int durationHours,
            OffsetDateTime startAt,
            OffsetDateTime endAt
    ) {
        Auction auction = new Auction();
        auction.auctionItem = auctionItem;
        auction.seller = seller;
        auction.title = title;
        auction.description = description;
        auction.status = status;
        auction.startPrice = startPrice;
        auction.currentPrice = startPrice;
        auction.bidIncrement = bidIncrement;
        auction.durationHours = durationHours;
        auction.startAt = startAt;
        auction.endAt = endAt;
        auction.originalEndAt = endAt;
        auction.bidCount = 0;
        auction.favoriteCount = 0;
        auction.extendCount = 0;

        return auction;
    }

    // 경매 수정
    public void updateDraft(String title, String description, int startPrice, int bidIncrement, int durationHours) {
        this.title = title;
        this.description = description;
        this.startPrice = startPrice;
        this.bidIncrement = bidIncrement;
        this.durationHours = durationHours;
    }

    // 경매 게시
    public void publish(OffsetDateTime startAt, OffsetDateTime endAt) {
        this.status = AuctionStatus.LIVE;
        this.startAt = startAt;
        this.endAt = endAt;
        this.originalEndAt = endAt;
        this.currentPrice = this.startPrice;
    }

    // 입찰
    public void updateBid(int newAmount) {
        this.currentPrice = newAmount;
        this.bidCount++;
    }

    // 경매 종료 시간 연장
    public void extendEndTime(OffsetDateTime newEndAt, int newDurationHours) {
        this.endAt = newEndAt;
        this.extendCount++;
        this.durationHours = newDurationHours;
    }

    // 낙찰
    public void closeAsSold(User buyer, Long finalPrice) {
        this.status = AuctionStatus.ENDED_SOLD;
        this.buyer = buyer;
        this.finalPrice = finalPrice;
        this.endReason = AuctionEndReason.SOLD;
    }

    // 유찰
    public void closeAsUnsold() {
        this.status = AuctionStatus.ENDED_UNSOLD;
        this.endReason = AuctionEndReason.NO_BIDS;
        this.finalPrice = 0L;
    }

    // 판매자 경매 취소
    public void cancelBySeller() {
        this.status = AuctionStatus.ENDED_UNSOLD;
        this.endReason = AuctionEndReason.SELLER_STOPPED;
        this.finalPrice = 0L;
    }
}