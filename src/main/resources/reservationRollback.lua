if redis.call('EXISTS', KEYS[1]) == 0 then
    redis.call('SREM', KEYS[2], ARGV[1])
    return 0
end
redis.call('INCR', KEYS[1])
redis.call('SREM', KEYS[2], ARGV[1])
return 1
