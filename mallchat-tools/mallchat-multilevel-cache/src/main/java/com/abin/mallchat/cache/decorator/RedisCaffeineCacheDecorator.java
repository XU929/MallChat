package com.abin.mallchat.cache.decorator;

import cn.hutool.core.collection.CollectionUtil;
import com.abin.mallchat.cache.BatchCache;
import com.abin.mallchat.cache.domain.RedisCaffeineCache;
import lombok.AllArgsConstructor;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * @author lee
 */
@AllArgsConstructor
public abstract class RedisCaffeineCacheDecorator<IN extends Number & CharSequence, OUT> implements BatchCache<IN, OUT> {

    private final RedisCaffeineCache cache;

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

        // 组装最后的结果
        Map<IN, OUT> resultMap = new HashMap<>();
        for (int i = 0; i < keys.size(); i++) {
            IN key = req.get(i);
            // 查询并补差集
            OUT value = cache.get(keys.get(i), () -> load(key));
            resultMap.put(key, value);
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

    protected abstract OUT load(IN req);
}
