package org.example.timecoinweb.rag;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Configuration
@lombok.extern.slf4j.Slf4j
@ConditionalOnProperty(name="ai.rag.mq-enabled",havingValue="true")
public class RagMessaging {
    private final JdbcTemplate jdbc;
    private final RabbitTemplate rabbit;
    private final KnowledgeService knowledge;
    public RagMessaging(JdbcTemplate jdbc,RabbitTemplate rabbit,KnowledgeService knowledge) { this.jdbc=jdbc;this.rabbit=rabbit;this.knowledge=knowledge; }
    @Bean
    Declarables ragTopology() {
        DirectExchange exchange=new DirectExchange("timebank.rag",true,false), dead=new DirectExchange("timebank.rag.dead",true,false);
        Queue work=QueueBuilder.durable("timebank.rag.work").deadLetterExchange("timebank.rag.dead").deadLetterRoutingKey("dead").build();
        Queue deadQueue=QueueBuilder.durable("timebank.rag.work.dead").build();
        return new Declarables(exchange,dead,work,deadQueue,BindingBuilder.bind(work).to(exchange).with("index"),BindingBuilder.bind(deadQueue).to(dead).with("dead"));
    }
    @Scheduled(fixedDelayString="${ai.rag.dispatch-ms:2000}",initialDelayString="${ai.rag.dispatch-initial-ms:5000}")
    public void dispatch() {
        for(String id:jdbc.queryForList("select o.document_id from rag_outbox o join rag_document d on d.id=o.document_id where o.next_send<=CURRENT_TIMESTAMP and "
                + "((d.state in ('PENDING','RETRY') and d.next_attempt<=CURRENT_TIMESTAMP) or (d.state='PROCESSING' and d.lease_until<CURRENT_TIMESTAMP)) order by o.next_send limit 10",String.class)) {
            try {
                CorrelationData correlation=new CorrelationData(UUID.randomUUID().toString());
                rabbit.convertAndSend("timebank.rag","index",id,message -> {
                    message.getMessageProperties().setMessageId(id); message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);return message;
                },correlation);
                if(!correlation.getFuture().get(3,TimeUnit.SECONDS).isAck() || correlation.getReturned()!=null) throw new IllegalStateException("知识消息未可靠路由");
                jdbc.update("update rag_outbox set confirmed_at=CURRENT_TIMESTAMP,send_count=send_count+1,next_send=? where document_id=?",Timestamp.from(Instant.now().plusSeconds(10)),id);
            } catch(Exception failed) {
                if(failed instanceof InterruptedException) Thread.currentThread().interrupt();
                log.warn("知识消息尚未确认，outbox保留重试 documentId={}",id); break;
            }
        }
    }
    @RabbitListener(queues="timebank.rag.work",concurrency="1")
    public void consume(String id) {
        try { UUID.fromString(id); } catch(Exception invalid) { throw new org.springframework.amqp.AmqpRejectAndDontRequeueException("无效知识文档编号"); }
        knowledge.process(id);
    }
}
