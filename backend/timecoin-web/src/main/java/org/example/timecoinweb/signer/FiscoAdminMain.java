package org.example.timecoinweb.signer;

import tools.jackson.databind.ObjectMapper;
import org.fisco.bcos.sdk.v3.BcosSDK;
import org.fisco.bcos.sdk.v3.client.Client;
import org.fisco.bcos.sdk.v3.crypto.CryptoSuite;
import org.fisco.bcos.sdk.v3.crypto.keypair.CryptoKeyPair;
import org.fisco.bcos.sdk.jni.utilities.tx.TransactionBuilderJniObj;
import org.fisco.bcos.sdk.v3.contract.auth.manager.AuthManager;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.datatypes.*;
import org.web3j.abi.datatypes.generated.*;
import org.web3j.crypto.Hash;
import org.web3j.utils.Numeric;
import java.nio.file.*;
import java.util.*;

/** 离线运维入口：部署密钥与业务signer分离。不会修改数据库中的旧合约/凭据。 */
public final class FiscoAdminMain {
    private static String required(String name) {String value=System.getenv(name);if(value==null || value.isBlank()) throw new IllegalArgumentException("缺少环境变量："+name);return value;}
    public static void main(String[] args) throws Exception {
        if(args.length!=1 || !List.of("deploy","observer").contains(args[0])) throw new IllegalArgumentException("用法：deploy或observer");
        BcosSDK sdk=BcosSDK.build(required("FISCO_CONFIG"));
        try {
            Client client=sdk.getClient(required("FISCO_GROUP_ID"));
            if(client.getCryptoType()!=0 || client.isWASM() || !required("FISCO_CHAIN_ID").equals(client.getChainId())) throw new IllegalStateException("链或加密模式不匹配");
            CryptoKeyPair deployer=new CryptoSuite(0).getKeyPairFactory().createKeyPair(Files.readString(Path.of(required("FISCO_DEPLOY_KEY_FILE"))).trim());
            if("observer".equals(args[0])) {
                String node=Files.readString(Path.of(required("FISCO_OBSERVER_NODEID_FILE"))).trim();
                if(!node.matches("[0-9a-fA-F]{128}")) throw new IllegalArgumentException("节点ID不合法");
                AuthManager governance=new AuthManager(client,deployer);
                java.math.BigInteger proposal=governance.createSetConsensusWeightProposal(node,java.math.BigInteger.ZERO,true);
                System.out.println("Consensus proposal: "+proposal+"; status: "+governance.getProposalInfo(proposal).getStatusString());
                if(!client.getObserverList().getObserverList().contains(node)) throw new IllegalStateException("提案尚未完成，须其他链治理委员投票；勿当作节点已切换");
                System.out.println("Observer configured: "+node);return;
            }
            ObjectMapper json=new ObjectMapper();
            String binary=json.readTree(Files.readString(Path.of(required("FISCO_CONTRACT_ARTIFACT")))).path("bytecode").asText();
            if(!binary.matches("0x[0-9a-fA-F]+")) throw new IllegalArgumentException("合约bytecode无效，请先编译");
            String operator=required("SIGNER_ADDRESS");
            List<Address> approvers=new ArrayList<>();for(String address:required("GOVERNANCE_APPROVERS").split(",")) approvers.add(new Address(address.trim()));
            int threshold=Integer.parseInt(required("GOVERNANCE_THRESHOLD"));
            String domain=Hash.sha3String(client.getChainId()+":"+client.getGroup()+":"+required("GOVERNANCE_DEPLOYMENT_ID"));
            // 发行政策必须与后端 runtime 配置一致（对齐 BLOCKCHAIN_* 环境变量）；timestampDivisor 固定 1000（FISCO EVM 时间单位为毫秒）。
            java.math.BigInteger[] issuance=new java.math.BigInteger[]{
                new java.math.BigInteger(required("BLOCKCHAIN_MAX_SUPPLY")),
                new java.math.BigInteger(required("BLOCKCHAIN_LIFETIME_MINT_THRESHOLD")),
                new java.math.BigInteger(required("BLOCKCHAIN_GLOBAL_DAILY_MINT_LIMIT")),
                new java.math.BigInteger(required("BLOCKCHAIN_ACCOUNT_DAILY_MINT_LIMIT"))};
            for(java.math.BigInteger limit:issuance)if(limit.signum()<=0 || limit.bitLength()>63)
                throw new IllegalArgumentException("四个发行额度必须为正且不超过int64");
            if(issuance[1].compareTo(issuance[0])>0 || issuance[2].compareTo(issuance[0])>0 || issuance[3].compareTo(issuance[2])>0)
                throw new IllegalArgumentException("发行额度关系不合法");
            String constructor=FunctionEncoder.encodeConstructor(List.of(new Address(operator),new Bytes32(Numeric.hexStringToByteArray(domain)),new DynamicArray<>(Address.class,approvers),new Uint256(threshold),
                new StaticArray5<>(Uint256.class,List.of(new Uint256(issuance[0]),new Uint256(issuance[1]),new Uint256(issuance[2]),new Uint256(issuance[3]),new Uint256(1000)))));
            var signed=TransactionBuilderJniObj.createSignedTransaction(deployer.getJniKeyPair(),client.getGroup(),client.getChainId(),"",Numeric.cleanHexPrefix(binary)+constructor,"",client.getBlockLimit().longValueExact(),0);
            // 部署同样先保存原凭据；结果不明时依据记录查询，不能盲目重复部署。
            Path evidence=Path.of(required("FISCO_DEPLOY_RECEIPT_FILE"));
            Files.writeString(evidence,json.writeValueAsString(Map.of("txHash",signed.getTxHash(),"rawTx",signed.getSignedTx(),"domain",domain,"operator",operator)),StandardOpenOption.CREATE_NEW);
            var receipt=client.sendTransaction(signed.getSignedTx(),false).getTransactionReceipt();
            if(receipt==null || receipt.getStatus()!=0) throw new IllegalStateException("部署未确认，请核查原凭据");
            System.out.println("Contract: "+receipt.getContractAddress()+"; txHash: "+signed.getTxHash()+"; domain: "+domain);
        } finally {sdk.stopAll();}
    }
}
