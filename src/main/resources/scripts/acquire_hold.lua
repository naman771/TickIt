local owner = redis.call('GET', KEYS[1])
if owner and owner ~= ARGV[1] then return 0 end
if not owner then redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2]) end
local clock = redis.call('TIME')
local now = clock[1] * 1000 + math.floor(clock[2] / 1000)
local ttl = redis.call('PTTL', KEYS[1])
redis.call('ZADD', KEYS[2], now + ttl, KEYS[1])
return 1
