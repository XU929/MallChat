package com.abin.mallchat.cache.config;

import com.abin.mallchat.cache.config.properties.CacheConfigProperties;
import com.abin.mallchat.cache.listener.CacheMessageListener;
import com.abin.mallchat.cache.manager.RedisCaffeineCacheManager;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * @author lee
 * @version 1.0.0
 */
@Configuration(proxyBeanMethods = false)
@AutoConfigureAfter(RedisAutoConfiguration.class)
@EnableConfigurationProperties(CacheConfigProperties.class)
public class MultilevelCacheAutoConfiguration {

    @Bean
    @ConditionalOnBean(RedisTemplate.class)
    public RedisCaffeineCacheManager cacheManager(CacheConfigProperties cacheConfigProperties,
                                                  @Qualifier("myRedisTemplate") RedisTemplate<Object, Object> redisTemplate) {
        return new RedisCaffeineCacheManager(cacheConfigProperties, redisTemplate);
    }


    @Bean
    @ConditionalOnBean(RedisTemplate.class)
    public RedisMessageListenerContainer cacheMessageListenerContainer(CacheConfigProperties cacheConfigProperties,
                                                                       @Qualifier("myRedisTemplate") RedisTemplate<Object, Object> redisTemplate,
                                                                       RedisCaffeineCacheManager redisCaffeineCacheManager) {
        RedisMessageListenerContainer redisMessageListenerContainer = new RedisMessageListenerContainer();
        redisMessageListenerContainer.setConnectionFactory(redisTemplate.getConnectionFactory());
        CacheMessageListener cacheMessageListener = new CacheMessageListener(redisCaffeineCacheManager);
        redisMessageListenerContainer.addMessageListener(cacheMessageListener,
                new ChannelTopic(cacheConfigProperties.getRedis().getTopic()));
        return redisMessageListenerContainer;
    }

}
