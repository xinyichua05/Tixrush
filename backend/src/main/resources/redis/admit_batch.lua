-- Let the next N fans out of the waiting room and give each one a time-limited pass.
--
-- One atomic step: ZPOPMIN takes the N lowest scores (= the front of the line) and REMOVES them,
-- so two servers running this at the same moment can never admit the same fan twice.
--
-- KEYS[1] = the line (sorted set), e.g. "tixrush:wr:42:line"
-- ARGV[1] = how many to admit
-- ARGV[2] = pass key prefix, e.g. "tixrush:wr:42:pass:"  (the member is appended)
-- ARGV[3] = pass lifetime in seconds
-- Returns the admitted members, front of the line first.

local popped = redis.call('ZPOPMIN', KEYS[1], ARGV[1])
local admitted = {}
for i = 1, #popped, 2 do          -- popped = { member1, score1, member2, score2, ... }
    local member = popped[i]
    redis.call('SET', ARGV[2] .. member, '1', 'EX', ARGV[3])
    admitted[#admitted + 1] = member
end
return admitted
