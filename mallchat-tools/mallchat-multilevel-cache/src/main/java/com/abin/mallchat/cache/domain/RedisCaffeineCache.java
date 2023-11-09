package com.abin.mallchat.cache.domain;

import com.abin.mallchat.cache.config.properties.CacheConfigProperties;
import com.github.benmanes.caffeine.cache.Cache;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.support.AbstractValueAdaptingCache;
import org.springframework.cache.support.NullValue;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * @author fuwei.deng
 * @version 1.0.0
 */
@Slf4j
public class RedisCaffeineCache extends AbstractValueAdaptingCache {

    @Getter
    private final String name;

    @Getter
    private final Cache<Object, Object> caffeineCache;

    private final RedisTemplate<Object, Object> redisTemplate;

//    private final String cachePrefix;

    private final Duration defaultExpiration;

    private final Duration defaultNullValuesExpiration;

    private final Map<String, Duration> expires;

    private final String topic;

    private final Map<String, ReentrantLock> keyLockMap = new ConcurrentHashMap<>();

    private RedisSerializer<String> stringSerializer = RedisSerializer.string();

    private RedisSerializer<Object> javaSerializer = RedisSerializer.java();

    public RedisCaffeineCache(String name, RedisTemplate<Object, Object> redisTemplate,
                              Cache<Object, Object> caffeineCache, CacheConfigProperties cacheConfigProperties) {
        super(cacheConfigProperties.isCacheNullValues());
        this.name = name;
        this.redisTemplate = redisTemplate;
        this.caffeineCache = caffeineCache;
//        this.cachePrefix = cacheConfigProperties.getCachePrefix();
        this.defaultExpiration = cacheConfigProperties.getRedis().getDefaultExpiration();
        this.defaultNullValuesExpiration = cacheConfigProperties.getRedis().getDefaultNullValuesExpiration();
        this.expires = cacheConfigProperties.getRedis().getExpires();
        this.topic = cacheConfigProperties.getRedis().getTopic();
    }

    @Override
    public Object getNativeCache() {
        return this;
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T get(Object key, Callable<T> valueLoader) {
        Object value = lookup(key);
        if (value != null) {
            return (T) value;
        }

        ReentrantLock lock = keyLockMap.computeIfAbsent(key.toString(), s -> {
            log.trace("create lock for key : {}", s);
            return new ReentrantLock();
        });

        lock.lock();
        try {
            value = lookup(key);
            if (value != null) {
                return (T) value;
            }
            // 执行函数式接口(意思可以为提供默认值)
            value = valueLoader.call();
            // 校验可否为空值
            Object storeValue = toStoreValue(value);
            // 更新L1 和 L2缓存
            put(key, storeValue);
            return (T) value;
        } catch (Exception e) {
            throw new ValueRetrievalException(key, valueLoader, e.getCause());
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void put(Object key, Object value) {
        if (!super.isAllowNullValues() && value == null) {
            this.evict(key);
            return;
        }
        doPut(key, value);
    }

    public void put(Object key, Object value, Duration expire) {
        if (!super.isAllowNullValues() && value == null) {
            this.evict(key);
            return;
        }
        doPut(key, value);
    }

    @Override
    public ValueWrapper putIfAbsent(Object key, Object value) {
        Object prevValue;
        // 考虑使用分布式锁，或者将redis的setIfAbsent改为原子性操作
        synchronized (key) {
            prevValue = getRedisValue(key);
            if (prevValue == null) {
                doPut(key, value);
            }
        }
        return toValueWrapper(prevValue);
    }

    private void doPut(Object key, Object value) {
        doPut(key, value, Duration.ZERO);
    }

    private void doPut(Object key, Object value, Duration expire) {
        value = toStoreValue(value);
        if (!expire.isNegative() && !expire.isZero()) {
            expire = getExpire(value);
        }
        setRedisValue(key, value, expire);

        push(new CacheMessage(this.name, key));

        caffeineCache.put(key, value);
    }

    @Override
    public void evict(Object key) {
        // 先清除redis中缓存数据，然后清除caffeine中的缓存，避免短时间内如果先清除caffeine缓存后其他请求会再从redis里加载到caffeine中
        redisTemplate.delete(key);
        //
        push(new CacheMessage(this.name, key));

        caffeineCache.invalidate(key);
    }

    @Override
    public void clear() {
        // 先清除redis中缓存数据，然后清除caffeine中的缓存，避免短时间内如果先清除caffeine缓存后其他请求会再从redis里加载到caffeine中
        Set<Object> keys = redisTemplate.keys(this.name.concat(":*"));

        if (!CollectionUtils.isEmpty(keys)) {
            redisTemplate.delete(keys);
        }

        push(new CacheMessage(this.name, null));

        caffeineCache.invalidateAll();
    }

    @Override
    protected Object lookup(Object key) {
        Object value = caffeineCache.getIfPresent(key);
        if (value != null) {
            log.debug("get cache from caffeine, the key is : {}", key);
            return value;
        }

        value = getRedisValue(key);

        if (value != null) {
            log.debug("get cache from redis and put in caffeine, the key is : {}", key);
            caffeineCache.put(key, value);
        }
        return value;
    }

/*    private Object getKey(Object key) {
        return this.name.concat(":").concat(
                StringUtils.isEmpty(cachePrefix) ? key.toString() : cachePrefix.concat(":").concat(key.toString()));
    }*/

    /**
     * 根据key获取过期事件
     *
     * @param value
     * @return
     */
    private Duration getExpire(Object value) {
        Duration cacheNameExpire = expires.get(this.name);
        if (cacheNameExpire == null) {
            cacheNameExpire = defaultExpiration;
        }
        if ((value == null || value == NullValue.INSTANCE) && this.defaultNullValuesExpiration != null) {
            cacheNameExpire = this.defaultNullValuesExpiration;
        }
        return cacheNameExpire;
    }

    /**
     * @param message
     * @description 缓存变更时通知其他节点清理本地缓存
     * @author fuwei.deng
     * @date 2018年1月31日 下午3:20:28
     * @version 1.0.0
     */
    private void push(CacheMessage message) {

        /**
         * 为了能自定义 redisTemplate，发布订阅的序列化方式固定为jdk序列化方式。
         */
        Assert.hasText(topic, "a non-empty channel is required");
        byte[] rawChannel = stringSerializer.serialize(topic);
        byte[] rawMessage = javaSerializer.serialize(message);
        redisTemplate.execute((connection) -> {
            connection.publish(rawChannel, rawMessage);
            return null;
        }, true);

        // redisTemplate.convertAndSend(topic, message);
    }

    /**
     * @param key
     * @description 清理本地缓存
     * @author fuwei.deng
     * @date 2018年1月31日 下午3:15:39
     * @version 1.0.0
     */
    public void clearLocal(Object key) {
        log.debug("clear local cache, the key is : {}", key);
        if (key == null) {
            caffeineCache.invalidateAll();
        } else {
            caffeineCache.invalidate(key);
        }
    }

    private void setRedisValue(Object key, Object value, Duration expire) {

        Object convertValue = value;
        if (value == null || value == NullValue.INSTANCE) {
            convertValue = RedisNullValue.REDISNULLVALUE;
        }

        if (!expire.isNegative() && !expire.isZero()) {
            redisTemplate.opsForValue().set(key, convertValue, expire);
        } else {
            redisTemplate.opsForValue().set(key, convertValue);
        }
    }

    private Object getRedisValue(Object key) {

        // NullValue在不同序列化方式中存在问题，因此自定义了RedisNullValue做个转化。
        Object value = redisTemplate.opsForValue().get(key);
        if (value != null && value instanceof RedisNullValue) {
            value = NullValue.INSTANCE;
        }
        return value;
    }

}
