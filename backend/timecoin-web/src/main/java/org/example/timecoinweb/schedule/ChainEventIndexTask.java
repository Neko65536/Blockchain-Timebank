package org.example.timecoinweb.schedule;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.timecoinweb.config.BlockchainProperties;
import org.example.timecoinweb.service.ChainEventIndexService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** N5：后台定时同步链上事件到离线索引，用户查询只读索引。 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "blockchain.enabled", havingValue = "true")
public class ChainEventIndexTask {
    private final ChainEventIndexService index;
    private final BlockchainProperties props;

    @Scheduled(fixedDelayString = "${blockchain.event-index-ms:300000}", initialDelayString = "${blockchain.event-index-ms:300000}")
    public void sync() {
        if (!props.isEventIndexEnabled()) return;
        int added = index.sync();
        if (added > 0) log.info("链上事件索引新增 {} 条", added);
    }
}
