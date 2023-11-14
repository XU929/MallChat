package com.abin.mallchat.cache.manager;

import com.abin.mallchat.cache.AbstractSycCache;
import com.abin.mallchat.cache.config.properties.CacheConfigProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * @author fuwei.deng
 * @version 1.0.0
 */
@Slf4j
public class RedisCaffeineCacheManager implements CacheManager {

    private ConcurrentMap<String, Cache> cacheMap = new ConcurrentHashMap();

    private CacheConfigProperties cacheConfigProperties;

    private RedisTemplate<Object, Object> redisTemplate;

    private boolean dynamic;

    private Set<String> cacheNames;

    public RedisCaffeineCacheManager(CacheConfigProperties cacheConfigProperties,
                                     RedisTemplate<Object, Object> redisTemplate) {
        super();
        this.cacheConfigProperties = cacheConfigProperties;
        this.redisTemplate = redisTemplate;
        this.dynamic = cacheConfigProperties.isDynamic();
        this.cacheNames = cacheConfigProperties.getCacheNames();
    }

    @Override
    public Cache getCache(String name) {
        Cache cache = cacheMap.get(name);
        if (cache != null) {
            return cache;
        }
        if (!dynamic && !cacheNames.contains(name)) {
            return cache;
        }
        // todo lee -> name 格式 { 缓存级别:缓存名, 例如: L1:cacheName }
        // 提供 Simple 类
        cache = null;
        Cache oldCache = cacheMap.putIfAbsent(name, cache);
        log.debug("create cache instance, the cache name is : {}", name);
        return oldCache == null ? cache : oldCache;
    }

    @Override
    public Collection<String> getCacheNames() {
        return this.cacheNames;
    }

    /**
     * 不发布事件
     *
     * @param cacheName
     * @param keys
     */
    public void clearLocal(String cacheName, Collection<?> keys) {
        Cache cache = cacheMap.get(cacheName);
        if (cache == null) {
            return;
        }
        if (cache instanceof AbstractSycCache) {
            AbstractSycCache sycCache = (AbstractSycCache) cache;
            sycCache.clearLocal(keys);
        }
    }
}
