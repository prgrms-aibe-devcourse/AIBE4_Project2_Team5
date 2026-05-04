package kr.eolmago.domain.entity.auction;

import kr.eolmago.domain.entity.common.CreatedAtEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(
        name = "auction_images",
        indexes = {
                @Index(name = "idx_auction_images_item_order", columnList = "auction_item_id,display_order")
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuctionImage extends CreatedAtEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(nullable = false, updatable = false)
    private Long auctionImageId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "auction_item_id", nullable = false)
    private AuctionItem auctionItem;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String imageUrl;

    @Column(nullable = false)
    private int displayOrder;

    public static AuctionImage create(
            AuctionItem auctionItem,
            String imageUrl,
            int displayOrder
    ) {
        AuctionImage image = new AuctionImage();
        image.auctionItem = auctionItem;
        image.imageUrl = imageUrl;
        image.displayOrder = displayOrder;
        return image;
    }
}