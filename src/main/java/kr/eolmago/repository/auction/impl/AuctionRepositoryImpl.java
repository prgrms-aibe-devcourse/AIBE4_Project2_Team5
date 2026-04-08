package kr.eolmago.repository.auction.impl;

import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.Expressions;
import com.querydsl.core.types.dsl.StringExpression;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import kr.eolmago.domain.entity.auction.Auction;
import kr.eolmago.global.config.properties.AuctionRuntimeProperties;
import kr.eolmago.domain.entity.auction.QAuction;
import kr.eolmago.domain.entity.auction.enums.AuctionStatus;
import kr.eolmago.domain.entity.auction.enums.ItemCategory;
import kr.eolmago.dto.api.auction.request.AuctionSearchRequest;
import kr.eolmago.dto.api.auction.response.AuctionDetailDto;
import kr.eolmago.dto.api.auction.response.AuctionListDto;
import kr.eolmago.repository.auction.AuctionRepositoryCustom;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static kr.eolmago.domain.entity.auction.QAuction.auction;
import static kr.eolmago.domain.entity.auction.QAuctionImage.auctionImage;
import static kr.eolmago.domain.entity.auction.QAuctionItem.auctionItem;
import static kr.eolmago.domain.entity.user.QUser.user;
import static kr.eolmago.domain.entity.user.QUserProfile.userProfile;

@Repository
@RequiredArgsConstructor
public class AuctionRepositoryImpl implements AuctionRepositoryCustom {

    private final JPAQueryFactory queryFactory;
    private final AuctionRuntimeProperties auctionRuntimeProperties;
    private final EntityManager entityManager;

    // 경매 목록 조회
    @Override
    public Page<AuctionListDto> searchList(
            Pageable pageable,
            String sortKey,
            AuctionSearchRequest searchRequest
    ) {
        AuctionStatus status = searchRequest != null ? searchRequest.status() : null;
        UUID userId = searchRequest != null ? searchRequest.userId() : null;
        ItemCategory category = searchRequest != null ? searchRequest.category() : null;
        List<String> brands = searchRequest != null ? searchRequest.brands() : null;
        Integer minPrice = searchRequest != null ? searchRequest.minPrice() : null;
        Integer maxPrice = searchRequest != null ? searchRequest.maxPrice() : null;

        OrderSpecifier<?>[] orderSpecifiers = createOrderSpecifiers(sortKey);

        List<AuctionListDto> content = queryFactory
                .select(com.querydsl.core.types.Projections.constructor(
                        AuctionListDto.class,
                        auction.auctionId,
                        auctionItem.auctionItemId,
                        auctionItem.itemName,
                        auction.title,
                        auctionImage.imageUrl,
                        userProfile.nickname,
                        auction.startPrice,
                        auction.currentPrice,
                        auction.finalPrice,
                        auction.bidCount,
                        auction.favoriteCount,
                        auction.endAt,
                        auction.status,
                        auction.endReason
                ))
                .from(auction)
                .innerJoin(auction.auctionItem, auctionItem)
                .innerJoin(auctionImage)
                .on(auctionImage.auctionItem.eq(auctionItem)
                        .and(auctionImage.displayOrder.eq(0)))
                .innerJoin(auction.seller, user)
                .innerJoin(userProfile)
                .on(userProfile.user.eq(user))
                .where(
                        status == null ? null : auction.status.eq(status),
                        userId == null ? null : user.userId.eq(userId),
                        category != null ? auctionItem.category.eq(category) : null,
                        brandsIn(brands),
                        minPrice != null ? auction.currentPrice.goe(minPrice) : null,
                        maxPrice != null ? auction.currentPrice.loe(maxPrice) : null
                )
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .orderBy(orderSpecifiers)
                .fetch();

        JPAQuery<Long> countQuery = queryFactory
                .select(auction.count())
                .from(auction)
                .innerJoin(auction.auctionItem, auctionItem)
                .innerJoin(auctionImage)
                .on(auctionImage.auctionItem.eq(auctionItem)
                        .and(auctionImage.displayOrder.eq(0)))
                .innerJoin(auction.seller, user)
                .innerJoin(userProfile)
                .on(userProfile.user.eq(user))
                .where(
                        status == null ? null : auction.status.eq(status),
                        userId == null ? null : user.userId.eq(userId),
                        category != null ? auctionItem.category.eq(category) : null,
                        brandsIn(brands),
                        minPrice != null ? auction.currentPrice.goe(minPrice) : null,
                        maxPrice != null ? auction.currentPrice.loe(maxPrice) : null
                );

        return PageableExecutionUtils.getPage(content, pageable, countQuery::fetchOne);
    }

    // 경매 상세 조회
    @Override
    public Optional<AuctionDetailDto> findDetailById(UUID auctionId) {
        AuctionDetailDto result = queryFactory
                .select(Projections.constructor(
                        AuctionDetailDto.class,
                        auction.auctionId,
                        auction.title,
                        auction.description,
                        auction.status,
                        auction.startPrice,
                        auction.currentPrice,
                        auction.bidIncrement,
                        auction.bidCount,
                        auction.favoriteCount,
                        auction.startAt,
                        auction.endAt,
                        auction.originalEndAt,
                        auction.durationHours,
                        auction.extendCount,
                        auction.endReason,
                        auction.finalPrice,
                        auction.createdAt,
                        auctionItem.auctionItemId,
                        auctionItem.itemName,
                        auctionItem.category,
                        auctionItem.condition,
                        auctionItem.specs,
                        user.userId,
                        userProfile.nickname,
                        userProfile.tradeCount,
                        auctionImage.imageUrl,
                        Expressions.nullExpression(UUID.class) // highestBidderId
                ))
                .from(auction)
                .innerJoin(auction.auctionItem, auctionItem)
                .innerJoin(auction.seller, user)
                .innerJoin(userProfile)
                .on(userProfile.user.eq(user))
                .leftJoin(auctionImage)
                .on(auctionImage.auctionItem.eq(auctionItem)
                        .and(auctionImage.displayOrder.eq(0)))
                .where(auction.auctionId.eq(auctionId))
                .fetchOne();

        return Optional.ofNullable(result);
    }

    /**
     * 비관적 쓰기 락으로 경매를 조회한다.
     * PostgreSQL lock_timeout 을 매우 짧게 두어 동일 경매 입찰이 길게 줄 서지 않도록 한다.
     * 먼저 락을 잡은 요청이 임계구역을 통과하고, 뒤 요청은 짧게 대기한 뒤 timeout 으로 빠르게 실패한다.
     */
    @Override
    public Optional<Auction> findByIdForUpdate(UUID auctionId) {
        applyLocalLockTimeout(auctionRuntimeProperties.getBid().getLockTimeoutMs());

        Auction result = queryFactory
                .selectFrom(auction)
                .where(auction.auctionId.eq(auctionId))
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .fetchOne();

        return Optional.ofNullable(result);
    }


    private void applyLocalLockTimeout(int lockTimeoutMs) {
        int sanitized = Math.max(lockTimeoutMs, 0);
        entityManager.createNativeQuery("SET LOCAL lock_timeout = '" + sanitized + "ms'").executeUpdate();
    }

    @Override
    public Optional<UUID> findSellerIdByAuctionId(UUID auctionId) {
        QAuction auction = QAuction.auction;

        UUID sellerId = queryFactory
                .select(auction.seller.userId)
                .from(auction)
                .where(auction.auctionId.eq(auctionId))
                .fetchOne();

        return Optional.ofNullable(sellerId);
    }

    @Override
    public void incrementFavoriteCount(UUID auctionId) {
        queryFactory
                .update(auction)
                .set(auction.favoriteCount, auction.favoriteCount.add(1))
                .where(auction.auctionId.eq(auctionId))
                .execute();
    }

    @Override
    public void decrementFavoriteCount(UUID auctionId) {
        queryFactory
                .update(auction)
                .set(auction.favoriteCount,
                        new com.querydsl.core.types.dsl.CaseBuilder()
                                .when(auction.favoriteCount.gt(0))
                                .then(auction.favoriteCount.subtract(1))
                                .otherwise(0))
                .where(auction.auctionId.eq(auctionId))
                .execute();
    }

    @Override
    public int findFavoriteCountByAuctionId(UUID auctionId) {
        Integer count = queryFactory
                .select(auction.favoriteCount)
                .from(auction)
                .where(auction.auctionId.eq(auctionId))
                .fetchOne();

        return count != null ? count : 0;
    }

    private OrderSpecifier<?>[] createOrderSpecifiers(String sortKey) {
        if (sortKey == null || sortKey.isEmpty()) {
            sortKey = "latest";
        }

        List<OrderSpecifier<?>> orders = new ArrayList<>();

        switch (sortKey.toLowerCase()) {
            case "latest" -> orders.add(auction.createdAt.desc());
            case "deadline" -> orders.add(auction.endAt.asc());
            case "price_low" -> orders.add(auction.currentPrice.asc());
            case "price_high" -> orders.add(auction.currentPrice.desc());
            case "popular" -> {
                orders.add(auction.bidCount.desc());
                orders.add(auction.favoriteCount.desc());
            }
            default -> orders.add(auction.createdAt.desc());
        }

        orders.add(auction.auctionId.desc());

        return orders.toArray(new OrderSpecifier[0]);
    }

    private StringExpression brandValue() {
        // specs에서 brand를 text로 뽑음: jsonb_extract_path_text(specs, 'brand')
        return Expressions.stringTemplate(
                "function('jsonb_extract_path_text', {0}, {1})",
                auctionItem.specs,
                Expressions.constant("brand")
        );
    }

    private BooleanExpression brandsIn(List<String> brands) {
        if (brands == null || brands.isEmpty()) {
            return null;
        }

        StringExpression brand = brandValue();

        boolean hasOther = brands.contains("기타");
        List<String> selectedBrands = brands.stream()
                .filter(b -> !"기타".equals(b))
                .toList();

        if (hasOther && selectedBrands.isEmpty()) {
            // "기타"만: Apple, Samsung 제외
            return brand.notIn("Apple", "Samsung");

        } else if (hasOther) {
            // "기타" + 특정 브랜드
            return brand.in(selectedBrands)
                    .or(brand.notIn("Apple", "Samsung"));

        } else {
            // 특정 브랜드만
            return brand.in(selectedBrands);
        }
    }
}
