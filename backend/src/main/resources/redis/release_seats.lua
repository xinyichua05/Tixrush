-- Give seats back (unpaid order expired, or queueing the order failed): the stock goes up, the
-- person may buy again, and each seat is free again, but ONLY if it is still held by THIS
-- reservation (never free a seat that someone else has since taken).
--
-- KEYS[1] = stock key, KEYS[2] = person's count, KEYS[3] = taken-seats hash
-- ARGV[1] = how many, ARGV[2] = holder (reservation id), ARGV[3..] = seat ids
if redis.call('EXISTS', KEYS[1]) == 1 then
    redis.call('INCRBY', KEYS[1], ARGV[1])
end
if redis.call('EXISTS', KEYS[2]) == 1 then
    if redis.call('DECRBY', KEYS[2], ARGV[1]) <= 0 then
        redis.call('DEL', KEYS[2])
    end
end
for i = 3, #ARGV do
    if redis.call('HGET', KEYS[3], ARGV[i]) == ARGV[2] then
        redis.call('HDEL', KEYS[3], ARGV[i])
    end
end
return 1
