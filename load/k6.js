import http from 'k6/http';
import { check } from 'k6';

const baseUrl = __ENV.BASE_URL || 'http://127.0.0.1:8080';
const profileId = __ENV.PROFILE_ID;

export const options = {
  scenarios: {
    projected_reads: {
      executor: 'constant-arrival-rate',
      rate: 250,
      timeUnit: '1s',
      duration: __ENV.DURATION || '2m',
      preAllocatedVUs: 64,
      maxVUs: 256,
    },
  },
  thresholds: {
    http_req_duration: ['p(95)<100'],
    http_req_failed: ['rate<0.001'],
  },
};

export default function () {
  const path = profileId && (__ITER % 2 === 0)
    ? `/api/v1/players/${encodeURIComponent(profileId)}`
    : '/api/v1/leaderboards/players/level?limit=50';
  const response = http.get(`${baseUrl}${path}`);
  check(response, { 'HTTP 200': (result) => result.status === 200 });
}
