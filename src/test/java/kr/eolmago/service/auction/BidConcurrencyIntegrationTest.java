package kr.eolmago.service.auction;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import kr.eolmago.domain.entity.auction.Auction;
import kr.eolmago.domain.entity.auction.AuctionItem;
import kr.eolmago.domain.entity.auction.Bid;
import kr.eolmago.domain.entity.auction.enums.AuctionStatus;
import kr.eolmago.domain.entity.auction.enums.ItemCategory;
import kr.eolmago.domain.entity.auction.enums.ItemCondition;
import kr.eolmago.domain.entity.user.User;
import kr.eolmago.domain.entity.user.enums.UserRole;
import kr.eolmago.dto.api.auction.request.BidCreateRequest;
import kr.eolmago.dto.api.auction.response.BidCreateResponse;
import kr.eolmago.global.exception.BusinessException;
import kr.eolmago.global.exception.ErrorCode;
import kr.eolmago.repository.auction.AuctionItemRepository;
import kr.eolmago.repository.auction.AuctionRepository;
import kr.eolmago.repository.auction.BidRepository;
import kr.eolmago.repository.user.UserRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;


// 입찰 동시성 통합 테스트
@SpringBootTest
@Testcontainers
@ActiveProfiles("test")
@DisplayName("입찰 동시성 통합 테스트 (PostgreSQL Testcontainers)")
class BidConcurrencyIntegrationTest {

    private static final int CONCURRENT_REQUESTS_SINGLE_AUCTION = 100;
    private static final int CONCURRENT_AUCTIONS_COUNT = 5;
    private static final int CONCURRENT_REQUESTS_PER_AUCTION = 20;
    private static final int START_PRICE = 10_000;
    private static final int BID_INCREMENT = 1_000;
    private static final int DURATION_HOURS = 24;
    private static final int THREAD_POOL_SIZE = 50;
    private static final long TIMEOUT_SECONDS = 60;

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("eolmago_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");

        // HikariCP 커넥션 풀 설정
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 60);
        registry.add("spring.datasource.hikari.minimum-idle", () -> 10);
        registry.add("spring.datasource.hikari.connection-timeout", () -> 5000);

        // Redis 비활성화
        registry.add("spring.data.redis.host", () -> "localhost");
        registry.add("spring.data.redis.port", () -> "6379");
    }

    @Autowired
    private BidService bidService;

    @Autowired
    private AuctionRepository auctionRepository;

    @Autowired
    private AuctionItemRepository auctionItemRepository;

    @Autowired
    private BidRepository bidRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @PersistenceContext
    private EntityManager entityManager;

    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        // 테스트 데이터 정리
        bidRepository.deleteAll();
        auctionRepository.deleteAll();
        auctionItemRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Nested
    @DisplayName("단일 경매 동시 입찰")
    class SingleAuctionConcurrentBids {

        @Test
        @DisplayName("100개 동시 입찰 시 정합성 유지")
        void given100ConcurrentRequests_whenBid_thenMaintainConsistency() throws InterruptedException {
            // Given: 경매 1개, 유저 100명 준비
            User seller = createAndSaveUser();
            Auction auction = createAndSaveLiveAuction(seller);
            List<User> bidders = createAndSaveUsers(CONCURRENT_REQUESTS_SINGLE_AUCTION);

            CountDownLatch readyLatch = new CountDownLatch(CONCURRENT_REQUESTS_SINGLE_AUCTION);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(CONCURRENT_REQUESTS_SINGLE_AUCTION);

            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger lockBusyCount = new AtomicInteger(0);
            AtomicInteger invalidAmountCount = new AtomicInteger(0);
            AtomicInteger otherErrorCount = new AtomicInteger(0);
            ConcurrentLinkedQueue<BidResult> results = new ConcurrentLinkedQueue<>();
            ConcurrentLinkedQueue<String> unexpectedErrors = new ConcurrentLinkedQueue<>();

            // When: 100개 요청을 거의 동시에 시작
            for (int i = 0; i < CONCURRENT_REQUESTS_SINGLE_AUCTION; i++) {
                final int bidderIndex = i;
                final User bidder = bidders.get(i);
                // 각 유저가 다른 금액으로 입찰 (유효한 금액들)
                final int bidAmount = START_PRICE + BID_INCREMENT * (i + 1);

                executor.submit(() -> {
                    try {
                        readyLatch.countDown();
                        startLatch.await(); // 모든 스레드가 준비될 때까지 대기

                        BidCreateRequest request = new BidCreateRequest(
                                bidAmount, "req-" + bidderIndex + "-" + UUID.randomUUID()
                        );
                        BidCreateResponse response = bidService.createBid(
                                auction.getAuctionId(), request, bidder.getUserId()
                        );

                        successCount.incrementAndGet();
                        results.add(new BidResult(bidderIndex, bidAmount, true, null));

                    } catch (BusinessException e) {
                        ErrorCode code = e.getErrorCode();
                        results.add(new BidResult(bidderIndex, bidAmount, false, code));

                        if (code == ErrorCode.AUCTION_LOCK_BUSY) {
                            lockBusyCount.incrementAndGet();
                        } else if (code == ErrorCode.BID_INVALID_AMOUNT) {
                            invalidAmountCount.incrementAndGet();
                        } else {
                            String errorMsg = "BusinessException: " + code + " - " + e.getMessage();
                            System.err.println("Unexpected " + errorMsg);
                            unexpectedErrors.add(errorMsg);
                            otherErrorCount.incrementAndGet();
                        }
                    } catch (Exception e) {
                        String errorMsg = e.getClass().getName() + " - " + e.getMessage();
                        System.err.println("Unexpected Exception: " + errorMsg);
                        unexpectedErrors.add(errorMsg);
                        e.printStackTrace();
                        otherErrorCount.incrementAndGet();
                        results.add(new BidResult(bidderIndex, bidAmount, false, null));
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            // 모든 스레드가 준비되면 동시 시작
            readyLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            startLatch.countDown();
            doneLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            // Then: DB 상태 검증
            Auction updatedAuction = auctionRepository.findById(auction.getAuctionId()).orElseThrow();
            List<Bid> allBids = bidRepository.findAll().stream()
                    .filter(b -> b.getAuction().getAuctionId().equals(auction.getAuctionId()))
                    .toList();

            // 저장된 Bid 수와 성공 카운트 일치
            assertThat(allBids).hasSize(successCount.get());

            // bidCount 정합성
            assertThat(updatedAuction.getBidCount()).isEqualTo(successCount.get());

            // currentPrice 정합성: 최고가 Bid의 amount와 일치해야 함
            if (!allBids.isEmpty()) {
                int maxBidAmount = allBids.stream()
                        .mapToInt(Bid::getAmount)
                        .max()
                        .orElse(START_PRICE);
                assertThat(updatedAuction.getCurrentPrice()).isEqualTo(maxBidAmount);
            }

            // 모든 Bid는 유효한 금액이어야 함
            allBids.forEach(bid -> {
                assertThat(bid.getAmount()).isGreaterThanOrEqualTo(START_PRICE + BID_INCREMENT);
            });

            System.out.println("=== 최종 에러 카운트 ===");
            System.out.println("otherErrorCount: " + otherErrorCount.get());
            System.out.println("lockBusyCount: " + lockBusyCount.get());
            System.out.println("invalidAmountCount: " + invalidAmountCount.get());
            if (otherErrorCount.get() > 0) {
                System.out.println("=== Unexpected Errors ===");
                unexpectedErrors.forEach(err -> System.out.println("  - " + err));
            }
            assertThat(otherErrorCount.get())
                    .withFailMessage("Unexpected errors occurred: " + unexpectedErrors)
                    .isZero();

            long failedRequestCount = results.stream().filter(result -> !result.success()).count();
            assertThat(failedRequestCount).isEqualTo(lockBusyCount.get() + invalidAmountCount.get());

            // 결과 로그 출력
            System.out.println("=== 동시성 테스트 결과 ===");
            System.out.println("총 요청: " + CONCURRENT_REQUESTS_SINGLE_AUCTION);
            System.out.println("성공: " + successCount.get());
            System.out.println("락 경합 실패 (AUCTION_LOCK_BUSY): " + lockBusyCount.get());
            System.out.println("금액 검증 실패 (BID_INVALID_AMOUNT): " + invalidAmountCount.get());
            System.out.println("기타 에러: " + otherErrorCount.get());
            System.out.println("최종 currentPrice: " + updatedAuction.getCurrentPrice());
            System.out.println("저장된 Bid 수: " + allBids.size());
        }

        @Test
        @DisplayName("동일 clientRequestId 동시 요청 시 멱등성 보장")
        void givenSameRequestIdConcurrently_whenBid_thenOnlyOneBidCreated() throws InterruptedException {
            // Given
            User seller = createAndSaveUser();
            Auction auction = createAndSaveLiveAuction(seller);
            User bidder = createAndSaveUser();
            String sharedClientRequestId = "shared-req-" + UUID.randomUUID();
            int bidAmount = START_PRICE + BID_INCREMENT;
            int concurrentRequests = 10;

            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(concurrentRequests);
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger lockBusyCount = new AtomicInteger(0);
            AtomicInteger conflictCount = new AtomicInteger(0);
            AtomicInteger unexpectedErrorCount = new AtomicInteger(0);
            ConcurrentLinkedQueue<String> unexpectedErrors = new ConcurrentLinkedQueue<>();

            // When: 같은 유저가 같은 requestId로 동시에 여러 번 요청
            for (int i = 0; i < concurrentRequests; i++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        BidCreateRequest request = new BidCreateRequest(bidAmount, sharedClientRequestId);
                        bidService.createBid(auction.getAuctionId(), request, bidder.getUserId());
                        successCount.incrementAndGet();
                    } catch (BusinessException e) {
                        if (e.getErrorCode() == ErrorCode.BID_IDEMPOTENCY_CONFLICT) {
                            conflictCount.incrementAndGet();
                        } else if (e.getErrorCode() == ErrorCode.AUCTION_LOCK_BUSY) {
                            lockBusyCount.incrementAndGet();
                        } else {
                            String errorMsg = "BusinessException: " + e.getErrorCode() + " - " + e.getMessage();
                            System.err.println("Unexpected " + errorMsg);
                            unexpectedErrors.add(errorMsg);
                            unexpectedErrorCount.incrementAndGet();
                        }
                    } catch (Exception e) {
                        String errorMsg = e.getClass().getName() + " - " + e.getMessage();
                        System.err.println("Unexpected Exception: " + errorMsg);
                        unexpectedErrors.add(errorMsg);
                        e.printStackTrace();
                        unexpectedErrorCount.incrementAndGet();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            doneLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            // Then: Bid는 정확히 1개만 생성되어야 함
            List<Bid> bids = bidRepository.findAll().stream()
                    .filter(b -> b.getAuction().getAuctionId().equals(auction.getAuctionId()))
                    .filter(b -> b.getBidder().getUserId().equals(bidder.getUserId()))
                    .toList();

            assertThat(bids).hasSize(1);
            assertThat(bids.get(0).getAmount()).isEqualTo(bidAmount);
            assertThat(bids.get(0).getClientRequestId()).isEqualTo(sharedClientRequestId);
            if (unexpectedErrorCount.get() > 0) {
                System.out.println("=== Idempotency Test Unexpected Errors ===");
                unexpectedErrors.forEach(err -> System.out.println("  - " + err));
            }
            assertThat(unexpectedErrorCount.get())
                    .withFailMessage("Unexpected errors occurred: " + unexpectedErrors)
                    .isZero();
            assertThat(successCount.get() + conflictCount.get() + lockBusyCount.get()).isEqualTo(concurrentRequests);
        }

        @Test
        @DisplayName("선행 트랜잭션이 락을 보유 중이면 후행 입찰은 AUCTION_LOCK_BUSY로 빠르게 실패")
        void givenAuctionRowLocked_whenCreateBid_thenFailFastWithLockBusy() throws Exception {
            // Given
            User seller = createAndSaveUser();
            Auction auction = createAndSaveLiveAuction(seller);
            User bidder = createAndSaveUser();
            UUID auctionId = auction.getAuctionId();

            CountDownLatch lockAcquiredLatch = new CountDownLatch(1);
            CountDownLatch releaseLockLatch = new CountDownLatch(1);
            AtomicReference<Throwable> lockerError = new AtomicReference<>();
            TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

            Future<?> locker = executor.submit(() -> {
                transactionTemplate.executeWithoutResult(status -> {
                    try {
                        entityManager.find(Auction.class, auctionId, LockModeType.PESSIMISTIC_WRITE);
                        entityManager.flush();
                        lockAcquiredLatch.countDown();
                        releaseLockLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        lockerError.set(e);
                    } catch (Throwable e) {
                        lockerError.set(e);
                    }
                });
            });

            assertThat(lockAcquiredLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

            BidCreateRequest request = new BidCreateRequest(
                    START_PRICE + BID_INCREMENT,
                    "lock-busy-" + UUID.randomUUID()
            );

            long startedAt = System.nanoTime();

            try {
                // When & Then
                assertThatThrownBy(() -> bidService.createBid(auctionId, request, bidder.getUserId()))
                        .isInstanceOf(BusinessException.class)
                        .extracting(e -> ((BusinessException) e).getErrorCode())
                        .isEqualTo(ErrorCode.AUCTION_LOCK_BUSY);

                long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

                // application-test.yml의 lock-timeout-ms가 100ms이므로 넉넉하게 2초 안에 실패해야 한다.
                assertThat(elapsedMillis).isLessThan(2_000);
            } finally {
                releaseLockLatch.countDown();
                locker.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }

            assertThat(lockerError.get()).isNull();
        }
    }

    @Nested
    @DisplayName("다중 경매 동시 입찰")
    class MultipleAuctionsConcurrentBids {

        @Test
        @DisplayName("5개 경매에 각 20개 동시 요청 시 각 경매별 정합성 유지")
        void givenMultipleAuctions_whenConcurrentBids_thenEachAuctionMaintainsConsistency() throws InterruptedException {
            // Given: 경매 5개, 경매당 유저 20명
            User seller = createAndSaveUser();
            List<Auction> auctions = new ArrayList<>();
            Map<UUID, List<User>> auctionBidders = new HashMap<>();

            for (int a = 0; a < CONCURRENT_AUCTIONS_COUNT; a++) {
                Auction auction = createAndSaveLiveAuction(seller);
                auctions.add(auction);
                auctionBidders.put(auction.getAuctionId(), createAndSaveUsers(CONCURRENT_REQUESTS_PER_AUCTION));
            }

            int totalRequests = CONCURRENT_AUCTIONS_COUNT * CONCURRENT_REQUESTS_PER_AUCTION;
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(totalRequests);

            Map<UUID, AtomicInteger> successCounts = new ConcurrentHashMap<>();
            Map<UUID, AtomicInteger> expectedFailureCounts = new ConcurrentHashMap<>();
            AtomicInteger unexpectedErrorCount = new AtomicInteger(0);
            ConcurrentLinkedQueue<String> unexpectedErrors = new ConcurrentLinkedQueue<>();
            auctions.forEach(a -> {
                successCounts.put(a.getAuctionId(), new AtomicInteger(0));
                expectedFailureCounts.put(a.getAuctionId(), new AtomicInteger(0));
            });

            // When: 모든 경매에 동시 입찰
            for (Auction auction : auctions) {
                List<User> bidders = auctionBidders.get(auction.getAuctionId());

                for (int i = 0; i < CONCURRENT_REQUESTS_PER_AUCTION; i++) {
                    final User bidder = bidders.get(i);
                    final int bidAmount = START_PRICE + BID_INCREMENT * (i + 1);

                    executor.submit(() -> {
                        try {
                            startLatch.await();
                            BidCreateRequest request = new BidCreateRequest(
                                    bidAmount, "req-" + UUID.randomUUID()
                            );
                            bidService.createBid(auction.getAuctionId(), request, bidder.getUserId());
                            successCounts.get(auction.getAuctionId()).incrementAndGet();
                        } catch (BusinessException e) {
                            if (e.getErrorCode() == ErrorCode.AUCTION_LOCK_BUSY
                                    || e.getErrorCode() == ErrorCode.BID_INVALID_AMOUNT) {
                                expectedFailureCounts.get(auction.getAuctionId()).incrementAndGet();
                            } else {
                                String errorMsg = "BusinessException: " + e.getErrorCode() + " - " + e.getMessage();
                                System.err.println("Unexpected " + errorMsg + " in multi-auction test");
                                unexpectedErrors.add(errorMsg);
                                unexpectedErrorCount.incrementAndGet();
                            }
                        } catch (Exception e) {
                            String errorMsg = e.getClass().getName() + " - " + e.getMessage();
                            System.err.println("Unexpected Exception in multi-auction test: " + errorMsg);
                            unexpectedErrors.add(errorMsg);
                            e.printStackTrace();
                            unexpectedErrorCount.incrementAndGet();
                        } finally {
                            doneLatch.countDown();
                        }
                    });
                }
            }

            startLatch.countDown();
            doneLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            // Then: 각 경매별로 정합성 검증
            for (Auction auction : auctions) {
                Auction updated = auctionRepository.findById(auction.getAuctionId()).orElseThrow();
                List<Bid> bids = bidRepository.findAll().stream()
                        .filter(b -> b.getAuction().getAuctionId().equals(auction.getAuctionId()))
                        .toList();

                int expectedSuccess = successCounts.get(auction.getAuctionId()).get();

                // bidCount = 성공한 입찰 수
                assertThat(updated.getBidCount()).isEqualTo(expectedSuccess);

                // 저장된 Bid 수 = 성공 수
                assertThat(bids).hasSize(expectedSuccess);

                // currentPrice = 최고가
                if (!bids.isEmpty()) {
                    int maxAmount = bids.stream().mapToInt(Bid::getAmount).max().orElse(START_PRICE);
                    assertThat(updated.getCurrentPrice()).isEqualTo(maxAmount);
                }

                System.out.println("경매 " + auction.getAuctionId() + ": 성공 " + expectedSuccess +
                        ", 최종가 " + updated.getCurrentPrice());
            }

            // 전체 성공 수가 0이 아니어야 함
            int totalSuccess = successCounts.values().stream().mapToInt(AtomicInteger::get).sum();
            assertThat(totalSuccess).isGreaterThan(0);
            if (unexpectedErrorCount.get() > 0) {
                System.out.println("=== Multi-Auction Test Unexpected Errors ===");
                unexpectedErrors.forEach(err -> System.out.println("  - " + err));
            }
            assertThat(unexpectedErrorCount.get())
                    .withFailMessage("Unexpected errors occurred: " + unexpectedErrors)
                    .isZero();
        }
    }

    @Nested
    @DisplayName("자동 연장 동시성")
    class AutoExtensionConcurrency {

        @Test
        @DisplayName("종료 5분 이내 경매에 동시 입찰 시 연장 적용")
        void givenAuctionAboutToEnd_whenConcurrentBids_thenExtensionApplied() throws InterruptedException {
            // Given: 3분 후 종료되는 경매
            User seller = createAndSaveUser();
            AuctionItem item = createAndSaveAuctionItem();
            OffsetDateTime now = OffsetDateTime.now();

            Auction auction = Auction.create(
                    item, seller, "Ending Soon", "Test",
                    AuctionStatus.LIVE, START_PRICE, BID_INCREMENT, DURATION_HOURS,
                    now.minusHours(1), now.plusMinutes(3)
            );
            auction = auctionRepository.save(auction);
            final UUID auctionId = auction.getAuctionId();
            final OffsetDateTime originalEndAt = auction.getEndAt();

            List<User> bidders = createAndSaveUsers(5);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(5);
            AtomicInteger unexpectedErrorCount = new AtomicInteger(0);

            // When
            for (int i = 0; i < 5; i++) {
                final User bidder = bidders.get(i);
                final int amount = START_PRICE + BID_INCREMENT * (i + 1);

                executor.submit(() -> {
                    try {
                        startLatch.await();
                        BidCreateRequest request = new BidCreateRequest(amount, "req-" + UUID.randomUUID());
                        bidService.createBid(auctionId, request, bidder.getUserId());
                    } catch (BusinessException e) {
                        if (e.getErrorCode() != ErrorCode.AUCTION_LOCK_BUSY
                                && e.getErrorCode() != ErrorCode.BID_INVALID_AMOUNT) {
                            System.err.println("Unexpected BusinessException in auto-extension test: " + e.getErrorCode() + " - " + e.getMessage());
                            unexpectedErrorCount.incrementAndGet();
                        }
                    } catch (Exception e) {
                        System.err.println("Unexpected Exception in auto-extension test: " + e.getClass().getName() + " - " + e.getMessage());
                        e.printStackTrace();
                        unexpectedErrorCount.incrementAndGet();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            doneLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            // Then: 연장이 적용되어 endAt이 늘어나야 함
            Auction updated = auctionRepository.findById(auctionId).orElseThrow();

            // 입찰이 하나라도 성공했다면 연장되어야 함
            if (updated.getBidCount() > 0) {
                assertThat(updated.getEndAt()).isAfter(originalEndAt);
                assertThat(updated.getExtendCount()).isGreaterThan(0);
            }
            assertThat(unexpectedErrorCount.get()).isZero();
        }
    }

    private User createAndSaveUser() {
        User user = User.create(UserRole.USER);
        return userRepository.save(user);
    }

    private List<User> createAndSaveUsers(int count) {
        return IntStream.range(0, count)
                .mapToObj(i -> createAndSaveUser())
                .toList();
    }

    private AuctionItem createAndSaveAuctionItem() {
        Map<String, Object> specs = new HashMap<>();
        specs.put("brand", "Apple");
        AuctionItem item = AuctionItem.create("iPhone 15 Pro", ItemCategory.PHONE, ItemCondition.A, specs);
        return auctionItemRepository.save(item);
    }

    private Auction createAndSaveLiveAuction(User seller) {
        AuctionItem item = createAndSaveAuctionItem();
        OffsetDateTime now = OffsetDateTime.now();

        Auction auction = Auction.create(
                item, seller, "Test Auction " + UUID.randomUUID(),
                "Test Description", AuctionStatus.LIVE,
                START_PRICE, BID_INCREMENT, DURATION_HOURS,
                now, now.plusHours(DURATION_HOURS)
        );
        return auctionRepository.save(auction);
    }

    private record BidResult(int bidderIndex, int amount, boolean success, ErrorCode errorCode) {}
}
