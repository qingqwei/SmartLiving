--库存判断、一人一单、扣库存、标记用户下单、XADD stream.orders写入订单消息，是在 Redis 内一次完成的
local voucherId = ARGV[1]
local userId = ARGV[2]
local orderId = ARGV[3]

local stockKey = 'seckill:stock:' .. voucherId
local orderKey = 'seckill:order:' .. voucherId
local stock = tonumber(redis.call('get', stockKey))

if (not stock) or stock <= 0 then
    return 1
end

if (redis.call('sismember', orderKey, userId) == 1) then
    return 2
end
--扣库存   --下单，保存用户   --发送消息到队列中
redis.call('incrby', stockKey, -1)
redis.call('sadd', orderKey, userId)
redis.call('xadd', 'stream.orders', '*', 'userId', userId, 'voucherId', voucherId, 'id', orderId)
return 0
