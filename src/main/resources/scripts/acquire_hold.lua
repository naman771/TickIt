local clock = redis.call('TIME')
local now = clock[1] * 1000 + math.floor(clock[2] / 1000)
local owner = redis.call('GET', KEYS[1])
if owner and owner ~= ARGV[1] then return 0 end
if KEYS[3] then
  redis.call('ZREMRANGEBYSCORE', KEYS[3], '-inf', now)
  if not owner and redis.call('ZCARD', KEYS[3]) >= tonumber(ARGV[3] or '3') then return 0 end
end
if not owner then redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2]) end
local ttl = redis.call('PTTL', KEYS[1])
redis.call('ZADD', KEYS[2], now + ttl, KEYS[1])
if KEYS[3] then
  redis.call('ZADD', KEYS[3], now + ttl, KEYS[1])
  redis.call('PEXPIRE', KEYS[3], tonumber(ARGV[2]) * 1000)
end
return 1
