-- Atomically take one ticket from the stock counter.
--
-- Redis runs a Lua script as ONE uninterruptible step: no other command can run in
-- between the GET and the DECR. That's what makes "check, then take" safe here,
-- exactly the step the naive version got wrong.
--
-- KEYS[1] = stock key, e.g. "tixrush:stock:event:42"
-- Returns:  1 = got a ticket,  0 = sold out,  -1 = stock not loaded into Redis yet

local stock = redis.call('GET', KEYS[1])
if not stock then
    return -1
end
if tonumber(stock) <= 0 then
    return 0
end
redis.call('DECR', KEYS[1])
return 1
