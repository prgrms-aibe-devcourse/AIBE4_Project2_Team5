package kr.eolmago.controller.api.search;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import kr.eolmago.dto.api.search.response.AutocompleteResponse;
import kr.eolmago.dto.api.search.response.PopularKeywordResponse;
import kr.eolmago.service.search.SearchKeywordService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/search")
@Tag(name = "Search", description = "검색 부가 기능 API")
@RequiredArgsConstructor
public class SearchController {

    private final SearchKeywordService searchKeywordService;

    @Operation(summary = "자동 완성")
    @GetMapping("/autocomplete")
    public ResponseEntity<List<AutocompleteResponse>> autocomplete(
            @RequestParam("q") String query
    ) {
        // 빈 문자열 체크
        if (query == null || query.trim().isEmpty()) {
            return ResponseEntity.ok(List.of());
        }

        // 너무 짧은 검색어 필터링 (성능 최적화)
        if (query.trim().length() < 1) {
            return ResponseEntity.ok(List.of());
        }

        // 자동완성 Service 호출(초성 자동 판단)
        List<AutocompleteResponse> results = searchKeywordService.getAutoComplete(query.trim());

        return ResponseEntity.ok(results);
    }

    @Operation(summary = "검색어 인기순")
    @GetMapping("/popular")
    public ResponseEntity<List<PopularKeywordResponse>> getPopularKeywords() {
        List<PopularKeywordResponse> results = searchKeywordService.getPopularKeywords();
        return ResponseEntity.ok(results);
    }

    @Operation(summary = "검색어 통계 기록")
    @PostMapping("/record")
    public ResponseEntity<Map<String, String>> recordSearch(
            @RequestParam String keyword,
            @RequestParam UUID userId
    ) {
        // 검색어 통계 기록
        searchKeywordService.recordSearch(keyword, userId);

        Map<String, String> response = new HashMap<>();
        response.put("message", "검색어 통계 기록 완료");
        response.put("keyword", keyword);

        return ResponseEntity.ok(response);
    }
}
