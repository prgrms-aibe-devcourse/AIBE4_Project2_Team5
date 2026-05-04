package kr.eolmago.service.auction.monitoring;

public final class BidMetricNames {
    public static final String BID_REQUEST_METRIC = "auction.bid.request.total";
    public static final String BID_INFLIGHT_METRIC = "auction.bid.request.inflight";
    public static final String BID_OUTCOME_METRIC = "auction.bid.outcome.total";
    public static final String BID_DURATION_METRIC = "auction.bid.duration";
    public static final String BID_IDEMPOTENCY_LOOKUP_METRIC = "auction.bid.idempotency.lookup.total";
    public static final String BID_LOCK_WAIT_METRIC = "auction.bid.lock.wait";
    public static final String BID_LOCK_HOLD_METRIC = "auction.bid.lock.hold";
    public static final String BID_TRANSACTION_DURATION_METRIC = "auction.bid.transaction.duration";
}
