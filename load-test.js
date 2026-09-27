import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';


const allowed = new Counter('allowed_requests');
const limited = new Counter('limited_requests');

export const options = {
  scenarios: {
    burst: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '5s', target: 100 },   // ramp up to 50 concurrent users
        { duration: '20s', target: 100 },  // hold steady, sustained pressure
        { duration: '5s', target: 0 },    // ramp down
      ],
    },
  },
};

export default function () {
  
  const res = http.get('http://host.docker.internal:8080/api/ping?userId=load-test-user');

  check(res, { 'status is 200 or 429': (r) => r.status === 200 || r.status === 429 });

  if (res.status === 200) {
    allowed.add(1);
  } else if (res.status === 429) {
    limited.add(1);
  }
}
