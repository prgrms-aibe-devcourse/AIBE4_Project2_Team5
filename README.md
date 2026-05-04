# 얼마고 : 중고 물품 경매 플랫폼
**얼마고**는 원하는 중고 상품을 빠르고 정확한 검색으로 찾고, 실시간 경매로 합리적인 가격에 낙찰받을 수 있는 플랫폼입니다.<br>
판매자 정보와 거래 이력을 확인할 수 있고, 신고·제재 기반 운영으로 더 안전한 거래 환경을 제공합니다.<br>
경매와 거래 진행 상황을 알림으로 실시간 추적하고, 중요한 업데이트를 즉시 확인할 수 있습니다.<br>
낙찰 후에는 1:1 채팅방에서 판매자와 바로 소통하고, 거래를 안전하게 마무리할 수 있습니다.

<br>

## 기술 스택

### Backend
- Java 17, Spring Boot 3.5.9
- Spring Data JPA, QueryDSL
- Spring Security (OAuth2)
- PostgreSQL 17
- Redis
- WebSocket (STOMP)
- Swagger UI
- JUnit 5
- Prometheus, Grafana, Loki, Alloy, k6

### Frontend
- Thymeleaf
- Tailwind CSS

### Infra
- AWS EC2, RDS, ElastiCache
- Docker
- Supabase Storage

<br>

## 주요 기능

### 가입 · 인증
- Google / Kakao / Naver 소셜 로그인
- SMS 전화번호 인증
- JWT 기반 인증 및 Role 권한 관리 (Spring Security, OAuth2)

### 경매
- 경매 임시저장 · 수정 · 삭제 · 게시 · 취소
- 내 경매 대시보드: 상태별 현황 및 성과
- 실시간 입찰: 최고가 실시간 갱신, 마감 5분 전 자동 연장(30분 캡)
- 입찰 동시성 제어: DB 비관적 락(SELECT ... FOR UPDATE), lock_timeout, clientRequestId 멱등성 기반 중복 방지
- 동일 경매 동시 입찰 시 짧은 임계구역만 보호하고, 후행 요청은 장시간 대기하지 않도록 빠른 실패 처리
- 입찰 동시성 검증: JUnit 5 기반 통합 테스트로 최고가·입찰 수·중복/누락 여부 검증
- 입찰 부하 테스트: k6 기반 단일 경매 집중 / 다중 경매 분산 시나리오 검증
- 관측 및 분석: Prometheus · Grafana · Loki · Alloy 기반으로 lock wait/hold time, 응답 시간, 에러율, 실패 유형 시각화
- 경매 자동 마감: 스케줄링 기반 유찰/낙찰 처리, 낙찰 시 거래 자동 생성
- 유찰 시 재등록 기능 제공
- 권한별 입찰 내역 조회
- 판매자 정보(신뢰도) 확인

### 검색 · 자동완성
- 초성/일반 텍스트/오타 보정 검색
- 인기 키워드 자동완성
- Redis 기반 검색어 캐싱 및 인기 검색어 집계/정렬
- 카테고리·브랜드·가격·상태 다차원 필터링

### 거래 관리 · 확정서
- 거래 생성 및 상태 관리, 판매/구매 내역 분리 조회
- 판매자·구매자 상호 확정 후 거래 완료 처리
- 거래 확정서 PDF 생성 및 다운로드

### 알림 · 채팅
- 경매 · 입찰 · 낙찰 · 거래 · 신고 · 찜 실시간 알림 (SSE)
- 시스템 알림 타임라인: 내역 누적 조회 · 일괄 읽음 처리
- 거래 전용 1:1 실시간 채팅 (WebSocket · STOMP)
- Redis Streams 기반 메시지 처리/저장 파이프라인
- afterCommit 기반 후처리 분리로 알림 실패가 경매/입찰 트랜잭션에 영향을 주지 않도록 설계

### 신고 · 관리자
- 경매 게시물 및 사용자 신고 접수/조회
- 관리자 대시보드: 사용자 목록/상태 관리, 신고 검토·처리, 제재 관리

### 마이페이지
- 프로필 조회/수정 및 프로필 이미지 변경
- 찜한 경매 목록 조회
- 거래 통계(거래 횟수, 평균 별점 등) 조회

### 리뷰
- 거래 완료 후 리뷰 작성 · 수정 · 삭제
- 작성한 리뷰 목록 조회
- 받은 리뷰 목록 조회

<br>

## ERD

<img width="3720" height="2192" alt="얼마고_ERD" src="https://github.com/user-attachments/assets/86893a21-8c23-48ff-b079-c131bea26d5e" />

[ERDCloud 바로가기](https://www.erdcloud.com/d/SHAtwxTSaqmk47nFJ)


<br>

## 소프트웨어 아키텍처

<img width="3021" height="1049" alt="eolmago 인프라" src="https://github.com/user-attachments/assets/62bb322a-7861-414f-a006-3a1f4eac785f" />

<br>

## 프로젝트 구조

```
kr.eolmago
├── controller
│   ├── api          # REST API 컨트롤러
│   └── view         # 뷰 렌더링 컨트롤러
├── dto
│   ├── api
│   │   ├── request  # API 요청 DTO
│   │   └── response # API 응답 DTO
│   └── view         # 뷰 전용 DTO
├── domain
│   └── entity       # JPA 엔티티
├── repository       # Spring Data JPA Repository
├── service          # 비즈니스 로직
└── global
    ├── config       # 설정 클래스
    ├── exception    # 예외 처리
    ├── handler      # 이벤트 핸들러
    └── util         # 유틸리티
```

<br>

## 트러블슈팅

프로젝트 진행 중 발생한 이슈와 해결 과정은 아래 링크에서 확인할 수 있습니다.

[트러블슈팅 문서 (Notion)](https://www.notion.so/2f089178cb0a801dbab4ff503532978a)

<br>

## 팀 협업 컨벤션

- [커밋 컨벤션](docs/COMMIT_CONVENTION.md)
- 이슈/PR 컨벤션
  - GitHub 템플릿 사용(.github/ISSUE_TEMPLATE, PULL_REQUEST_TEMPLATE.md)

<br>

## 팀원
| <img src="https://github.com/so-myoung.png" width="100" height="100" /> | <img src="https://github.com/jk-Nam.png" width="100" height="100" /> | <img src="https://github.com/jihun4452.png" width="100" height="100" /> | <img src="https://github.com/yerincho94.png" width="100" height="100" /> | <img src="https://github.com/c-wonjun.png" width="100" height="100" /> |
| :---: | :---: | :---: | :---: | :---: |
| 김소명 | 남준구 | 박지훈 | 조예린 | 최원준 |
| [so-myoung](https://github.com/so-myoung) | [jk-Nam](https://github.com/jk-Nam) | [jihun4452](https://github.com/jihun4452) | [yerincho94](https://github.com/yerincho94) | [c-wonjun](https://github.com/c-wonjun) |
