import http from 'k6/http';
import { check } from 'k6';
import { Counter, Gauge, Rate, Trend } from 'k6/metrics';

export const expectedBidStatuses = http.expectedStatuses({ min: 200, max: 299 }, 400, 408, 409);

export const bidMetrics = {
  attempts: new Counter('bid_attempts'),
  accepted: new Counter('bid_accepted'),
  lockBusy: new Counter('bid_lock_busy'),
  idempotencyReplay: new Counter('bid_idempotency_replay'),
  idempotencyConflict: new Counter('bid_idempotency_conflict'),
  validationFailure: new Counter('bid_validation_failure'),
  unexpected: new Counter('bid_unexpected'),
  outcome: new Counter('bid_outcome'),
  businessFailureRate: new Rate('bid_business_failure_rate'),
  apiDuration: new Trend('bid_api_duration', true),
  actualBidCountDelta: new Gauge('actual_bid_count_delta')
};

export function readRequiredEnv(name) {
  const value = __ENV[name];
  if (!value || String(value).trim() === '') {
    throw new Error(`${name} 환경변수가 필요합니다.`);
  }
  return String(value).trim();
}

export function readOptionalEnv(name, defaultValue) {
  const value = __ENV[name];
  if (value === undefined || value === null || String(value).trim() === '') {
    return defaultValue;
  }
  return String(value).trim();
}

export function readNumberEnv(name, defaultValue) {
  const value = __ENV[name];
  if (value === undefined || value === null || String(value).trim() === '') {
    return defaultValue;
  }

  const parsed = Number(value);
  if (!Number.isFinite(parsed)) {
    throw new Error(`${name} 값은 숫자여야 합니다. 현재 값: ${value}`);
  }
  return parsed;
}

export function readPositiveNumberEnv(name, defaultValue) {
  const value = readNumberEnv(name, defaultValue);
  if (value <= 0) {
    throw new Error(`${name} 값은 0보다 커야 합니다. 현재 값: ${value}`);
  }
  return value;
}

export function readAuctionIdsEnv(name) {
  const raw = readRequiredEnv(name);
  const ids = raw.split(',').map((value) => value.trim()).filter(Boolean);

  if (ids.length === 0) {
    throw new Error(`${name}에는 최소 1개 이상의 경매 ID가 필요합니다.`);
  }
  return ids;
}

export function buildRampingStages(defaultPeakVus) {
  const warmupVus = readNumberEnv('WARMUP_VUS', Math.max(1, Math.floor(defaultPeakVus / 5)));
  const midVus = readNumberEnv('MID_VUS', Math.max(warmupVus, Math.floor(defaultPeakVus / 2)));
  const peakVus = readNumberEnv('PEAK_VUS', defaultPeakVus);

  return [
    {
      duration: readOptionalEnv('WARMUP_DURATION', '10s'),
      target: warmupVus
    },
    {
      duration: readOptionalEnv('RAMPUP_DURATION', '20s'),
      target: midVus
    },
    {
      duration: readOptionalEnv('MID_STEADY_DURATION', '30s'),
      target: midVus
    },
    {
      duration: readOptionalEnv('PEAK_RAMPUP_DURATION', '20s'),
      target: peakVus
    },
    {
      duration: readOptionalEnv('STEADY_DURATION', '30s'),
      target: peakVus
    },
    {
      duration: readOptionalEnv('COOLDOWN_DURATION', '10s'),
      target: 0
    }
  ];
}

export function authHeaders(token) {
  return {
    'Content-Type': 'application/json',
    Authorization: `Bearer ${token}`
  };
}

export function safeJson(response) {
  if (!response || !response.body) {
    return null;
  }

  try {
    return JSON.parse(response.body);
  } catch (error) {
    return null;
  }
}

export function extractBusinessCode(responseBody) {
  if (!responseBody || typeof responseBody !== 'object') {
    return null;
  }

  return responseBody.code || responseBody.errorCode || responseBody.statusCode || null;
}

export function extractMessage(responseBody) {
  if (!responseBody || typeof responseBody !== 'object') {
    return '';
  }

  return responseBody.message || responseBody.error || responseBody.reason || '';
}

export function isSuccessStatus(status) {
  return status >= 200 && status < 300;
}

export function mapBusinessOutcome(status, code, successOutcome) {
  if (isSuccessStatus(status)) {
    return successOutcome || 'accepted';
  }

  switch (code) {
    case 'B002':
    case 'B005':
      return 'invalid_amount';
    case 'B006':
      return 'invalid_increment';
    case 'B007':
      return 'missing_request_id';
    case 'B008':
      return 'idempotency_conflict';
    case 'B009':
      return 'amount_exceeds_limit';
    case 'B010':
      return 'lock_busy';
    case 'B011':
      return 'queue_timeout';
    case 'A001':
      return 'auction_not_found';
    case 'A004':
    case 'A005':
      return 'auction_not_live';
    case 'A008':
      return 'seller_cannot_bid';
    case 'C001':
      return 'validation_fail';
    case 'C999':
      return 'internal_error';
    default:
      return mapStatusFallback(status);
  }
}

function mapStatusFallback(status) {
  if (status === 400) {
    return 'validation_fail';
  }
  if (status === 408) {
    return 'queue_timeout';
  }
  if (status === 409) {
    return 'business_conflict';
  }
  if (status === 401 || status === 403) {
    return 'auth_error';
  }
  if (status === 404) {
    return 'not_found';
  }
  if (status >= 500) {
    return 'server_error';
  }
  return `http_${status}`;
}

export function classifyBidResponse(response, successOutcome) {
  const body = safeJson(response);
  const code = extractBusinessCode(body);
  const resolvedSuccessOutcome = resolveSuccessOutcome(body, successOutcome);
  const outcome = mapBusinessOutcome(response.status, code, resolvedSuccessOutcome);

  return {
    body,
    code,
    message: extractMessage(body),
    outcome
  };
}

function resolveSuccessOutcome(body, successOutcome) {
  if (successOutcome) {
    return successOutcome;
  }

  if (body && body.extensionApplied === true) {
    return 'accepted_extended';
  }

  return 'accepted';
}

export function isBusinessFailure(outcome) {
  return !['accepted', 'accepted_extended', 'idempotency_replay'].includes(outcome);
}

export function recordBidResult(response, classification, tags) {
  const outcome = classification.outcome;
  const mergedTags = {
    ...tags,
    outcome
  };

  bidMetrics.attempts.add(1, tags);
  bidMetrics.outcome.add(1, mergedTags);
  bidMetrics.apiDuration.add(response.timings.duration, tags);
  bidMetrics.businessFailureRate.add(isBusinessFailure(outcome), mergedTags);

  if (outcome === 'accepted' || outcome === 'accepted_extended') {
    bidMetrics.accepted.add(1, mergedTags);
    return;
  }

  if (outcome === 'lock_busy') {
    bidMetrics.lockBusy.add(1, mergedTags);
    return;
  }

  if (outcome === 'idempotency_replay') {
    bidMetrics.idempotencyReplay.add(1, mergedTags);
    return;
  }

  if (outcome === 'idempotency_conflict') {
    bidMetrics.idempotencyConflict.add(1, mergedTags);
    return;
  }

  if (isValidationOutcome(outcome)) {
    bidMetrics.validationFailure.add(1, mergedTags);
    return;
  }

  bidMetrics.unexpected.add(1, mergedTags);
}

function isValidationOutcome(outcome) {
  return [
    'invalid_amount',
    'invalid_increment',
    'amount_exceeds_limit',
    'auction_not_live',
    'seller_cannot_bid',
    'missing_request_id',
    'validation_fail'
  ].includes(outcome);
}

export function checkBidResponse(response, allowedStatuses) {
  const statuses = allowedStatuses || [200, 201, 400, 408, 409];

  return check(response, {
    '입찰 API 응답 상태가 예상 범위 안에 있음': (res) => statuses.includes(res.status)
  });
}

export function logUnexpectedResponse(response, classification, requestContext) {
  if (!isBusinessFailure(classification.outcome)) {
    return;
  }

  if (['invalid_amount', 'invalid_increment', 'lock_busy', 'idempotency_conflict', 'idempotency_replay'].includes(classification.outcome)) {
    return;
  }

  console.log(JSON.stringify({
    event: 'k6_bid_unexpected_response',
    status: response.status,
    code: classification.code,
    outcome: classification.outcome,
    message: classification.message,
    requestContext
  }));
}

export function getAuctionDetail(baseUrl, auctionId, token) {
  const response = http.get(`${baseUrl}/api/auctions/${auctionId}`, {
    headers: authHeaders(token),
    responseCallback: expectedBidStatuses,
    timeout: readOptionalEnv('SETUP_TIMEOUT', '10s')
  });

  if (response.status !== 200) {
    throw new Error(`경매 상세 조회 실패: auctionId=${auctionId}, status=${response.status}, body=${response.body}`);
  }

  return safeJson(response) || {};
}

export function getBidHistorySummary(baseUrl, auctionId, token) {
  const response = http.get(`${baseUrl}/api/auctions/${auctionId}/bids?page=0&size=1`, {
    headers: authHeaders(token),
    responseCallback: expectedBidStatuses,
    timeout: readOptionalEnv('SETUP_TIMEOUT', '10s')
  });

  if (response.status !== 200) {
    console.log(`입찰 이력 조회 실패: auctionId=${auctionId}, status=${response.status}, body=${response.body}`);
    return {
      bidCount: null,
      totalElements: null
    };
  }

  const body = safeJson(response) || {};
  return {
    bidCount: readNullableNumber(body.bidCount),
    totalElements: readNullableNumber(body.totalElements)
  };
}

export function readNullableNumber(value) {
  if (value === undefined || value === null || value === '') {
    return null;
  }

  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : null;
}

export function resolveAuctionBaseAmount(auction, fallbackStartAmount, fallbackIncrement) {
  const currentPrice = readNullableNumber(auction.currentPrice) || readNullableNumber(auction.currentHighestAmount) || 0;
  const startPrice = readNullableNumber(auction.startPrice) || 0;
  const bidIncrement = readNullableNumber(auction.bidIncrement) || fallbackIncrement;
  const currentBase = Math.max(currentPrice, startPrice);
  const nextAcceptableAmount = currentBase + bidIncrement;

  return Math.max(fallbackStartAmount, nextAcceptableAmount);
}

export function buildAuctionSetup(baseUrl, auctionId, token, fallbackStartAmount, fallbackIncrement) {
  const auction = getAuctionDetail(baseUrl, auctionId, token);
  const history = getBidHistorySummary(baseUrl, auctionId, token);
  const baseAmount = resolveAuctionBaseAmount(auction, fallbackStartAmount, fallbackIncrement);

  console.log(JSON.stringify({
    event: 'k6_auction_setup',
    auctionId,
    status: auction.status || 'unknown',
    currentPrice: auction.currentPrice,
    bidIncrement: auction.bidIncrement,
    initialBidCount: history.bidCount,
    baseAmount
  }));

  return {
    auctionId,
    baseAmount,
    bidIncrement: readNullableNumber(auction.bidIncrement) || fallbackIncrement,
    initialBidCount: history.bidCount
  };
}

export function recordActualBidCountDelta(baseUrl, auctionSetup, token, tags) {
  const history = getBidHistorySummary(baseUrl, auctionSetup.auctionId, token);

  if (history.bidCount === null || auctionSetup.initialBidCount === null) {
    console.log(JSON.stringify({
      event: 'k6_actual_bid_count_delta_skipped',
      auctionId: auctionSetup.auctionId,
      reason: 'bidCount 조회 결과가 null입니다.'
    }));
    return;
  }

  const delta = history.bidCount - auctionSetup.initialBidCount;
  bidMetrics.actualBidCountDelta.add(delta, tags || {});

  console.log(JSON.stringify({
    event: 'k6_actual_bid_count_delta',
    auctionId: auctionSetup.auctionId,
    initialBidCount: auctionSetup.initialBidCount,
    finalBidCount: history.bidCount,
    delta
  }));
}

export function nextBidAmount(baseAmount, increment, sequence, maxBidAmount) {
  const amount = baseAmount + sequence * increment;
  return Math.min(amount, maxBidAmount);
}

export function randomSleepSeconds(maxMs) {
  if (!maxMs || maxMs <= 0) {
    return 0;
  }
  return Math.random() * maxMs / 1000;
}
