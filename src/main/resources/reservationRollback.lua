-- SREM 是回滚的幂等门闩：同一占位标记只能被移除一次，从而不会重复 INCR 名额。
if redis.call('SREM', KEYS[2], ARGV[1]) == 0 then
    return 0
end
if redis.call('EXISTS', KEYS[1]) == 1 then
    redis.call('INCR', KEYS[1])
    return 1
end
-- 批次关闭后名额键可能已删除；此时仅清理用户标记，不应重建过期的名额键。
return 2
