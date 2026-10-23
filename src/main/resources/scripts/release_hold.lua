if ARGV[2] and redis.call('GET',KEYS[4]) ~= ARGV[2] then return 0 end
if ARGV[1] ~= '*' and redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
local removed = redis.call('DEL', KEYS[1])
if KEYS[4] then redis.call('DEL',KEYS[4]) end
redis.call('ZREM', KEYS[2], KEYS[1])
if KEYS[3] then redis.call('ZREM', KEYS[3], KEYS[1]) end
return removed
