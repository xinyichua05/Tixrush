-- Give N tickets of a ticket type back (unpaid order expired, or queueing the order failed),
-- and lower the person's count so they may buy again.
--
-- Like release_ticket.lua: only add to the stock if the counter is loaded. If it's missing, the
-- next purchase reloads it from the database, which already includes the returned tickets.
--
-- KEYS[1] = stock of this ticket type, KEYS[2] = tickets this person holds for the event
-- ARGV[1] = how many tickets
if redis.call('EXISTS', KEYS[1]) == 1 then
    redis.call('INCRBY', KEYS[1], ARGV[1])
end
if redis.call('EXISTS', KEYS[2]) == 1 then
    if redis.call('DECRBY', KEYS[2], ARGV[1]) <= 0 then
        redis.call('DEL', KEYS[2])
    end
end
return 1
