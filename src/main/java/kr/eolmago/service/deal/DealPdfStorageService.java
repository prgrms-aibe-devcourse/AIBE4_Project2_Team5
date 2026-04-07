package kr.eolmago.service.deal;

import kr.eolmago.global.config.SupabaseConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnBean(SupabaseConfig.class)
public class DealPdfStorageService {

    private final SupabaseConfig supabaseConfig;

    /**
     * PDF 파일을 Supabase에 업로드
     */
    public String uploadPdfToSupabase(byte[] pdfBytes, Long dealId, String fileName) {
        log.debug("PDF 업로드 시작: dealId={}, fileName={}, size={}", dealId, fileName, pdfBytes.length);

        try {
            // 파일 경로: deal_pdfs/{dealId}/deal-confirmation-{dealId}.pdf
            String filePath = generateFilePath(dealId, fileName);
            String uploadUrl = supabaseConfig.getUrl() + "/storage/v1/object/" +
                    supabaseConfig.getBucket() + "/" + filePath;

            RestTemplate restTemplate = new RestTemplate();
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + supabaseConfig.getServiceRoleKey());
            headers.setContentType(MediaType.APPLICATION_PDF);
            headers.set("x-upsert", "true"); // 덮어쓰기 옵션

            HttpEntity<byte[]> entity = new HttpEntity<>(pdfBytes, headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    uploadUrl,
                    HttpMethod.POST,
                    entity,
                    String.class
            );

            if (response.getStatusCode().is2xxSuccessful()) {
                String pdfUrl = supabaseConfig.getUrl() + "/storage/v1/object/public/" +
                        supabaseConfig.getBucket() + "/" + filePath;

                log.info("Supabase PDF 업로드 성공: dealId={}, fileName={}, url={}", dealId, fileName, pdfUrl);
                return pdfUrl;
            } else {
                log.error("Supabase PDF 업로드 실패: statusCode={}, body={}",
                        response.getStatusCode(), response.getBody());
                throw new RuntimeException("Supabase PDF 업로드 실패: " + response.getStatusCode());
            }
        } catch (Exception e) {
            log.error("PDF 업로드 중 오류 발생: dealId={}, fileName={}", dealId, fileName, e);
            throw new RuntimeException("PDF 업로드 중 오류 발생", e);
        }
    }

    /**
     * 파일 경로 생성
     */
    private String generateFilePath(Long dealId, String fileName) {
        return "deal_documents/" + dealId + "/" + fileName;
    }

    /**
     * Supabase에서 PDF 삭제
     */
    public void deletePdfFromSupabase(Long dealId, String fileName) {
        log.debug("PDF 삭제 시작: dealId={}, fileName={}", dealId, fileName);

        try {
            String filePath = generateFilePath(dealId, fileName);
            String deleteUrl = supabaseConfig.getUrl() + "/storage/v1/object/" +
                    supabaseConfig.getBucket() + "/" + filePath;

            RestTemplate restTemplate = new RestTemplate();
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + supabaseConfig.getServiceRoleKey());

            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    deleteUrl,
                    HttpMethod.DELETE,
                    entity,
                    String.class
            );

            if (response.getStatusCode().is2xxSuccessful()) {
                log.info("Supabase PDF 삭제 성공: dealId={}, fileName={}", dealId, fileName);
            } else {
                log.warn("Supabase PDF 삭제 실패: statusCode={}, dealId={}, fileName={}",
                        response.getStatusCode(), dealId, fileName);
            }
        } catch (Exception e) {
            log.error("PDF 삭제 중 오류 발생: dealId={}, fileName={}", dealId, fileName, e);
            // 삭제 실패는 예외를 던지지 않음 (이미 삭제되었거나 없을 수 있음)
        }
    }
}
