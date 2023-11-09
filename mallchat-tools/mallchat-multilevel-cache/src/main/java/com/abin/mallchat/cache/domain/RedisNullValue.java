package com.abin.mallchat.cache.domain;

import lombok.Data;

import java.io.Serializable;

/**
 * @author lee
 */
@Data
class RedisNullValue implements Serializable {

    private static final long serialVersionUID = 1L;

    public static RedisNullValue REDISNULLVALUE = new RedisNullValue();
}
