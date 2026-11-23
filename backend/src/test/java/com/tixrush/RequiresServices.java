package com.tixrush;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.platform.commons.util.AnnotationUtils;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Runs the test only when the services it needs exist:
 *   - Docker is available (CI): TestcontainersConfiguration starts private Redis/RabbitMQ, or
 *   - the cloud ones are configured (this laptop): REDIS_URL / RABBITMQ_URL are set.
 * Otherwise the test is skipped (shown as "skipped", with the reason), instead of failing.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(RequiresServices.Check.class)
public @interface RequiresServices {

    boolean redis() default true;

    boolean rabbit() default false;

    class Check implements ExecutionCondition {
        @Override
        public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
            RequiresServices needs = context.getElement()
                    .flatMap(e -> AnnotationUtils.findAnnotation(e, RequiresServices.class))
                    .orElse(null);
            if (needs == null || DockerCheck.available()) {
                return ConditionEvaluationResult.enabled("Docker provides the services");
            }
            if (needs.redis() && !set("REDIS_URL", "rediss?://.+")) {
                return ConditionEvaluationResult.disabled("No Docker and no REDIS_URL");
            }
            if (needs.rabbit() && !set("RABBITMQ_URL", "amqps?://.+")) {
                return ConditionEvaluationResult.disabled("No Docker and no RABBITMQ_URL");
            }
            return ConditionEvaluationResult.enabled("Using REDIS_URL / RABBITMQ_URL");
        }

        private static boolean set(String name, String pattern) {
            String value = System.getenv(name);
            return value != null && value.matches(pattern);
        }
    }
}
