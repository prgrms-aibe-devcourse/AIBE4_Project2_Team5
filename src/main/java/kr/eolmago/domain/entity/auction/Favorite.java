package kr.eolmago.domain.entity.auction;

import kr.eolmago.domain.entity.common.CreatedAtEntity;
import kr.eolmago.domain.entity.user.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(
        name = "favorites",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_favorites_user_auction",
                        columnNames = {"user_id", "auction_id"}
                )
        },
        indexes = {
                @Index(name = "idx_favorites_user_created", columnList = "user_id,created_at"),
                @Index(name = "idx_favorites_auction", columnList = "auction_id")
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Favorite extends CreatedAtEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(nullable = false, updatable = false)
    private Long favoriteId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "auction_id", nullable = false)
    private Auction auction;

    public static Favorite create(User user, Auction auction) {
        Favorite favorite = new Favorite();
        favorite.user = user;
        favorite.auction = auction;
        return favorite;
    }
}
