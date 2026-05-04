package kr.eolmago.global.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "auction.runtime")
public class AuctionRuntimeProperties {

    private final Bid bid = new Bid();
    private final Close close = new Close();
    private final Scheduler scheduler = new Scheduler();

    public Bid getBid() {
        return bid;
    }

    public Close getClose() {
        return close;
    }

    public Scheduler getScheduler() {
        return scheduler;
    }

    // 입찰
    public static class Bid {
        private int lockTimeoutMs = 150; // 동일 경매 row 락을 기다리는 최대 시간(ms)
        private int transactionTimeoutSec = 3; // 입찰 쓰기 트랜잭션 전체 제한 시간(초)

        public int getLockTimeoutMs() {
            return lockTimeoutMs;
        }

        public void setLockTimeoutMs(int lockTimeoutMs) {
            this.lockTimeoutMs = lockTimeoutMs;
        }

        public int getTransactionTimeoutSec() {
            return transactionTimeoutSec;
        }

        public void setTransactionTimeoutSec(int transactionTimeoutSec) {
            this.transactionTimeoutSec = transactionTimeoutSec;
        }
    }

    // 마감
    public static class Close {
        private int lockTimeoutMs = 200; // 마감 작업 락 대기 상한
        private int transactionTimeoutSec = 5; // 마감 트랜잭션 전체 제한

        public int getLockTimeoutMs() {
            return lockTimeoutMs;
        }

        public void setLockTimeoutMs(int lockTimeoutMs) {
            this.lockTimeoutMs = lockTimeoutMs;
        }

        public int getTransactionTimeoutSec() {
            return transactionTimeoutSec;
        }

        public void setTransactionTimeoutSec(int transactionTimeoutSec) {
            this.transactionTimeoutSec = transactionTimeoutSec;
        }
    }

    // 스케줄러
    public static class Scheduler {
        private int poolSize = 10; // 마감 작업 실행 스레드 수
        private String threadNamePrefix = "auction-close-";
        private int awaitTerminationSec = 30;
        private long sweepDelayMs = 60000; // 안전망 스캔 주기(ms)
        private int sweepBatchSize = 100; // 한 번 스캔에서 가져올 경매 수

        public int getPoolSize() {
            return poolSize;
        }

        public void setPoolSize(int poolSize) {
            this.poolSize = poolSize;
        }

        public String getThreadNamePrefix() {
            return threadNamePrefix;
        }

        public void setThreadNamePrefix(String threadNamePrefix) {
            this.threadNamePrefix = threadNamePrefix;
        }

        public int getAwaitTerminationSec() {
            return awaitTerminationSec;
        }

        public void setAwaitTerminationSec(int awaitTerminationSec) {
            this.awaitTerminationSec = awaitTerminationSec;
        }

        public long getSweepDelayMs() {
            return sweepDelayMs;
        }

        public void setSweepDelayMs(long sweepDelayMs) {
            this.sweepDelayMs = sweepDelayMs;
        }

        public int getSweepBatchSize() {
            return sweepBatchSize;
        }

        public void setSweepBatchSize(int sweepBatchSize) {
            this.sweepBatchSize = sweepBatchSize;
        }
    }
}
