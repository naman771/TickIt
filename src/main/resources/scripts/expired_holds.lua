local clock = redis.call('TIME')
local now = clock[1] * 1000 + math.floor(clock[2] / 1000)
local due = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', now, 'LIMIT', 0, 200)
local expired = {}
for _, key in ipairs(due) do
    local ttl = redis.call('PTTL', key)
    if ttl == -2 then
        redis.call('ZREM', KEYS[1], key)
        table.insert(expired, key)
    elseif ttl >= 0 then
        redis.call('ZADD', KEYS[1], now + ttl, key)
    end
end
return expired
