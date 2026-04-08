package kr.eolmago.service.auction.constants;

public final class AuctionConstants {
    // common
    public static final int MAX_PAGE_SIZE = 50;

    // 경매
    public static final int EXTENSION_THRESHOLD_SECONDS = 300; // 연장 발동 임계값(5분)
    public static final int EXTENSION_DURATION_SECONDS = 300;  // 연장 시간(5분)
    public static final int MAX_REMAINING_SECONDS = 1800; // 남은시간 상한(30분)
    public static final int HARD_MAX_EXTENSION_HOURS = 12; // 최대 연장 시간(12시간)

    // 입찰
    public static final int MAX_BID_AMOUNT = 10_000_000; // 입찰 금액 상한

    // ==== 검색 ====
    // Trigram 유사도 임계값
    public static final double TRIGRAM_THRESHOLD_SHORT = 0.5;   // 짧은 키워드 (엄격)
    public static final double TRIGRAM_THRESHOLD_MEDIUM = 0.3;  // 중간 길이
    public static final double TRIGRAM_THRESHOLD_LONG = 0.25;   // 긴 키워드 (관대)

    // 키워드 길이 기준
    public static final int KEYWORD_LENGTH_SHORT = 2;   // 짧은 키워드 기준
    public static final int KEYWORD_LENGTH_MEDIUM = 4;  // 중간 길이 키워드 기준

}
