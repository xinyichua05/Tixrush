-- Atomically take N tickets of ONE ticket type, respecting the per-person limit for the event.
--
-- Redis runs a Lua script as ONE uninterruptible step, so "check stock + check limit + take"
-- can't be split by another buyer: no overselling, and no way past the limit with parallel clicks.
-- All or nothing: if 3 are asked and only 2 are left, nothing is taken.
--
-- KEYS[1] = stock of this ticket type,          e.g. "tixrush:stock:event:42:type:7"
-- KEYS[2] = tickets this person already holds,  e.g. "tixrush:bought:event:42:user:5"
-- ARGV[1] = how many tickets (1-4)
-- ARGV[2] = per-person limit for this event (4)
-- ARGV[3] = lifetime of the per-person counter, in seconds
--
-- Returns { code, number }:
--   { 1, left }  got them; "left" = tickets of this type still left
--   { 0, left }  not enough left of this type (left may be 0 = sold out)
--   {-1, 0 }     stock not loaded into Redis yet (the caller loads it and tries again)
--   {-2, have}   this would go over the per-person limit; "have" = what they already hold

local stock = redis.call('GET', KEYS[1])
if not stock then
    return { -1, 0 }
end
stock = tonumber(stock)
local qty = tonumber(ARGV[1])
if stock <= 0 then
    return { 0, 0 }
end
local have = tonumber(redis.call('GET', KEYS[2]) or '0')
if have + qty > tonumber(ARGV[2]) then
    return { -2, have }
end
if stock < qty then
    return { 0, stock }
end
redis.call('DECRBY', KEYS[1], qty)
redis.call('INCRBY', KEYS[2], qty)
redis.call('EXPIRE', KEYS[2], ARGV[3])
return { 1, stock - qty }
