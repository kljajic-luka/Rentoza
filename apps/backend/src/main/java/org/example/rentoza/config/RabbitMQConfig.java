package org.example.rentoza.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ configuration for async processing.
 * 
 * <h2>Phase 2 Architecture Improvement</h2>
 * <p>Provides message queue infrastructure for:
 * <ul>
 *   <li>Async photo EXIF validation</li>
 *   <li>Async notification delivery</li>
 *   <li>Checkout saga step orchestration</li>
 * </ul>
 * 
 * <h2>Queue Topology</h2>
 * <pre>
 * Exchange: rentoza.direct (Direct Exchange)
 *    │
 *    ├──► Queue: photo.validation
 *    │    Routing Key: photo.validate
 *    │
 *    ├──► Queue: photo.validation.dlq (Dead Letter)
 *    │    Routing Key: photo.validate.dlq
 *    │
 *    ├──► Queue: checkout.saga
 *    │    Routing Key: checkout.saga
 *    │
 *    └──► Queue: notification.async
 *         Routing Key: notification.send
 * </pre>
 * 
 * <h2>Error Handling</h2>
 * <p>Failed messages are sent to DLQ after 3 retry attempts.
 * 
 * @see org.example.rentoza.booking.checkin.photo.PhotoValidationWorker
 */
@Configuration
@ConditionalOnProperty(name = "app.rabbitmq.enabled", havingValue = "true", matchIfMissing = false)
@Slf4j
public class RabbitMQConfig {

    // ========== EXCHANGE NAMES ==========
    public static final String EXCHANGE_DIRECT = "rentoza.direct";
    public static final String EXCHANGE_TOPIC = "rentoza.topic";
    public static final String EXCHANGE_DLQ = "rentoza.dlq";

    // ========== QUEUE NAMES ==========
    public static final String QUEUE_PHOTO_VALIDATION = "photo.validation";
    public static final String QUEUE_PHOTO_VALIDATION_DLQ = "photo.validation.dlq";
    public static final String QUEUE_CHECKOUT_SAGA = "checkout.saga";
    public static final String QUEUE_CHECKOUT_SAGA_DLQ = "checkout.saga.dlq";
    public static final String QUEUE_NOTIFICATION = "notification.async";

    // ========== ROUTING KEYS ==========
    public static final String ROUTING_PHOTO_VALIDATE = "photo.validate";
    public static final String ROUTING_PHOTO_VALIDATE_DLQ = "photo.validate.dlq";
    public static final String ROUTING_CHECKOUT_SAGA = "checkout.saga";
    public static final String ROUTING_CHECKOUT_SAGA_DLQ = "checkout.saga.dlq";
    public static final String ROUTING_NOTIFICATION = "notification.send";

    // ========== EXCHANGES ==========

    @Bean
    public DirectExchange directExchange() {
        return ExchangeBuilder.directExchange(EXCHANGE_DIRECT)
                .durable(true)
                .build();
    }

    @Bean
    public TopicExchange topicExchange() {
        return ExchangeBuilder.topicExchange(EXCHANGE_TOPIC)
                .durable(true)
                .build();
    }

    @Bean
    public DirectExchange dlqExchange() {
        return ExchangeBuilder.directExchange(EXCHANGE_DLQ)
                .durable(true)
                .build();
    }

    // ========== PHOTO VALIDATION QUEUES ==========

    @Bean
    public Queue photoValidationQueue() {
        return QueueBuilder.durable(QUEUE_PHOTO_VALIDATION)
                .withArgument("x-dead-letter-exchange", EXCHANGE_DLQ)
                .withArgument("x-dead-letter-routing-key", ROUTING_PHOTO_VALIDATE_DLQ)
                .withArgument("x-message-ttl", 300000)  // 5 minute TTL
                .build();
    }

    @Bean
    public Queue photoValidationDlqQueue() {
        return QueueBuilder.durable(QUEUE_PHOTO_VALIDATION_DLQ)
                .withArgument("x-message-ttl", 604800000)  // 7 day TTL for DLQ
                .build();
    }

    @Bean
    public Binding photoValidationBinding() {
        return BindingBuilder.bind(photoValidationQueue())
                .to(directExchange())
                .with(ROUTING_PHOTO_VALIDATE);
    }

    @Bean
    public Binding photoValidationDlqBinding() {
        return BindingBuilder.bind(photoValidationDlqQueue())
                .to(dlqExchange())
                .with(ROUTING_PHOTO_VALIDATE_DLQ);
    }

    // ========== CHECKOUT SAGA QUEUES ==========

    @Bean
    public Queue checkoutSagaQueue() {
        return QueueBuilder.durable(QUEUE_CHECKOUT_SAGA)
                .withArgument("x-dead-letter-exchange", EXCHANGE_DLQ)
                .withArgument("x-dead-letter-routing-key", ROUTING_CHECKOUT_SAGA_DLQ)
                .withArgument("x-message-ttl", 600000)  // 10 minute TTL
                .build();
    }

    @Bean
    public Queue checkoutSagaDlqQueue() {
        return QueueBuilder.durable(QUEUE_CHECKOUT_SAGA_DLQ)
                .withArgument("x-message-ttl", 604800000)  // 7 day TTL
                .build();
    }

    @Bean
    public Binding checkoutSagaBinding() {
        return BindingBuilder.bind(checkoutSagaQueue())
                .to(directExchange())
                .with(ROUTING_CHECKOUT_SAGA);
    }

    @Bean
    public Binding checkoutSagaDlqBinding() {
        return BindingBuilder.bind(checkoutSagaDlqQueue())
                .to(dlqExchange())
                .with(ROUTING_CHECKOUT_SAGA_DLQ);
    }

    // ========== NOTIFICATION QUEUES ==========

    @Bean
    public Queue notificationQueue() {
        return QueueBuilder.durable(QUEUE_NOTIFICATION)
                .withArgument("x-message-ttl", 3600000)  // 1 hour TTL
                .build();
    }

    @Bean
    public Binding notificationBinding() {
        return BindingBuilder.bind(notificationQueue())
                .to(directExchange())
                .with(ROUTING_NOTIFICATION);
    }

    // ========== MESSAGE CONVERTER ==========

    @Bean
    public MessageConverter jsonMessageConverter() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        return new Jackson2JsonMessageConverter(mapper);
    }

    // ========== RABBIT TEMPLATE ==========

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter());
        template.setExchange(EXCHANGE_DIRECT);
        
        // Confirm callback for message delivery
        template.setConfirmCallback((correlationData, ack, cause) -> {
            if (!ack) {
                log.error("[RabbitMQ] Message delivery failed: {}", cause);
            }
        });
        
        // Return callback for unroutable messages
        template.setReturnsCallback(returned -> {
            log.warn("[RabbitMQ] Message returned: exchange={}, routingKey={}, replyText={}",
                    returned.getExchange(),
                    returned.getRoutingKey(),
                    returned.getReplyText());
        });
        
        return template;
    }

    // ========== LISTENER CONTAINER FACTORY ==========

    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(jsonMessageConverter());
        factory.setConcurrentConsumers(2);
        factory.setMaxConcurrentConsumers(10);
        factory.setPrefetchCount(10);
        factory.setDefaultRequeueRejected(false);  // Send to DLQ instead of requeue
        
        // Retry configuration
        factory.setAdviceChain(
                org.springframework.retry.interceptor.RetryInterceptorBuilder
                        .stateless()
                        .maxAttempts(3)
                        .backOffOptions(1000, 2.0, 10000)  // 1s, 2s, 4s backoff
                        .build()
        );
        
        return factory;
    }

    /**
     * Listener factory for photo validation with higher concurrency.
     */
    @Bean
    public SimpleRabbitListenerContainerFactory photoValidationListenerFactory(
            ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(jsonMessageConverter());
        factory.setConcurrentConsumers(4);  // More consumers for photo processing
        factory.setMaxConcurrentConsumers(16);
        factory.setPrefetchCount(5);  // Lower prefetch for CPU-intensive work
        factory.setDefaultRequeueRejected(false);
        
        return factory;
    }

    /**
     * Listener factory for saga processing with single consumer per saga.
     */
    @Bean
    public SimpleRabbitListenerContainerFactory sagaListenerFactory(
            ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(jsonMessageConverter());
        factory.setConcurrentConsumers(1);  // Single consumer for ordering
        factory.setMaxConcurrentConsumers(4);
        factory.setPrefetchCount(1);  // Process one at a time
        factory.setDefaultRequeueRejected(false);
        
        return factory;
    }
}
