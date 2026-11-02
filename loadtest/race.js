// Scenario 1: the last-seat race.
//
// load_1 posts a want with 6 seats, so 5 are free besides the owner's, and
// 200 other users try to sign up for it at the same moment. Exactly 5 must
// get in and 195 be told it is full (409), and the want must end with 6
// seats taken. Anything else means the row lock in SignupService let two
// people into one seat.
//
//   k6 run loadtest/race.js

import http from 'k6/http'
import { check } from 'k6'
import { Counter } from 'k6/metrics'
import { SharedArray } from 'k6/data'

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8081'
const RACERS = 200
const CAPACITY = 6

// tokens[0] is load_1, the host; tokens[1..200] are the racers.
const tokens = new SharedArray('tokens', () => JSON.parse(open('./tokens.json')))

const won = new Counter('seats_won')
const full = new Counter('told_full')
const other = new Counter('other_answers')

export const options = {
  scenarios: {
    race: { executor: 'per-vu-iterations', vus: RACERS, iterations: 1, maxDuration: '1m' },
  },
  thresholds: {
    seats_won: [`count==${CAPACITY - 1}`],
    told_full: [`count==${RACERS - (CAPACITY - 1)}`],
    other_answers: ['count==0'],
    // The seat count read back in teardown.
    checks: ['rate==1'],
  },
}

const as = (token) => ({ headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' } })

export function setup() {
  const res = http.post(
    `${BASE_URL}/wants`,
    JSON.stringify({ message: `Race ${new Date().toISOString()}`, category: 'GAME', capacity: CAPACITY }),
    as(tokens[0]),
  )
  if (res.status !== 201) {
    throw new Error(`Could not post the race want: ${res.status} ${res.body}`)
  }
  return { wantId: res.json('id') }
}

export default function ({ wantId }) {
  // __VU counts from 1, so racer n is load_<n + 1>.
  const res = http.post(`${BASE_URL}/wants/${wantId}/signups`, null, as(tokens[__VU]))
  if (res.status === 200) won.add(1)
  else if (res.status === 409) full.add(1)
  else other.add(1)
}

export function teardown({ wantId }) {
  const feed = http.get(`${BASE_URL}/wants`, as(tokens[0])).json()
  const want = feed.find((w) => w.id === wantId)
  check(want, { [`the want has ${CAPACITY} seats taken`]: (w) => w && w.seatsTaken === CAPACITY })
}
