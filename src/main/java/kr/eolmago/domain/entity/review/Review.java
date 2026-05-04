package kr.eolmago.domain.entity.review;

import kr.eolmago.domain.entity.common.AuditableEntity;
import kr.eolmago.domain.entity.deal.Deal;
import kr.eolmago.domain.entity.user.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(
        name = "reviews",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_reviews_deal", columnNames = {"deal_id"})
        },
        indexes = {
                @Index(name = "idx_reviews_seller", columnList = "seller_id")
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Review extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(nullable = false, updatable = false)
    private Long reviewId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "deal_id", nullable = false)
    private Deal deal;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "seller_id", nullable = false)
    private User seller;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "buyer_id", nullable = false)
    private User buyer;

    @Column(nullable = false)
    private int rating;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    public static Review create(
            Deal deal,
            User seller,
            User buyer,
            int rating,
            String content
    ) {
        Review review = new Review();
        review.deal = deal;
        review.seller = seller;
        review.buyer = buyer;
        review.rating = rating;
        review.content = content;
        return review;
    }
}