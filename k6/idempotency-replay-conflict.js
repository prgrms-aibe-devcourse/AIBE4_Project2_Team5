import http from 'k6/http';
import { sleep } from 'k6';
import {
  authHeaders,
  buildAuctionSetup,
  checkBidResponse,
  classifyBidResponse,
  expectedBidStatuses,
  logUnexpectedResponse,
  nextBidAmount,
  readOptionalEnv,
  readPositiveNumberEnv,
  readRequiredEnv,
  recordActualBidCountDelta,
  recordBidResult
} from './lib/bid-test-utils.js';

const BASE_URL = readOptionalEnv('BASE_URL', 'http://localhost:8080');
const AUCTION_ID = readRequiredEnv('AUCTION_ID');
const TOKEN = readRequiredEnv('TOKEN');
const START_AMOUNT = readPositiveNumberEnv('START_AMOUNT', 11000);
const INCREMENT = readPositiveNumberEnv('INCREMENT', 1000);
const MAX_BID_AMOUNT = readPositiveNumberEnv('MAX_BID_AMOUNT', 9500000);

http.setResponseCallback(expectedBidStatuses);

export const options = {
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  scenarios: {
    idempotency_probe: {
      executor: 'per-vu-iterations',
      vus: readPositiveNumberEnv('VUS', 1),
      iterations: readPositiveNumberEnv('ITERATIONS_PER_VU', 1),
      maxDuration: readOptionalEnv('MAX_DURATION', '1m')
    }
  },
  thresholds: {
    http_req_failed: ['rate<0.05'],
    bid_idempotency_replay: ['count>0'],
    bid_idempotency_conflict: ['count>0']
  },
  tags: {
    test_suite: 'auction-bid-load-test',
    auction_scope: 'single'
  }
};

export function setup() {
  return {
    auction: buildAuctionSetup(BASE_URL, AUCTION_ID, TOKEN, START_AMOUNT, INCREMENT)
  };
}

export default function (data) {
  const auction = data.auction;

  // 멱등성 동작 검증
  const sequence = (__ITER * readPositiveNumberEnv('VUS', 1)) + __VU;
  const amount = nextBidAmount(auction.baseAmount, INCREMENT, sequence, MAX_BID_AMOUNT);
  const conflictingAmount = amount + INCREMENT <= MAX_BID_AMOUNT ? amount + INCREMENT : amount - INCREMENT;
  const requestId = `idem-${auction.auctionId}-${__VU}-${__ITER}-${Date.now()}`;

  const firstResponse = sendBid(auction.auctionId, amount, requestId, 'idempotency_first_request', 'accepted');
  const firstClassification = classifyBidResponse(firstResponse, 'accepted');

  if (firstClassification.outcome !== 'accepted' && firstClassification.outcome !== 'accepted_extended') {
    return;
  }

  sleep(0.1);

  sendBid(auction.auctionId, amount, requestId, 'idempotency_replay_probe', 'idempotency_replay');

  sleep(0.1);

  sendBid(auction.auctionId, conflictingAmount, requestId, 'idempotency_conflict_probe', 'accepted');
}

function sendBid(auctionId, amount, requestId, operation, successOutcome) {
  const payload = JSON.stringify({
    amount,
    clientRequestId: requestId
  });

  const tags = {
    auction_scope: 'single',
    operation
  };

  const response = http.post(`${BASE_URL}/api/auctions/${auctionId}/bids`, payload, {
    headers: authHeaders(TOKEN),
    tags,
    responseCallback: expectedBidStatuses,
    timeout: readOptionalEnv('REQUEST_TIMEOUT', '10s')
  });

  const classification = classifyBidResponse(response, successOutcome);
  recordBidResult(response, classification, tags);
  checkBidResponse(response);
  logUnexpectedResponse(response, classification, {
    auctionScope: 'single',
    auctionId,
    requestId,
    amount,
    operation
  });

  return response;
}

export function teardown(data) {
  recordActualBidCountDelta(BASE_URL, data.auction, TOKEN, {
    auction_scope: 'single',
    operation: 'idempotency_summary'
  });
}
