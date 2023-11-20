package com.abin.mallchat.cache.domain;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.io.Serializable;
import java.util.Collection;

/**
 * @author lee
 */
@Data
@AllArgsConstructor
public class CacheMessage implements Serializable {

    private String cacheName;

    private Collection<?> keys;
}
