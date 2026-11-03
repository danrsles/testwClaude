// Scenario 3: one user flooding the API, while everyone else uses it normally.
//
// "flood" is a single person, load_1, sending GET /wants from 50 connections
// with no pause, as a script or a stuck retry loop would. "normal" is 100
// other people each loading the feed about once a second, as in feed.js.
//
// Run it twice, with the rate limit off and on, and compare the normal users'
// latency and how many of the flood's requests reached the database:
//
//   mvn spring-boot:test-run -Dspring-boot.run.arguments=--app.rate-limit.enabled=false
//   k6 run loadtest/ratelimit.js
//   mvn spring-boot:test-run
//   k6 run loadtest/ratelimit.js
//
// The only pass mark is that no normal user is ever refused: the limit is per
// person, so one person's flood must not spend anyone else's requests.

import http from 'k6/http'
import { check, sleep } from 'k6'
import { SharedArray } from 'k6/data'

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8081'

const tokens = new SharedArray('tokens', () => JSON.parse(open('./tokens.json')))

export const options = {
  scenarios: {
    normal: {
      executor: 'ramping-vus',
      exec: 'normal',
      startVUs: 0,
      stages: [
        { duration: '5s', target: 100 },
        { duration: '1m', target: 100 },
      ],
    },
    flood: {
      executor: 'ramping-vus',
      exec: 'flood',
      startVUs: 0,
      // Starts once the normal users are steady, so the first 10 s are a baseline.
      startTime: '15s',
      stages: [
        { duration: '5s', target: 50 },
        { duration: '45s', target: 50 },
      ],
    },
  },
  thresholds: {
    // A refused normal user would mean one person's limit spilled onto others.
    'checks{scenario:normal}': ['rate==1'],
    // Listed so the summary breaks them out; these only measure.
    'http_req_duration{scenario:normal}': ['p(95)>=0'],
    'http_reqs{scenario:normal}': ['count>=0'],
    'http_reqs{scenario:flood,status:200}': ['count>=0'],
    'http_reqs{scenario:flood,status:429}': ['count>=0'],
  },
  summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
}

const as = (token) => ({ headers: { Authorization: `Bearer ${token}` } })

// tokens[0] is load_1, the flooder; the normal users are everyone after.
export function normal() {
  const token = tokens[1 + ((__VU - 1) % (tokens.length - 1))]
  const res = http.get(`${BASE_URL}/wants`, as(token))
  check(res, { 'status is 200': (r) => r.status === 200 })
  sleep(1)
}

export function flood() {
  const res = http.get(`${BASE_URL}/wants`, as(tokens[0]))
  check(res, { 'served or refused': (r) => r.status === 200 || r.status === 429 })
}
