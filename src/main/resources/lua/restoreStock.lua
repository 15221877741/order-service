-- KEYS[1] = product:stock:{id}
-- KEYS[2] = product:{id}
-- ARGV[1] = quantity (string)
-- 功能：原子恢复库存（INCRBY + cjson 更新缓存）

local stock_str = redis.call("GET", KEYS[1])
if not stock_str then
    return -1
end

local qty = tonumber(ARGV[1])
redis.call("INCRBY", KEYS[1], qty)

local new_stock = tonumber(stock_str) + qty

local json_str = redis.call("GET", KEYS[2])
if json_str then
    local obj = cjson.decode(json_str)
    if obj then
        obj["stock"] = new_stock
        redis.call("SET", KEYS[2], cjson.encode(obj), "EX", 3600)
    end
end

return 1
