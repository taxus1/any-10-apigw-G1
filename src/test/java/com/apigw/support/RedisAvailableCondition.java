package com.apigw.support;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * 集成测试开关：只有能连上 Redis（默认 localhost:6379，可用 -Dredis.host/-Dredis.port 覆盖）
 * 的机器才跑需要真实 Redis 的测试；连不上就跳过，而不是报一堆连接失败。
 *
 * 本地 docker compose up -d 起好 Redis，或 CI 里有 Redis 服务时，这些测试自动生效。
 */
public class RedisAvailableCondition implements ExecutionCondition {

    public static String host() {
        return System.getProperty("redis.host", System.getenv().getOrDefault("REDIS_HOST", "localhost"));
    }

    public static int port() {
        String p = System.getProperty("redis.port", System.getenv().getOrDefault("REDIS_PORT", "6379"));
        return Integer.parseInt(p);
    }

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host(), port()), 300);
            return ConditionEvaluationResult.enabled("Redis 可达：" + host() + ":" + port());
        } catch (Exception e) {
            return ConditionEvaluationResult.disabled("Redis 不可达（" + host() + ":" + port()
                    + "），跳过需要真实 Redis 的测试；docker compose up -d 起好 Redis 后会自动执行");
        }
    }
}
