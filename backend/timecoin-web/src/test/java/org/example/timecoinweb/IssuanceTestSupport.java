package org.example.timecoinweb;

import org.example.timecoinweb.config.BlockchainProperties;
import org.web3j.abi.datatypes.*;
import org.web3j.abi.datatypes.generated.*;
import org.web3j.crypto.Hash;
import org.web3j.utils.Numeric;
import java.math.BigInteger;
import java.time.*;
import java.util.*;

/** 仅测试链视图替身；真实合约约束另由Hardhat/FISCO测试验证。 */
final class IssuanceTestSupport {
    static void configure(BlockchainProperties p){
        p.setMaxSupply(BigInteger.valueOf(20000000));p.setLifetimeMintThreshold(BigInteger.valueOf(100000));
        p.setGlobalDailyMintLimit(BigInteger.valueOf(100000));p.setAccountDailyMintLimit(BigInteger.valueOf(1440));
    }
    static List<Type> view(Function f,BlockchainProperties p){
        return view(f,p,LocalDate.now(ZoneId.of("Asia/Shanghai")).toEpochDay());
    }
    static List<Type> view(Function f,BlockchainProperties p,long day){
        if(f.getName().equals("issuancePolicyHash"))return List.of(new Bytes32(Numeric.hexStringToByteArray(Hash.sha3String("test-issuance-policy"))));
        BigInteger value=switch(f.getName()){
            case "MAX_SUPPLY" -> p.getMaxSupply();case "lifetimeMintThreshold" -> p.getLifetimeMintThreshold();
            case "globalDailyLimit" -> p.getGlobalDailyMintLimit();case "accountDailyLimit" -> p.getAccountDailyMintLimit();
            case "timestampDivisor" -> BigInteger.valueOf(p.isFisco()?1000:1);
            case "currentDayIndex" -> BigInteger.valueOf(day);
            case "totalSupply","lifetimeMinted","globalDailyMinted","accountDailyMinted" -> BigInteger.ZERO;
            default -> throw new IllegalArgumentException("Unexpected view: "+f.getName());
        };
        return List.of(new Uint256(value));
    }
}
