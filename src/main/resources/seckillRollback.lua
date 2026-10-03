-- 回滚秒杀资格：库存 +1，并移除用户下单标记（与 seckill.lua 的 key 前缀保持一致）
-- 1.参数
local voucherId = ARGV[1]
local userId = ARGV[2]

-- 2.数据key
local stockKey = 'seckill:stock:' .. voucherId
local orderKey = 'seckill:order:' .. voucherId

-- 3.回滚
redis.call('incrby', stockKey, 1)
redis.call('srem', orderKey, userId)
return 0
