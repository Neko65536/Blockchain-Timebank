package org.example.timecoinweb.config;

import org.fisco.bcos.sdk.v3.BcosSDK;
import org.fisco.bcos.sdk.v3.client.Client;
import org.example.timecoinweb.signer.RemoteSigner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

@Configuration
@ConditionalOnProperty(name="blockchain.backend", havingValue="fisco")
public class FiscoConfig {
    @Bean(destroyMethod="stopAll")
    @ConditionalOnProperty(name="blockchain.enabled", havingValue="true")
    public BcosSDK bcosSDK(BlockchainProperties properties) throws Exception {
        if (!properties.getPrivateKey().isBlank()) throw new IllegalStateException("FISCO业务进程不得配置链签名私钥");
        if (!properties.isGovernanceEnabled()) throw new IllegalStateException("FISCO必须启用多签治理合约");
        if (!properties.getSignerAddress().matches("0x[0-9a-fA-F]{40}")
                || !properties.getContractAddress().matches("0x[0-9a-fA-F]{40}")) throw new IllegalStateException("必须配置合约和signer地址");
        return BcosSDK.build(properties.getFiscoConfig());
    }

    @Bean
    @ConditionalOnProperty(name="blockchain.enabled", havingValue="true")
    public Client fiscoClient(BcosSDK sdk, BlockchainProperties properties) {
        Client client = sdk.getClient(properties.getGroupId());
        if (client.getCryptoType()!=0 || client.isWASM()
                || !properties.getExpectedChainId().equals(client.getChainId()))
            throw new IllegalStateException("当前适配仅支持指定链的非国密EVM群组");
        return client;
    }

    @Bean
    @ConditionalOnProperty(name="blockchain.enabled", havingValue="true")
    public RemoteSigner remoteSigner(BlockchainProperties properties) {
        return new RemoteSigner(properties.getSignerUrl(), properties.getSignerToken());
    }
}
