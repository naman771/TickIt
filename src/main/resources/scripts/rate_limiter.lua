-- rate_limiter.lua
-- Token-bucket rate limiter (atomic via Redis Lua)
--
-- KEYS[1] = rate-limit key (e.g. "rl:user:42" or "rl:ip:1.2.3.4")
-- ARGV[1] = capacity  (max tokens)
-- ARGV[2] = refill rate (tokens per second)
-- ARGV[3] = now  (Unix epoch millis)
--
-- Returns: 1 if allowed, 0 if rejected

local key       = KEYS[1]
local capacity  = tonumber(ARGV[1])
local refill    = tonumber(ARGV[2])
local now       = tonumber(ARGV[3])

local data = redis.call('HMGET', key, 'tokens', 'last')
local tokens = tonumber(data[1])
local last   = tonumber(data[2])

if tokens == nil then
    -- First request: initialise bucket
    tokens = capacity
    last   = now
end

-- Refill tokens based on elapsed time
local elapsed = math.max(0, now - last) / 1000.0   -- seconds
local refilled = math.min(capacity, tokens + elapsed * refill)

if refilled < 1 then
    return 0  -- rejected
end

-- Consume one token
refilled = refilled - 1
redis.call('HMSET', key, 'tokens', tostring(refilled), 'last', tostring(now))
redis.call('EXPIRE', key, 120)  -- auto-cleanup after 2 min inactivity
return 1  -- allowed
