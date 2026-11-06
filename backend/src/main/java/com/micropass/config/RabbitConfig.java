package com.micropass.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

    public static final String DEPLOYMENT_EXCHANGE = "micropass.deployment.exchange";
    public static final String DEPLOYMENT_QUEUE = "micropass.deployment.queue";
    public static final String DEPLOYMENT_ROUTING_KEY = "micropass.deployment.trigger";
    public static final String DEPLOYMENT_DLX = "micropass.deployment.dlx";
    public static final String DEPLOYMENT_DLQ = "micropass.deployment.dlq";
    public static final String DEPLOYMENT_DEAD_ROUTING_KEY = "micropass.deployment.dead";

    @Bean
    public DirectExchange deploymentExchange() {
        return new DirectExchange(DEPLOYMENT_EXCHANGE, true, false);
    }

    @Bean
    public Queue deploymentQueue() {
        return QueueBuilder.durable(DEPLOYMENT_QUEUE)
                .deadLetterExchange(DEPLOYMENT_DLX)
                .deadLetterRoutingKey(DEPLOYMENT_DEAD_ROUTING_KEY)
                .build();
    }

    @Bean
    public Binding deploymentBinding(Queue deploymentQueue, DirectExchange deploymentExchange) {
        return BindingBuilder.bind(deploymentQueue)
                .to(deploymentExchange)
                .with(DEPLOYMENT_ROUTING_KEY);
    }

    @Bean
    public DirectExchange deploymentDeadLetterExchange() {
        return new DirectExchange(DEPLOYMENT_DLX, true, false);
    }

    @Bean
    public Queue deploymentDeadLetterQueue() {
        return QueueBuilder.durable(DEPLOYMENT_DLQ).build();
    }

    @Bean
    public Binding deploymentDeadLetterBinding(
            Queue deploymentDeadLetterQueue,
            DirectExchange deploymentDeadLetterExchange) {
        return BindingBuilder.bind(deploymentDeadLetterQueue)
                .to(deploymentDeadLetterExchange)
                .with(DEPLOYMENT_DEAD_ROUTING_KEY);
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
