-- First use of a numbered ticket type: copy its stock AND its taken seats from the database
-- into Redis, in one step. Only if the stock key is missing, so it can never load twice
-- (buyers can't take seats until the stock key exists, so nothing is lost in between).
--
-- KEYS[1] = stock key, KEYS[2] = taken-seats hash
-- ARGV[1] = tickets left, then pairs: ARGV[2] = seatId, ARGV[3] = reservationId, ...
-- Returns 1 = loaded, 0 = was already loaded.
if redis.call('EXISTS', KEYS[1]) == 1 then
    return 0
end
redis.call('DEL', KEYS[2])
for i = 2, #ARGV, 2 do
    redis.call('HSET', KEYS[2], ARGV[i], ARGV[i + 1])
end
redis.call('SET', KEYS[1], ARGV[1])
return 1
