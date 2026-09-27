
-- KEYS[1] = bucket key, e.g. "rate_limiter:user123"
-- ARGV[1] = capacity      (max tokens the bucket can hold)
-- ARGV[2] = refill_rate   (tokens added per second)
-- ARGV[3] = requested_token     (tokens this call wants to consume, usually 1)
local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local refill_rate = tonumber(ARGV[2])
local requested_token = tonumber(ARGV[3])

local time = redis.call('TIME')
local now = tonumber(time[1]) + tonumber(time[2])/1000000


local bucket = redis.call('HMGET', key, 'tokens', 'last_refreshed')
local tokens = tonumber(bucket[1])
local last_refreshed = tonumber(bucket[2])

if tokens == nil then
    tokens = capacity
    last_refreshed = now
end


local elapsed = math.max(0, now - last_refreshed)
tokens = math.min(capacity, tokens + elapsed * refill_rate);

local allowed = 0
if tokens >= requested_token then
    tokens = tokens - requested_token
    allowed = 1
end

redis.call('HMSET', key, 'tokens', tokens, 'last_refreshed', now)

local ttl = math.ceil(capacity/refill_rate) * 2
redis.call('EXPIRE', key, ttl)

return allowed