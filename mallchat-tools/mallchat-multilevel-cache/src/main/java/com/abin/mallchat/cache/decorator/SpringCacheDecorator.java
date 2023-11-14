package com.abin.mallchat.cache.decorator;

import com.abin.mallchat.cache.AbstractRedisCaffeineCache;
import com.abin.mallchat.cache.BatchCache;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.support.AbstractValueAdaptingCache;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * @author lee
 */
@Slf4j
public class SpringCacheDecorator extends AbstractValueAdaptingCache {

    @Getter
    private final String name;

    private final BatchCache cache;

    private final Map<String, ReentrantLock> keyLockMap = new ConcurrentHashMap<>();

    protected SpringCacheDecorator(boolean allowNullValues, String name) {
        super(allowNullValues);
        this.name = name;
        cache = new AbstractRedisCaffeineCache() {
            @Override
            protected String getKey(Number req) {
                return null;
            }

            @Override
            protected Map load(List req) {
                return null;
            }

            @Override
            protected Long getExpireSeconds() {
                return null;
            }
        };
    }


    @Override
    protected Object lookup(Object key) {
        return cache.get(key);
    }

    @Override
    public Object getNativeCache() {
        return null;
    }

    @Override
    public <T> T get(Object key, Callable<T> valueLoader) {
        // 取值(L1 L2 中取)
        Object value = lookup(key);
        if (value != null) {
            return (T) value;
        }

        // 第一次取值为空(加锁)
        ReentrantLock lock = keyLockMap.computeIfAbsent(key.toString(), s -> {
            log.trace("create lock for key : {}", s);
            return new ReentrantLock();
        });

        lock.lock();
        try {
            // 取值
            value = lookup(key);
            if (value != null) {
                return (T) value;
            }
            // 设置默认值(或者再做一些其他操作)
            value = valueLoader.call();
            return (T) value;
        } catch (Exception e) {
            throw new ValueRetrievalException(key, valueLoader, e.getCause());
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void put(Object key, Object value) {

    }

    @Override
    public void evict(Object key) {
        cache.delete(key);
    }

    @Override
    public void clear() {
        cache.deleteBatch(null);
    }
}
