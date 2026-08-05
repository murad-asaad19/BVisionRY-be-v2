/**
 * Load test for the anonymous public-assessment funnel — the marketing path.
 *
 * WHY THIS ONE PATH. It is the only flow a stranger can drive end to end with no
 * account, it is what a QR code on a conference banner points at, and it is the
 * single place where one campaign can put thousands of first-time visitors onto
 * the platform inside an hour. Everything else in the product is behind a login
 * and therefore behind a known, bounded population.
 *
 * ── READ THIS BEFORE TRUSTING A NUMBER FROM IT ───────────────────────────────
 *
 * Session-create is capped at FIVE REQUESTS PER MINUTE PER CLIENT IP
 * (`bvisionry.rate-limit.public-assessment.requests-per-minute`, default 5).
 * A load generator is one client IP. So the naive version of this script — point
 * k6 at the URL, turn up the VUs — measures the rate limiter and nothing else,
 * reports a wall of 429s, and tells you precisely nothing about whether the
 * application can serve a cohort. That is the trap this file exists to avoid,
 * and it is why the funnel scenario below sends a distinct simulated client IP
 * per virtual user.
 *
 * It does that the way the real edge does it, not by spoofing: `ClientIpResolver`
 * trusts `X-Bvisionry-Client-Ip` only when `X-Bvisionry-Proxy-Secret` matches the
 * backend's configured shared secret. In production the Vercel BFF is what sends
 * that pair. Here the script impersonates the BFF, which means the traffic shape
 * under test is the real one — public traffic reaches this API through the BFF,
 * never browser-direct.
 *
 * WITHOUT the secret the script still runs, and it will tell you it is measuring
 * the limiter rather than quietly pretending otherwise.
 *
 * ── Running it ───────────────────────────────────────────────────────────────
 *
 *   k6 run \
 *     -e BASE_URL=http://localhost:8181 \
 *     -e LINK_TOKEN=<a published public-assessment link token> \
 *     -e PROXY_SECRET=$BVISIONRY_PROXY_SHARED_SECRET \
 *     backend/tools/loadtest/public-assessment.js
 *
 * k6 is a single static binary and is deliberately NOT a dependency of either
 * repo: nothing in the build, the test suite or CI imports it, so it cannot rot
 * a lockfile. See README.md in this directory.
 *
 * NEVER point this at dev (:5432/:8080) or at production. Use a sandbox lane.
 */

import http from "k6/http";
import { check, fail, group, sleep } from "k6";
import { Counter, Rate, Trend } from "k6/metrics";

const BASE_URL = (__ENV.BASE_URL || "http://localhost:8181").replace(/\/$/, "");
const LINK_TOKEN = __ENV.LINK_TOKEN;
const PROXY_SECRET = __ENV.PROXY_SECRET || "";
const FUNNEL_VUS = Number(__ENV.VUS || 20);
const FUNNEL_DURATION = __ENV.DURATION || "1m";

/** 429s are a PASS for the limiter scenario and a FAIL for the funnel — counted apart. */
const throttled = new Counter("throttled_responses");
const funnelCompleted = new Counter("funnel_completed");
const limiterHeld = new Rate("limiter_held");
const submitDuration = new Trend("submit_duration", true);

export const options = {
  scenarios: {
    // What a cohort launch looks like: many distinct people, each doing the
    // funnel once, none of them sharing an IP.
    funnel: {
      executor: "ramping-vus",
      exec: "funnel",
      startVUs: 1,
      stages: [
        { duration: "15s", target: FUNNEL_VUS },
        { duration: FUNNEL_DURATION, target: FUNNEL_VUS },
        { duration: "10s", target: 0 },
      ],
      gracefulRampDown: "10s",
    },
    // What an attacker or a broken retry loop looks like: one IP, no let-up.
    // Runs alongside the funnel on purpose — the property worth proving is that
    // throttling one abuser does not degrade everyone else.
    limiter: {
      executor: "constant-arrival-rate",
      exec: "limiter",
      rate: 5,
      timeUnit: "1s",
      duration: FUNNEL_DURATION,
      preAllocatedVUs: 5,
      startTime: "15s",
    },
  },
  thresholds: {
    // The funnel must stay fast and clean. These are the numbers that decide
    // whether a result is a pass, so they are deliberately explicit rather than
    // left to whoever reads the summary.
    "http_req_duration{scenario:funnel}": ["p(95)<1500"],
    "http_req_failed{scenario:funnel}": ["rate<0.01"],
    submit_duration: ["p(95)<3000"],
    // The limiter must actually refuse the abuser. A run where this drops is a
    // run where the rate limit silently stopped working.
    limiter_held: ["rate>0.5"],
  },
};

export function setup() {
  if (!LINK_TOKEN) {
    fail(
      "LINK_TOKEN is required — create a public assessment link in the admin " +
        "console and pass its token: -e LINK_TOKEN=<uuid>",
    );
  }
  if (!PROXY_SECRET) {
    console.warn(
      "⚠ No PROXY_SECRET given, so every virtual user shares one client IP and " +
        "session-create will be throttled at 5/min. The funnel numbers from this " +
        "run measure the RATE LIMITER, not the application. Pass " +
        "-e PROXY_SECRET=$BVISIONRY_PROXY_SHARED_SECRET for a real result.",
    );
  }
  const landing = http.get(`${BASE_URL}/api/public/assessments/by-token/${LINK_TOKEN}`);
  if (landing.status !== 200) {
    fail(
      `The link token does not resolve (HTTP ${landing.status} from ` +
        `${BASE_URL}). Check BASE_URL and that the link is published.`,
    );
  }
  return { ok: true };
}

/** Headers that make the backend attribute this request to `ip`, exactly as the BFF does. */
function asClient(ip) {
  const headers = { "Content-Type": "application/json" };
  if (PROXY_SECRET) {
    headers["X-Bvisionry-Client-Ip"] = ip;
    headers["X-Bvisionry-Proxy-Secret"] = PROXY_SECRET;
  }
  return { headers };
}

/**
 * One simulated respondent's whole journey. A distinct IP per VU: 10.x.y.z is
 * private space, so a value that ever escaped into a real bucket key would be
 * obviously synthetic rather than colliding with a real user's.
 */
export function funnel() {
  const ip = `10.${(__VU >> 8) & 0xff}.${__VU & 0xff}.${(__ITER % 254) + 1}`;
  const client = asClient(ip);

  group("landing", () => {
    const res = http.get(`${BASE_URL}/api/public/assessments/by-token/${LINK_TOKEN}`, client);
    check(res, { "landing 200": (r) => r.status === 200 });
  });

  let accessToken;
  group("start session", () => {
    const res = http.post(
      `${BASE_URL}/api/public/assessments/by-token/${LINK_TOKEN}/sessions`,
      JSON.stringify({
        respondentName: `Load Test VU${__VU}`,
        respondentEmail: `loadtest+${__VU}-${__ITER}@example.invalid`,
      }),
      client,
    );
    if (res.status === 429) {
      throttled.add(1);
      return;
    }
    check(res, { "session created": (r) => r.status === 201 });
    accessToken = res.json("accessToken");
  });

  if (!accessToken) {
    // Throttled or refused — end the iteration rather than reporting phantom
    // failures on every subsequent step for a session that never existed.
    return;
  }

  let questionIds = [];
  group("load questions", () => {
    const res = http.get(`${BASE_URL}/api/public/assessments/sessions/${accessToken}`, client);
    check(res, { "session detail 200": (r) => r.status === 200 });
    if (res.status !== 200) return;
    for (const pillar of res.json("pillars") || []) {
      for (const question of pillar.questions || []) {
        questionIds.push(question.questionId);
      }
    }
  });

  // Autosave: real takers save in batches as they move through pillars, and this
  // endpoint has its own generous 60/min bucket precisely so that behaviour is
  // not mistaken for abuse. Three batches approximates a real pass.
  group("answer", () => {
    const batchSize = Math.ceil(questionIds.length / 3) || 1;
    for (let i = 0; i < questionIds.length; i += batchSize) {
      const answers = questionIds.slice(i, i + batchSize).map((questionId) => ({
        questionId,
        responseText:
          "Load test answer. Long enough to be representative of a real free-text " +
          "response rather than a single token, because payload size is part of " +
          "what is being measured here.",
      }));
      const res = http.post(
        `${BASE_URL}/api/public/assessments/sessions/${accessToken}/answers/batch`,
        JSON.stringify({ answers }),
        client,
      );
      check(res, { "answers saved": (r) => r.status === 200 || r.status === 429 });
      if (res.status === 429) throttled.add(1);
      sleep(0.5);
    }
  });

  group("submit", () => {
    const res = http.post(
      `${BASE_URL}/api/public/assessments/sessions/${accessToken}/submit`,
      null,
      client,
    );
    submitDuration.add(res.timings.duration);
    if (res.status === 429) {
      throttled.add(1);
      return;
    }
    // Submit dispatches the AI evaluation ASYNCHRONOUSLY — it must return
    // immediately and let the taker poll /status. A submit that blocks on the
    // model is the failure this scenario is shaped to catch, which is why
    // submit_duration has its own threshold.
    if (check(res, { "submitted": (r) => r.status === 200 })) {
      funnelCompleted.add(1);
    }
  });

  sleep(1);
}

/**
 * One IP, flat out, against the tightest bucket in the flow. Asserts the two
 * things that matter: the limiter refuses, and it refuses with a 429 rather than
 * by falling over. A 5xx here means the abuse path costs us an error instead of
 * a cheap rejection.
 */
export function limiter() {
  const client = asClient("203.0.113.7"); // TEST-NET-3, reserved for documentation
  const res = http.post(
    `${BASE_URL}/api/public/assessments/by-token/${LINK_TOKEN}/sessions`,
    JSON.stringify({
      respondentName: "Abusive client",
      respondentEmail: `abuse+${__ITER}@example.invalid`,
    }),
    client,
  );
  limiterHeld.add(res.status === 429);
  check(res, {
    "limiter answers 429 or 201, never 5xx": (r) => r.status === 429 || r.status === 201,
  });
}
