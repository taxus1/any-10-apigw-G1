package com.apigw.support;

import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 仅在能连上真实 Redis 时执行（见 {@link RedisAvailableCondition}）。 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(RedisAvailableCondition.class)
public @interface EnabledIfRedis {
}
