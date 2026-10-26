-- KEYS[1] 为批次剩余名额，KEYS[2] 为已占位用户集合。
-- Redis 保证脚本整体原子执行，因此校验、DECR 与 SADD 之间不会被其他预约穿插。
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
-- 占位集合比使用时段多保留一段缓冲期，便于延迟消费与补偿判断用户是否真正占用过名额。
redis.call('EXPIRE', KEYS[2], ARGV[5])
return 0
