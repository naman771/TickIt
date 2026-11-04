# HTTP rate limiting

TickIt uses the existing shared Redis service and atomic Lua token buckets. The read, refill, allow/reject decision and token consumption happen in one Redis execution. All application instances connected to that Redis share the same bucket state. This follows the token-bucket, shared-state and atomic-operation approach in [the Hello Interview reference](https://www.hellointerview.com/learn/system-design/problem-breakdowns/distributed-rate-limiter), adapted to the current free deployment.

## Policy and coverage

The default bucket holds **20 requests** and refills at **5 requests/second**. Configure `app.rate-limit.capacity` / `app.rate-limit.refill-rate` (or `APP_RATE_LIMIT_CAPACITY` / `APP_RATE_LIMIT_REFILL_RATE`). Both values must be positive; invalid configuration prevents startup.

Every mapped `/api/**` handler is covered, including public discovery/configuration, accounts, booking/holds, checkout orders, private tickets, scanning, organizer management and developer operations. Newly added API handlers inherit coverage. Limits are shared across those paths and HTTP methods, rather than resetting for each URL. The IP bucket is checked first. Requests with a verified authenticated principal also consume a subject bucket, so changing IP does not reset that account's budget. When the subject bucket rejects, the earlier IP debit is retained conservatively; there is no atomic multi-bucket refund.

The exact `/api/payments/webhook` callback is excluded from customer quotas to avoid dropping settlement notifications. Signature validation and idempotency remain mandatory there. CORS preflights, health probes, static assets and HTML pages do not consume these API buckets. WebSocket frames retain their separate per-session guard; this change does not claim distributed WebSocket connection limiting.

Spring Security validates credentials and rejects unauthorized requests before MVC interception. Those early 401/403 responses and requests rejected before MVC are not counted by this limiter. This is application API protection, not an ingress authentication-throttling or DDoS service.

## Responses and outages

Responses passing the limiter carry `X-RateLimit-Limit`, `X-RateLimit-Remaining` and `X-RateLimit-Reset`. With both buckets applicable, remaining is the lower quota and reset is the later full-refill time. Reset is a Unix timestamp for a token bucket becoming full, not a fixed-window boundary.

Exhaustion returns **429**, a generic message, `Cache-Control: no-store` and a computed integer `Retry-After` in seconds until at least one token can become available (minimum one second). Concurrent requests can consume that token before a retry. All four headers are exposed to the allowed organizer origin through CORS.

Unavailable Redis or a missing script result fails closed with **503**, rather than allowing unverified traffic or reporting an exhausted quota. Redis owns time; application-server clock differences do not create extra tokens. A backwards Redis-clock adjustment preserves the later timestamp and delays refill. Idle keys expire after at least 120 seconds or their full-refill time, whichever is longer, so a slow-refill policy cannot regain a free burst from early expiry. Legacy diagnostics retain their scalar script response and zero-refill disposable probe.

## Client IP trust and free-host limits

The limiter uses the servlet's resolved `remoteAddr`. It never reads arbitrary `X-Forwarded-For` or `Forwarded` values. Before claiming client-specific IP quotas behind a reverse proxy, verify the actual ingress chain and configure the container to trust only that ingress. Without this verified configuration, clients behind the same proxy/NAT can share an IP bucket. Do not enable blanket forwarding-header trust to make a test pass. The subject bucket remains independent of the resolved IP.

The existing Render/Redis free resource remains a single dependency, without a new gateway, Redis cluster or paid failover. Losing Redis contents resets bucket state. Free-host sleep and provider limits still apply. No multi-region, latency, high-availability or public-launch capacity guarantee is claimed.

## Verification

`RateLimiterServiceTest` exercises the real Redis script, including refill, expiry, backwards time and 64 concurrent requests through two service instances sharing one 20-token budget. `RateLimiterBoundaryTest` covers subject/IP enforcement and forged forwarding headers. `RateLimiterHttpTest` checks current and future mapped API paths, actionable 429 headers, exclusions and sanitized 503 responses. General business-flow tests use a larger fixture budget in Redis database 15; dedicated limiter tests retain the real 20-token default.
