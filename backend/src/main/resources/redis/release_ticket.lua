-- Give one ticket back, but ONLY if the stock counter is loaded.
-- If the key is missing (reset, or never loaded), the next purchase reloads the stock
-- from the database, which already includes the returned ticket. A plain INCR on a
-- missing key would create "1" and hand out a wrong stock number.
-- Returns the new stock, or -1 if the key didn't exist.
if redis.call('EXISTS', KEYS[1]) == 1 then
    return redis.call('INCR', KEYS[1])
end
return -1
