package com.abin.mallchat.cache.adaptor;

import cn.hutool.core.collection.CollectionUtil;
import com.abin.mallchat.cache.BatchCache;
import com.abin.mallchat.cache.domain.RedisCaffeineCache;
import lombok.AllArgsConstructor;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

/**
 * @author lee
 */
@AllArgsConstructor
public abstract class RedisCaffeineCacheAdaptor<IN extends Number & CharSequence, OUT> implements BatchCache<IN, OUT> {

    private final RedisCaffeineCache cache;

    public <T> T get(Object key, Callable<T> valueLoader) {
        return cache.get(key, valueLoader);
    }

    @Override
    public OUT get(IN req) {
        return getBatch(Collections.singletonList(req)).get(req);
    }

    @Override
    public Map<IN, OUT> getBatch(List<IN> req) {
        if (CollectionUtil.isEmpty(req)) {//防御性编程
            return new HashMap<>();
        }
        // 去重
        req = req.stream().distinct().collect(Collectors.toList());
        // 组装key
        List<String> keys = req.stream().map(this::getKey).collect(Collectors.toList());
        // key 的 value 不为空的集合
        List<OUT> valueList = new ArrayList(req.size());
        // 差集计算
        List<IN> loadReqs = new ArrayList<>();
        for (int i = 0; i < keys.size(); i++) {
            OUT value = get(keys.get(i), () -> null);
            if (Objects.isNull(value)) {
                loadReqs.add(req.get(i));
            } else {
                valueList.add(value);
            }
        }

        Map<IN, OUT> load = new HashMap<>();
        // 查询不到的缓存重新加载到缓存
        if (CollectionUtil.isNotEmpty(loadReqs)) {
            //批量load
            load = load(loadReqs);
            // 查询不到的缓存补充到缓存
            for (Map.Entry<IN, OUT> entry : load.entrySet()) {
                cache.put(entry.getKey(), entry.getValue(), Duration.ofHours(getExpireSeconds()));
            }
        }

        // 组装最后的结果
        Map<IN, OUT> resultMap = new HashMap<>();
        for (int i = 0; i < req.size(); i++) {
            IN in = req.get(i);
            OUT out = Optional.ofNullable(valueList.get(i))
                    .orElse(load.get(in));
            resultMap.put(in, out);
        }
        return resultMap;
    }

    @Override
    public void delete(IN req) {

    }

    @Override
    public void deleteBatch(List<IN> req) {

    }

    protected RedisCaffeineCache getCache() {
        return cache;
    }

    protected abstract String getKey(IN req);

    protected abstract Long getExpireSeconds();

    protected abstract Map<IN, OUT> load(List<IN> req);
}
