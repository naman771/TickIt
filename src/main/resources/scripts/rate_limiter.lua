-- Atomic shared token bucket. KEYS[1]: bucket; ARGV: capacity, tokens/sec, optional "details".
-- Legacy result: 1/0. Details: allowed, remaining, retry-ms, full-refill Unix seconds.
local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local refill = tonumber(ARGV[2])
local clock = redis.call('TIME')
local now = clock[1] * 1000 + math.floor(clock[2] / 1000)
local data = redis.call('HMGET', key, 'tokens', 'last')
local tokens = tonumber(data[1])
local last = tonumber(data[2])
if tokens == nil then
    tokens = capacity
    last = now
end
local elapsed = math.max(0, now - last) / 1000.0
local refilled = math.min(capacity, tokens + elapsed * refill)
local allowed = 0
if refilled >= 1 then
    refilled = refilled - 1
    allowed = 1
end
-- Preserve future timestamps during backwards clock adjustments.
local updated = math.max(now, last)
local skew = updated - now
local full = 120000
local retry = 0
if refill > 0 then
    full = skew + math.ceil((capacity - refilled) / refill * 1000)
    if allowed == 0 then
        retry = skew + math.ceil((1 - refilled) / refill * 1000)
    end
end
-- Expiration must never grant a free burst before normal refill would fill the bucket.
redis.call('HSET', key, 'tokens', tostring(refilled), 'last', tostring(updated))
redis.call('PEXPIRE', key, math.max(120000, full))
if ARGV[3] == 'details' then
    return {allowed, math.floor(refilled), retry, math.ceil((now + full) / 1000)}
end
return allowed
