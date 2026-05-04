import http from 'k6/http';
import { sleep } from 'k6';
import {
  authHeaders,
  buildAuctionSetup,
  buildRampingStages,
  checkBidResponse,
  classifyBidResponse,
  expectedBidStatuses,
  logUnexpectedResponse,
  nextBidAmount,
  readNumberEnv,
  readOptionalEnv,
  readPositiveNumberEnv,
  readRequiredEnv,
  recordActualBidCountDelta,
  recordBidResult,
  randomSleepSeconds
} from './lib/bid-test-utils.js';

const BASE_URL = readOptionalEnv('BASE_URL', 'http://localhost:8080');
const AUCTION_ID = readRequiredEnv('AUCTION_ID');
const TOKEN = readRequiredEnv('TOKEN');
const START_AMOUNT = readPositiveNumberEnv('START_AMOUNT', 11000);
const INCREMENT = readPositiveNumberEnv('INCREMENT', 1000);
const MAX_BID_AMOUNT = readPositiveNumberEnv('MAX_BID_AMOUNT', 9500000);
const THINK_TIME_MAX_MS = readNumberEnv('THINK_TIME_MAX_MS', 200);

http.setResponseCallback(expectedBidStatuses);

export const options = {
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  scenarios: {
    bid_stress: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: buildRampingStages(100),
      gracefulRampDown: readOptionalEnv('GRACEFUL_RAMP_DOWN', '5s')
    }
  },
  thresholds: {
    http_req_failed: ['rate<0.05'],
    http_req_duration: ['p(95)<2000', 'p(99)<5000'],
    bid_business_failure_rate: ['rate<0.98'],
    bid_accepted: ['count>0']
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
  const sequence = (__ITER * readPositiveNumberEnv('PEAK_VUS', 100)) + __VU;
  const amount = nextBidAmount(auction.baseAmount, INCREMENT, sequence, MAX_BID_AMOUNT);
  const requestId = `stress-${auction.auctionId}-${__VU}-${__ITER}-${Date.now()}`;

  const payload = JSON.stringify({
    amount,
    clientRequestId: requestId
  });

  const tags = {
    auction_scope: 'single',
    operation: 'normal_bid'
  };

  const response = http.post(`${BASE_URL}/api/auctions/${auction.auctionId}/bids`, payload, {
    headers: authHeaders(TOKEN),
    tags,
    responseCallback: expectedBidStatuses,
    timeout: readOptionalEnv('REQUEST_TIMEOUT', '10s')
  });

  const classification = classifyBidResponse(response, 'accepted');
  recordBidResult(response, classification, tags);
  checkBidResponse(response);
  logUnexpectedResponse(response, classification, {
    auctionScope: 'single',
    auctionId: auction.auctionId,
    requestId,
    amount
  });

  sleep(randomSleepSeconds(THINK_TIME_MAX_MS));
}

export function teardown(data) {
  recordActualBidCountDelta(BASE_URL, data.auction, TOKEN, {
    auction_scope: 'single'
  });
}
