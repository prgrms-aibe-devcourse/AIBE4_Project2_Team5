package kr.eolmago.domain.entity.auction;

import kr.eolmago.domain.entity.common.CreatedAtEntity;
import kr.eolmago.domain.entity.user.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "bids")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Bid extends CreatedAtEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(nullable = false, updatable = false)
    private Long bidId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "auction_id", nullable = false)
    private Auction auction;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bidder_id", nullable = false)
    private User bidder;

    @Column(nullable = false)
    private int amount;

    // 중복 입찰 방지 - 요청 고유번호
    // (bidder_id, client_request_id) 복합 unique 제약으로 멱등성 보장
    @Column(name = "client_request_id", nullable = false, length = 64)
    private String clientRequestId;

    public static Bid create(
            Auction auction,
            User bidder,
            int amount,
            String clientRequestId
    ) {
        Bid bid = new Bid();
        bid.auction = auction;
        bid.bidder = bidder;
        bid.amount = amount;
        bid.clientRequestId = clientRequestId;
        return bid;
    }
}
