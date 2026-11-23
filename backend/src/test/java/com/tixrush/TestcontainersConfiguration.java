package com.tixrush;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

/**
 * Throwaway Postgres, Redis and RabbitMQ for the tests, each in its own Docker container.
 *
 * Why: without this, tests use the same database and the same cloud queues as the dev backend,
 * so a running backend could steal the tests' order messages. With containers, every test run
 * starts from empty, private services and nothing leaks between runs.
 *
 * How:
 *  - The three containers are started ONCE per test run (static), and every Spring test context
 *    shares them. Testcontainers' "Ryuk" helper removes them when the JVM exits.
 *  - The DynamicPropertyRegistrar points spring.datasource / spring.data.redis / spring.rabbitmq
 *    at the containers' random ports, overriding the values in application.yml.
 *  - Only when Docker is available (GitHub Actions has it). On a laptop without Docker this whole
 *    class is skipped and the tests use the normal settings (local Postgres + REDIS_URL/RABBITMQ_URL).
 *
 * Every @SpringBootTest imports it: @Import(TestcontainersConfiguration.class).
 */
@TestConfiguration(proxyBeanMethods = false)
@Conditional(TestcontainersConfiguration.DockerAvailable.class)
public class TestcontainersConfiguration {

    /** Started on first use (when Spring first creates the bean below), then reused. */
    static final class Containers {
        // Spring keeps each different test setup ("context") alive for reuse, and each one holds its own
        // pool of 20 DB connections. Postgres allows only 100 by default, so allow more.
        static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine")
                .withDatabaseName("tixrush").withUsername("tixrush").withPassword("tixrush")
                .withCommand("postgres", "-c", "max_connections=400");
        static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
        static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4-management-alpine");

        static {
            POSTGRES.start();
            REDIS.start();
            RABBIT.start();
        }
    }

    @Bean
    DynamicPropertyRegistrar containerConnections() {
        return registry -> {
            registry.add("spring.datasource.url", Containers.POSTGRES::getJdbcUrl);
            registry.add("spring.datasource.username", Containers.POSTGRES::getUsername);
            registry.add("spring.datasource.password", Containers.POSTGRES::getPassword);
            registry.add("spring.data.redis.url",
                    () -> "redis://" + Containers.REDIS.getHost() + ":" + Containers.REDIS.getMappedPort(6379));
            registry.add("spring.rabbitmq.addresses", Containers.RABBIT::getAmqpUrl);
        };
    }

    /** Spring condition: only use this configuration when a Docker engine answers. */
    static class DockerAvailable implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return DockerCheck.available();
        }
    }
}
