package com.abin.mallchat.cache;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.collection.CollectionUtil;
import com.abin.mallchat.cache.domain.CacheMessage;
import com.abin.mallchat.utils.RedisUtils;
import com.github.benmanes.caffeine.cache.CacheLoader;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.collect.Iterables;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.util.Pair;
import org.springframework.util.CollectionUtils;

import java.lang.reflect.ParameterizedType;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * @author lee
 */
public abstract class AbstractRedisCaffeineCache<IN extends Number & CharSequence, OUT> extends AbstractSycCache<IN, OUT> implements BatchCache<IN, OUT> {

    @Value("${mallchat.cache.topic}")
    private String topic;

    private Class<OUT> outClass;
    private Class<IN> inClass;

    protected AbstractRedisCaffeineCache() {
        this(60, 10 * 60, 1024);
    }

    protected AbstractRedisCaffeineCache(long refreshSeconds, long expireSeconds, int maxSize) {
        init(refreshSeconds, expireSeconds, maxSize);
    }

    @Override
    public OUT get(IN req) {
        return cache.get(req);
    }

    @Override
    public Map<IN, OUT> getBatch(List<IN> req) {
        return cache.getAll(req);
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

    protected abstract String getKey(IN req);

    protected abstract Map<IN, OUT> load(List<IN> req);

    protected abstract Long getExpireSeconds();

    private void init(long refreshSeconds, long expireSeconds, int maxSize) {
        ParameterizedType genericSuperclass = (ParameterizedType) this.getClass().getGenericSuperclass();
        this.outClass = (Class<OUT>) genericSuperclass.getActualTypeArguments()[1];
        this.inClass = (Class<IN>) genericSuperclass.getActualTypeArguments()[0];
        cache = Caffeine.newBuilder()
                //自动刷新,不会阻塞线程,其他线程返回旧值
                .refreshAfterWrite(refreshSeconds, TimeUnit.SECONDS)
                .expireAfterWrite(expireSeconds, TimeUnit.SECONDS)
                .maximumSize(maxSize)
                .build(new CacheLoader<IN, OUT>() {
                    @Nullable
                    @Override
                    public OUT load(@NonNull IN in) throws Exception {
                        return AbstractRedisCaffeineCache.this.loadForRedis(Collections.singletonList(in)).get(in);
                    }

                    @Override
                    public @NonNull Map<IN, OUT> loadAll(@NonNull Iterable<? extends IN> keys) throws Exception {
                        IN[] ins = Iterables.toArray(keys, inClass);
                        return AbstractRedisCaffeineCache.this.loadForRedis(Arrays.asList(ins));
                    }
                });
    }

    private Map<IN, OUT> loadForRedis(List<IN> req) {
        // 取 redis (二级)缓存键
        List<String> keys = req.stream().map(this::getKey).collect(Collectors.toList());
        // 批量get
        List<OUT> values = RedisUtils.mget(keys, outClass);
        // redis不存在的缓存
        List<IN> loadReqs = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            if (Objects.isNull(values.get(i))) {
                loadReqs.add(req.get(i));
            }
        }
        // 数据库加载的数据
        Map<IN, OUT> load = new HashMap<>();
        if (CollectionUtil.isNotEmpty(loadReqs)) {
            // redis 获取不到, 再到数据库加载
            load = load(loadReqs);
            Map<String, OUT> loadMap = load.entrySet().stream()
                    .map(a -> Pair.of(getKey(a.getKey()), a.getValue()))
                    .collect(Collectors.toMap(Pair::getFirst, Pair::getSecond));
            // 缓存到 redis(设置时间)
            RedisUtils.mset(loadMap, getExpireSeconds());
        }

        // 组装最后的结果
        Map<IN, OUT> resultMap = new HashMap<>();
        for (int i = 0; i < req.size(); i++) {
            IN in = req.get(i);
            OUT out = Optional.ofNullable(resultMap.get(i))
                    .orElse(load.get(in));
            resultMap.put(in, out);
        }

        return resultMap;
    }
}
