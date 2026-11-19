package org.example.timecoinweb.signer;

import tools.jackson.databind.JsonNode;
import org.fisco.bcos.sdk.jni.utilities.tx.TransactionBuilderJniObj;
import org.fisco.bcos.sdk.jni.utilities.tx.TxPair;
import org.fisco.bcos.sdk.v3.crypto.CryptoSuite;
import org.fisco.bcos.sdk.v3.crypto.keypair.CryptoKeyPair;
import java.util.*;

/** 独立进程的签名策略：只允许指定链、群组、合约与函数，不提供任意摘要签名。 */
public final class SignerEngine {
    private final CryptoKeyPair key;
    private final String chain,group,contract;
    private final Set<String> selectors;
    public SignerEngine(String privateKey,String chain,String group,String contract,Set<String> selectors) {
        if(!contract.matches("0x[0-9a-fA-F]{40}") || chain.isBlank() || group.isBlank() || selectors.isEmpty()) throw new IllegalArgumentException("签名策略不完整");
        for(String selector:selectors) if(!selector.matches("[0-9a-f]{8}")) throw new IllegalArgumentException("函数选择器必须是8位小写hex");
        this.key=new CryptoSuite(0).getKeyPairFactory().createKeyPair(privateKey);
        this.chain=chain;this.group=group;this.contract=contract.toLowerCase(Locale.ROOT);this.selectors=Set.copyOf(selectors);
    }
    public Map<String,Object> sign(JsonNode request) throws org.fisco.bcos.sdk.jni.common.JniException {
        String input=request.path("input").asText("").toLowerCase(Locale.ROOT).replaceFirst("^0x","");
        long limit=request.path("blockLimit").asLong(-1);
        if(!chain.equals(request.path("chainId").asText()) || !group.equals(request.path("groupId").asText())
                || !contract.equals(request.path("to").asText().toLowerCase(Locale.ROOT))
                || !input.matches("[0-9a-f]{8,16384}") || input.length()%2!=0 || !selectors.contains(input.substring(0,8)) || limit<=0)
            throw new IllegalArgumentException("签名请求不符合允许策略");
        TxPair signed=TransactionBuilderJniObj.createSignedTransaction(key.getJniKeyPair(),group,chain,contract,input,"",limit,0);
        if(signed==null || signed.getSignedTx()==null || signed.getTxHash()==null) throw new IllegalStateException("签名未完成");
        return Map.of("txHash",signed.getTxHash(),"rawTx",signed.getSignedTx(),"signer",key.getAddress());
    }
}
