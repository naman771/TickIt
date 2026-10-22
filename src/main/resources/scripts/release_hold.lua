if ARGV[1] ~= '*' and redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
local removed = redis.call('DEL', KEYS[1])
redis.call('ZREM', KEYS[2], KEYS[1])
return removed
