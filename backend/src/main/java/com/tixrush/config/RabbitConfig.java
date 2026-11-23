package com.tixrush.config;

import com.tixrush.order.messaging.OrderQueues;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * RabbitMQ setup. Spring Boot already creates the connection (from spring.rabbitmq.addresses),
 * a RabbitTemplate (for sending) and listener containers (for @RabbitListener).
 *
 * Every Queue @Bean below is created on the broker at startup if it doesn't exist yet
 * (Spring's RabbitAdmin does this when the first connection opens).
 */
@Configuration
@EnableConfigurationProperties(OrderProperties.class)
public class RabbitConfig {

    @Bean
    Queue orderCreateQueue() {
        return OrderQueues.create();
    }

    @Bean
    Queue orderCreateDeadLetterQueue() {
        return QueueBuilder.durable(OrderQueues.CREATE_DLQ).build();
    }

    @Bean
    Queue orderExpiredQueue() {
        return OrderQueues.expired();
    }

    @Bean
    Queue orderExpiredDeadLetterQueue() {
        return QueueBuilder.durable(OrderQueues.EXPIRED_DLQ).build();
    }

    @Bean
    Queue orderHoldQueue(OrderProperties props) {
        return OrderQueues.hold(props.paymentHold().toSeconds());
    }

    /**
     * Send and receive messages as JSON instead of Java serialization (which is unsafe and Java-only).
     * Only classes from our messaging package may be created from incoming messages ("trusted packages").
     */
    @Bean
    MessageConverter messageConverter(JsonMapper jsonMapper) {
        return new JacksonJsonMessageConverter(jsonMapper, "com.tixrush.order.messaging");
    }
}
