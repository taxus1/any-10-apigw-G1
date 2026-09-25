package com.apigw.infrastructure.gateway;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 「路径前缀」到 PathPattern 的翻译测试。
 *
 * 直接拿 /order/ 当 SCG 的 Path 断言用，是匹配不到 /order/abc 的，
 * 所以配置里的前缀必须先归一成 /order/** 这类的通配。
 */
class RedisRouteDefinitionRepositoryTest {

    @Test
    void trailingSlash_becomesDoubleStar() {
        assertEquals("/order/**", RedisRouteDefinitionRepository.toPrefixPattern("/order/"));
    }

    @Test
    void noTrailingSlash_alsoBecomesDoubleStar() {
        assertEquals("/order/**", RedisRouteDefinitionRepository.toPrefixPattern("/order"));
    }

    @Test
    void root_becomesAll() {
        assertEquals("/**", RedisRouteDefinitionRepository.toPrefixPattern("/"));
    }

    @Test
    void existingWildcard_keptAsIs() {
        assertEquals("/order/**", RedisRouteDefinitionRepository.toPrefixPattern("/order/**"));
        assertEquals("/order/*", RedisRouteDefinitionRepository.toPrefixPattern("/order/*"));
    }

    @Test
    void blank_isRejected() {
        assertThrows(IllegalStateException.class,
                () -> RedisRouteDefinitionRepository.toPrefixPattern("  "));
    }
}
