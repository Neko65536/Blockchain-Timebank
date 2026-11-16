package org.example.timecoinweb.config;

import org.springframework.amqp.core.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "settlement.mq-enabled", havingValue = "true")
public class SettlementMessaging {
    public static final String EXCHANGE = "timebank.settlement";
    public static final String QUEUE = "timebank.settlement.work";
    public static final String ROUTING_KEY = "settle";
    @Bean
    Declarables settlementTopology() {
        DirectExchange exchange = new DirectExchange(EXCHANGE, true, false);
        DirectExchange dead = new DirectExchange(EXCHANGE + ".dead", true, false);
        Queue work = QueueBuilder.durable(QUEUE).singleActiveConsumer()
                .deadLetterExchange(dead.getName()).deadLetterRoutingKey("dead").build();
        Queue deadQueue = QueueBuilder.durable(QUEUE + ".dead").build();
        return new Declarables(exchange, dead, work, deadQueue,
                BindingBuilder.bind(work).to(exchange).with(ROUTING_KEY),
                BindingBuilder.bind(deadQueue).to(dead).with("dead"));
    }
}
