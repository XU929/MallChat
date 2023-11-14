package com.abin.mallchat.cache;

import com.abin.mallchat.cache.domain.CacheMessage;
import com.abin.mallchat.utils.RedisUtils;
import com.github.benmanes.caffeine.cache.LoadingCache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.serializer.RedisSerializer;

import java.util.Collection;

/**
 * @author lee
 */
@Slf4j
public abstract class AbstractSycCache<IN, OUT> {
    /**
     * 本地缓存
     */
    protected LoadingCache<IN, OUT> cache;

    private RedisSerializer<String> stringSerializer = RedisSerializer.string();

    private RedisSerializer<Object> javaSerializer = RedisSerializer.java();

    /**
     * @param message
     * @description 缓存变更时通知其他节点清理本地缓存
     * @author lee
     */
    protected void push(String topic,CacheMessage message) {
        /**
         * 为了能自定义 redisTemplate，发布订阅的序列化方式固定为jdk序列化方式。
         */
        byte[] rawChannel = stringSerializer.serialize(topic);
        byte[] rawMessage = javaSerializer.serialize(message);
        // 异步
        RedisUtils.getTemplate().execute((connection) -> {
            connection.publish(rawChannel, rawMessage);
            return null;
        }, true);
    }

    /**
     * @param keys
     * @description 清理本地缓存
     * @author fuwei.deng
     * @date 2018年1月31日 下午3:15:39
     * @version 1.0.0
     */
    public void clearLocal(Collection<Object> keys) {
        log.debug("clear local cache, the key is : {}", keys);
        if (keys != null && !keys.isEmpty()) {
            cache.invalidate(keys);
        } else {
            cache.invalidateAll();
        }
    }
}
