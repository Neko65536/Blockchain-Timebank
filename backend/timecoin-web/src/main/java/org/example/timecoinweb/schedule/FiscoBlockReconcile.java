package org.example.timecoinweb.schedule;

import org.example.timecoinweb.config.BlockchainProperties;
import org.example.timecoinweb.service.CoinBalanceLedgerService;
import org.fisco.bcos.sdk.v3.BcosSDK;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.stereotype.Component;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.*;
import jakarta.annotation.PreDestroy;

/** 区块通知只唤醒独立对账线程；合并重复通知，定时任务继续补偿断线期间的遗漏。 */
@Component
@ConditionalOnProperty(name="blockchain.backend",havingValue="fisco")
@ConditionalOnExpression("${blockchain.enabled:false}")
public class FiscoBlockReconcile implements ApplicationRunner {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(FiscoBlockReconcile.class);
    private final BcosSDK sdk;
    private final BlockchainProperties properties;
    private final CoinBalanceLedgerService ledger;
    private final AtomicBoolean pending=new AtomicBoolean();
    private final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor();
    public FiscoBlockReconcile(BcosSDK sdk,BlockchainProperties properties,CoinBalanceLedgerService ledger) {this.sdk=sdk;this.properties=properties;this.ledger=ledger;}
    @Override public void run(ApplicationArguments arguments) {
        sdk.registerBlockNotifier(properties.getGroupId(),(group,height)->pending.set(true));
        worker.scheduleWithFixedDelay(()->{
            if(properties.isReconcileEnabled() && pending.getAndSet(false)) {
                try {ledger.reconcileAll(false);} catch(Exception failure) {pending.set(true);log.warn("FISCO区块通知对账失败，保留定时补偿",failure);}
            }
        },5,5,TimeUnit.SECONDS);
    }
    @PreDestroy public void close() {worker.shutdownNow();}
}
