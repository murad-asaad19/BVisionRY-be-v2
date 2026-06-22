package com.bvisionry.aiconfig;

import com.bvisionry.aiconfig.service.AIConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;

/**
 * Cross-instance AI-config cache invalidation subscriber (R1#13).
 *
 * <p>{@link AIConfigService} memoizes the singleton config in-process for a short TTL.
 * On a committed write it publishes to the {@link AIConfigService#INVALIDATE_CHANNEL}
 * Redis Pub/Sub channel; this container subscribes on every backend instance and clears
 * that instance's local memo on receipt, so an admin rotating a key or switching the
 * provider on one node is reflected on all the others within a Pub/Sub round-trip rather
 * than being bounded by the per-process TTL.
 *
 * <p>Gated on {@link ConditionalOnBean} for {@link RedisConnectionFactory}: when no Redis
 * connection is configured (unit/local runs) the container is simply not created and
 * startup is unaffected. Local single-instance invalidation still works because the
 * service clears its own memo inline; only the cross-instance broadcast is skipped.
 */
@Slf4j
@Configuration
@ConditionalOnBean(RedisConnectionFactory.class)
public class AIConfigCacheInvalidationConfig {

    /**
     * Adapts the listener to {@link AIConfigService#invalidateConfigCache()}. The payload
     * is intentionally empty — the signal itself means "drop your memo", so no method
     * argument is forwarded.
     */
    @Bean
    public MessageListenerAdapter aiConfigInvalidationListener(AIConfigService aiConfigService) {
        return new MessageListenerAdapter(new Object() {
            @SuppressWarnings("unused")
            public void handleMessage(String message) {
                aiConfigService.invalidateConfigCache();
            }
        });
    }

    @Bean
    public RedisMessageListenerContainer aiConfigInvalidationContainer(
            RedisConnectionFactory connectionFactory,
            MessageListenerAdapter aiConfigInvalidationListener) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(aiConfigInvalidationListener,
                new ChannelTopic(AIConfigService.INVALIDATE_CHANNEL));
        return container;
    }
}
