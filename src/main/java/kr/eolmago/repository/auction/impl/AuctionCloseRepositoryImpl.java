package kr.eolmago.repository.auction.impl;

import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import kr.eolmago.domain.entity.auction.Auction;
import kr.eolmago.global.config.properties.AuctionRuntimeProperties;
import kr.eolmago.domain.entity.auction.enums.AuctionStatus;
import kr.eolmago.dto.view.auction.AuctionEndAtView;
import kr.eolmago.repository.auction.AuctionCloseRepositoryCustom;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static kr.eolmago.domain.entity.auction.QAuction.auction;

@Repository
@RequiredArgsConstructor
public class AuctionCloseRepositoryImpl implements AuctionCloseRepositoryCustom {

    private final JPAQueryFactory queryFactory;
    private final AuctionRuntimeProperties auctionRuntimeProperties;
    private final EntityManager entityManager;

    // 경매 조회(비관적 락 사용)
    @Override
    public Optional<Auction> findByIdForUpdate(UUID auctionId) {
        applyLocalLockTimeout(auctionRuntimeProperties.getClose().getLockTimeoutMs());

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

    // 특정 상태의 경매들의 ID와 endAt 조회
    @Override
    public List<AuctionEndAtView> findAllEndAtByStatus(AuctionStatus status) {
        return queryFactory
                .select(Projections.constructor(
                        AuctionEndAtView.class,
                        auction.auctionId,
                        auction.endAt
                ))
                .from(auction)
                .where(auction.status.eq(status))
                .fetch();
    }

    // 마감할 경매 ID 조회
    @Override
    public List<UUID> findIdsToClose(AuctionStatus status, OffsetDateTime now, Pageable pageable) {
        return queryFactory
                .select(auction.auctionId)
                .from(auction)
                .where(
                        auction.status.eq(status),
                        auction.endAt.loe(now)
                )
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();
    }
}