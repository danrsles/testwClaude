// Scenario 2: the feed under load.
//
// Ramps from 10 to 300 users, each loading GET /wants about once a second,
// as someone keeping the app open would. It measures rather than tests: the
// point is where p95 starts to climb, and why. The only pass mark is that
// almost nothing fails.
//
//   k6 run loadtest/feed.js
//
// Each user is a different seeded person, so every request builds a
// different feed: their 10 wants and those of the 50 people they follow.

import http from 'k6/http'
import { check, sleep } from 'k6'
import { SharedArray } from 'k6/data'

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8081'

const tokens = new SharedArray('tokens', () => JSON.parse(open('./tokens.json')))

export const options = {
  scenarios: {
    feed: {
      executor: 'ramping-vus',
      startVUs: 10,
      stages: [
        { duration: '30s', target: 10 },
        { duration: '1m', target: 100 },
        { duration: '1m', target: 300 },
        { duration: '30s', target: 300 },
        { duration: '15s', target: 0 },
      ],
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
  },
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
}

export default function () {
  const token = tokens[(__VU - 1) % tokens.length]
  const res = http.get(`${BASE_URL}/wants`, { headers: { Authorization: `Bearer ${token}` } })
  check(res, { 'status is 200': (r) => r.status === 200 })
  sleep(1)
}
