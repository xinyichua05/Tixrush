-- Atomically take EXACT seats (all or nothing), respecting the stock and the per-person limit.
--
-- This is the heart of "two people click Row F Seat 12 at the same moment": Redis runs this
-- script as ONE step, so the second one sees the seat as taken and gets {-3, seatId}.
--
-- KEYS[1] = stock of the ticket type          "tixrush:stock:event:42:type:7"
-- KEYS[2] = tickets this person holds         "tixrush:bought:event:42:user:5"
-- KEYS[3] = taken seats (hash seatId -> who)  "tixrush:seats:event:42:type:7"
-- ARGV[1] = how many seats, ARGV[2] = per-person limit, ARGV[3] = lifetime of KEYS[2] (seconds)
-- ARGV[4] = holder (the reservation id), ARGV[5..] = the seat ids
--
-- Returns { 1, left } got them | { 0, left } not enough left | { -1, 0 } not loaded yet
--         {-2, have} over the per-person limit | {-3, seatId} that seat is already taken

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
for i = 5, #ARGV do
    if redis.call('HEXISTS', KEYS[3], ARGV[i]) == 1 then
        return { -3, tonumber(ARGV[i]) }
    end
end
if stock < qty then
    return { 0, stock }
end
redis.call('DECRBY', KEYS[1], qty)
redis.call('INCRBY', KEYS[2], qty)
redis.call('EXPIRE', KEYS[2], ARGV[3])
for i = 5, #ARGV do
    redis.call('HSET', KEYS[3], ARGV[i], ARGV[4])
end
return { 1, stock - qty }
