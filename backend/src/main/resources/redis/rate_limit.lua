-- Fixed-window rate limiter: count requests per user per minute.
--
-- KEYS[1] = counter key for this user + this minute, e.g. "tixrush:ratelimit:buy:7:29561234"
-- ARGV[1] = window length in seconds (the key deletes itself after that)
-- Returns the number of requests so far in this window (including this one).

local count = redis.call('INCR', KEYS[1])
if count == 1 then
    -- First request of the window: make the key expire so old counters clean themselves up.
    redis.call('EXPIRE', KEYS[1], ARGV[1])
end
return count
