package com.abin.mallchat.cache;

import cn.hutool.core.collection.CollUtil;
import com.abin.mallchat.cache.domain.CacheMessage;
import com.abin.mallchat.utils.RedisUtils;
import org.springframework.util.CollectionUtils;

import java.lang.reflect.ParameterizedType;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * @author lee
 */
public abstract class AbstractRedisCaffeineCache<IN, OUT> extends AbstractSycCache<IN, OUT> implements BatchCache<IN, OUT> {

    private Class<OUT> outClass;
    private Class<IN> inClass;
    private AbstractRedisStringCache<IN, OUT> secondLevelCache;
    private AbstractLocalCache<IN, OUT> firstLevelCache;

    protected AbstractRedisCaffeineCache() {
        this(60, 10 * 60, 1024);
    }

    protected AbstractRedisCaffeineCache(long refreshSeconds, long expireSeconds, int maxSize) {
        init(refreshSeconds, expireSeconds, maxSize);
    }

    @Override
    public OUT get(IN req) {
        return firstLevelCache.get(req);
    }

    @Override
    public Map<IN, OUT> getBatch(List<IN> req) {//这里可以改成
        return firstLevelCache.getBatch(req);
    }

    @Override
    public void delete(IN req) {
        deleteBatch(Collections.singletonList(req));
    }

    @Override
    public void deleteBatch(List<IN> req) {
        /*
         * 先删除 redis
         * 发布事件
         * 删除本地
         */
        if (CollUtil.isEmpty(req)) {
            Set<String> keys = RedisUtils.getTemplate().keys(getKey(null).concat(":*"));
            if (!CollectionUtils.isEmpty(keys)) {
                RedisUtils.del(keys);
            }
            push(topic, new CacheMessage(getKey(null), null));
            cache.invalidateAll();
            return;
        }

        List<String> keys = req.stream().map(this::getKey).collect(Collectors.toList());
        RedisUtils.del(keys);
        push(topic, new CacheMessage(getKey(null), keys));
        cache.invalidate(req);
    }

    protected abstract Map<IN, OUT> load(List<IN> req);

    private void init(long refreshSeconds, long expireSeconds, int maxSize) {
        ParameterizedType genericSuperclass = (ParameterizedType) this.getClass().getGenericSuperclass();
        this.outClass = (Class<OUT>) genericSuperclass.getActualTypeArguments()[1];
        this.inClass = (Class<IN>) genericSuperclass.getActualTypeArguments()[0];
        firstLevelCache = new SimpleLocalCache(refreshSeconds, expireSeconds, maxSize);
        secondLevelCache = new SimpleRedisCache();
    }

    class SimpleRedisCache extends AbstractRedisStringCache<IN, OUT> {

        @Override
        protected String getKey(IN req) {
            return null;//本地定义
        }

        @Override
        protected Long getExpireSeconds() {
            return getExpireSeconds();
        }

        @Override
        protected Map<IN, OUT> load(List<IN> req) {
            // redis缓存拿不到，就去原始实现拿
            return AbstractRedisCaffeineCache.this.load(req);
        }
    }

    class SimpleLocalCache extends AbstractLocalCache<IN, OUT> {
        public SimpleLocalCache(long refreshSeconds, long expireSeconds, int maxSize) {
            super(refreshSeconds, expireSeconds, maxSize);
        }

        @Override
        protected Map<IN, OUT> load(List<IN> req) {
            // 本地缓存拿不到，就去 redis 拿
            return secondLevelCache.getBatch(req);
        }
    }
}
