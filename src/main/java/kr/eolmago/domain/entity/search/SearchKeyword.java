package kr.eolmago.domain.entity.search;

import jakarta.persistence.*;
import kr.eolmago.domain.entity.common.CreatedAtEntity;
import kr.eolmago.domain.entity.search.enums.KeywordType;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Entity
@Table(
        name = "search_keywords",
        indexes = {
                @Index(name = "idx_search_keywords_keyword", columnList = "keyword", unique = true),
                @Index(name = "idx_search_count", columnList = "search_count DESC"),
                @Index(name = "idx_keyword_type", columnList = "keyword_type, search_count DESC")
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SearchKeyword extends CreatedAtEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String keyword;

    @Column(name = "search_count", nullable = false)
    private Integer searchCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "keyword_type", length = 20, nullable = false)
    private KeywordType keywordType;

    @Column(name = "last_searched_at", nullable = false)
    private OffsetDateTime lastSearchedAt;

    // 초성 컬럼 (Generated Column)
    @Column(name = "keyword_chosung", insertable = false, updatable = false)
    private String keywordChosung;  // 읽기 전용

    public static SearchKeyword create(String keyword) {
        SearchKeyword searchKeyword = new SearchKeyword();
        searchKeyword.keyword = keyword;
        searchKeyword.searchCount = 1;
        searchKeyword.keywordType = determineKeywordType(keyword);
        searchKeyword.lastSearchedAt = OffsetDateTime.now();
        return searchKeyword;
    }

    // 검색 횟수 증가
    public void incrementSearchCount() {
        this.searchCount++;
        this.lastSearchedAt = OffsetDateTime.now();
    }

    // 키워드 타입 자동 결정
    public static KeywordType determineKeywordType(String keyword) {
        String lowerKeyword = keyword.toLowerCase();

        // 브랜드 키워드 (명세서에서 브랜드 가중치 +100 적용)
        if (lowerKeyword.matches(".*(아이폰|갤럭시|엘지|픽셀|샤오미|소니|모토로라|apple|samsung|lg|google|xiaomi|sony|motorola).*")) {
            return KeywordType.BRAND;
        }

        // 모델명 키워드 (숫자 포함)
        if (lowerKeyword.matches(".*\\d+.*")) {
            return KeywordType.MODEL;
        }

        return KeywordType.GENERAL;
    }

}
