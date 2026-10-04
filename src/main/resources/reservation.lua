if redis.call('EXISTS', KEYS[1]) == 0 then
    return 1
end

local now = tonumber(ARGV[2])
local bookingStartTime = tonumber(ARGV[3])
local bookingEndTime = tonumber(ARGV[4])
if now < bookingStartTime then
    return 4
end
if now > bookingEndTime then
    return 5
end

local quota = redis.call('GET', KEYS[1])
if not quota or tonumber(quota) <= 0 then
    return 2
end

if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then
    return 3
end

redis.call('DECR', KEYS[1])
redis.call('SADD', KEYS[2], ARGV[1])
redis.call('EXPIRE', KEYS[2], ARGV[5])
return 0
