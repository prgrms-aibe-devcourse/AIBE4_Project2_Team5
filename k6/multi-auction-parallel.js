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
  readAuctionIdsEnv,
  readNumberEnv,
  readOptionalEnv,
  readPositiveNumberEnv,
  readRequiredEnv,
  recordActualBidCountDelta,
  recordBidResult,
  randomSleepSeconds
} from './lib/bid-test-utils.js';

const BASE_URL = readOptionalEnv('BASE_URL', 'http://localhost:8080');
const TOKEN = readRequiredEnv('TOKEN');
const AUCTION_IDS = readAuctionIdsEnv('AUCTION_IDS');
const START_AMOUNT = readPositiveNumberEnv('START_AMOUNT', 11000);
const INCREMENT = readPositiveNumberEnv('INCREMENT', 1000);
const MAX_BID_AMOUNT = readPositiveNumberEnv('MAX_BID_AMOUNT', 9500000);
const THINK_TIME_MAX_MS = readNumberEnv('THINK_TIME_MAX_MS', 200);

http.setResponseCallback(expectedBidStatuses);

export const options = {
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  scenarios: {
    multi_auction_parallel: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: buildRampingStages(100),
      gracefulRampDown: readOptionalEnv('GRACEFUL_RAMP_DOWN', '5s')
    }
  },
  thresholds: {
    http_req_failed: ['rate<0.05'],
    http_req_duration: ['p(95)<1500', 'p(99)<4000'],
    bid_business_failure_rate: ['rate<0.95'],
    bid_accepted: ['count>0']
  },
  tags: {
    test_suite: 'auction-bid-load-test',
    auction_scope: 'multi'
  }
};

export function setup() {
  const auctions = AUCTION_IDS.map((auctionId, index) => {
    const setup = buildAuctionSetup(BASE_URL, auctionId, TOKEN, START_AMOUNT, INCREMENT);
    return {
      ...setup,
      auctionIndex: index
    };
  });

  return {
    auctions
  };
}

export default function (data) {
  // VU와 iteration을 함께 사용해서 특정 경매 하나에만 요청이 몰리지 않게 분산
  const auctionIndex = (__VU + __ITER - 1) % data.auctions.length;
  const auction = data.auctions[auctionIndex];

  const sequence = (__ITER * readPositiveNumberEnv('PEAK_VUS', 100)) + __VU;
  const amount = nextBidAmount(auction.baseAmount, INCREMENT, sequence, MAX_BID_AMOUNT);
  const requestId = `multi-${auction.auctionId}-${__VU}-${__ITER}-${Date.now()}`;

  const payload = JSON.stringify({
    amount,
    clientRequestId: requestId
  });

  const tags = {
    auction_scope: 'multi',
    auction_index: String(auction.auctionIndex),
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
    auctionScope: 'multi',
    auctionIndex: auction.auctionIndex,
    auctionId: auction.auctionId,
    requestId,
    amount
  });

  sleep(randomSleepSeconds(THINK_TIME_MAX_MS));
}

export function teardown(data) {
  data.auctions.forEach((auction) => {
    recordActualBidCountDelta(BASE_URL, auction, TOKEN, {
      auction_scope: 'multi',
      auction_index: String(auction.auctionIndex)
    });
  });
}
