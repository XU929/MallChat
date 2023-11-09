package com.abin.mallchat.cache.domain.enums;

import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * @author lee
 * {@link Caffeine.Strength}
 */
@SuppressWarnings("JavadocReference")
public enum CaffeineStrength {

    /**
     * 弱引用
     */
    WEAK,
    /**
     * 软引用
     */
    SOFT

}
