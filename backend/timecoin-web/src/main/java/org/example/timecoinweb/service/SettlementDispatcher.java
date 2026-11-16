package org.example.timecoinweb.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.timecoinweb.config.SettlementMessaging;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "settlement.mq-enabled", havingValue = "true")
public class SettlementDispatcher {
    private final JdbcTemplate jdbc;
    private final RabbitTemplate rabbit;
    private final SettlementService settlements;

    /** confirm仅表示broker接收；未完成订单仍定期重投，覆盖发布后崩溃、broker恢复等窗口。 */
    @Scheduled(fixedDelayString = "${settlement.dispatch-ms:1000}", initialDelayString = "${settlement.dispatch-initial-ms:1000}")
    public void dispatch() {
        List<String> ids = jdbc.queryForList("select o.order_id from settlement_outbox o join settlement_order s on s.id=o.order_id "
                + "where o.next_send<=CURRENT_TIMESTAMP and ((s.state in ('PENDING','RETRY') and s.next_attempt<=CURRENT_TIMESTAMP) "
                + "or (s.state='PROCESSING' and s.lease_until<CURRENT_TIMESTAMP)) order by o.next_send limit 10", String.class);
        for (String id : ids) {
            try {
                publish(id);
                jdbc.update("update settlement_outbox set confirmed_at=CURRENT_TIMESTAMP,send_count=send_count+1,next_send=? where order_id=?",
                        Timestamp.from(Instant.now().plusSeconds(5)), id);
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                log.warn("结算消息尚未确认，outbox保留重试 orderId={}", id);
                // 停止本轮，避免broker故障期间每笔订单都阻塞调度线程。
                break;
            }
        }
    }

    public void publish(String id) throws Exception {
        CorrelationData correlation = new CorrelationData(UUID.randomUUID().toString());
        rabbit.convertAndSend(SettlementMessaging.EXCHANGE, SettlementMessaging.ROUTING_KEY, id, message -> {
            message.getMessageProperties().setMessageId(id);
            message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            return message;
        }, correlation);
        CorrelationData.Confirm confirmed = correlation.getFuture().get(3, TimeUnit.SECONDS);
        if (!confirmed.isAck() || correlation.getReturned() != null) throw new IllegalStateException("结算消息未可靠路由");
    }

    @RabbitListener(queues = SettlementMessaging.QUEUE, concurrency = "1")
    public void consume(Message message) {
        String id = new String(message.getBody(), StandardCharsets.UTF_8);
        try { UUID.fromString(id); }
        catch (IllegalArgumentException invalid) { throw new AmqpRejectAndDontRequeueException("无效结算编号"); }
        // AUTO ack在方法返回后发生。数据库不可用时抛异常进入DLQ，outbox仍会重新投递。
        settlements.process(id);
    }
}
