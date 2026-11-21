// SPDX-License-Identifier: MIT
pragma solidity ^0.8.0;

/// @notice 1币=1分钟；累计阈值只允许治理合约经显式复核跨越，硬额度不可豁免。
abstract contract IssuanceLimits {
    uint256 public immutable MAX_SUPPLY;
    uint256 public immutable lifetimeMintThreshold;
    uint256 public immutable globalDailyLimit;
    uint256 public immutable accountDailyLimit;
    uint256 public immutable timestampDivisor;
    uint256 public totalSupply;
    mapping(string => uint256) public lifetimeMinted;
    mapping(uint256 => uint256) public globalDailyMinted;
    mapping(string => mapping(uint256 => uint256)) public accountDailyMinted;
    event LifetimeMintReviewed(string account, uint256 previousMinted, uint256 amount);

    // [总量,累计复核阈值,全局每日,账户每日,时间戳单位(秒=1/FISCO毫秒=1000)]
    constructor(uint256[5] memory limits) {
        require(limits[0]>0 && limits[0]<=uint256(uint64(type(int64).max)), "invalid supply cap");
        require(limits[1]>0 && limits[1]<=limits[0] && limits[2]>0 && limits[2]<=limits[0]
            && limits[3]>0 && limits[3]<=limits[2], "invalid issuance limits");
        require(limits[4]==1 || limits[4]==1000, "invalid timestamp divisor");
        MAX_SUPPLY=limits[0];lifetimeMintThreshold=limits[1];globalDailyLimit=limits[2];
        accountDailyLimit=limits[3];timestampDivisor=limits[4];
    }

    function dayIndexAt(uint256 timestamp) public view returns (uint256) {
        return (timestamp/timestampDivisor+8 hours)/1 days;
    }
    function currentDayIndex() public view returns (uint256) { return dayIndexAt(block.timestamp); }
    function issuancePolicyHash() public view returns (bytes32) {
        return keccak256(abi.encode(MAX_SUPPLY,lifetimeMintThreshold,globalDailyLimit,accountDailyLimit,timestampDivisor));
    }
    function _consumeMint(string memory account,uint256 amount,bool reviewed) internal {
        require(bytes(account).length>0 && bytes(account).length<=64, "invalid account");
        require(amount>0 && amount<=MAX_SUPPLY-totalSupply, "supply cap exceeded");
        uint256 day=currentDayIndex();
        require(amount<=globalDailyLimit-globalDailyMinted[day], "global daily cap exceeded");
        require(amount<=accountDailyLimit-accountDailyMinted[account][day], "account daily cap exceeded");
        uint256 previous=lifetimeMinted[account];
        if(previous+amount>lifetimeMintThreshold) {
            require(reviewed, "lifetime review required");
            emit LifetimeMintReviewed(account,previous,amount);
        }
        totalSupply+=amount;lifetimeMinted[account]=previous+amount;
        globalDailyMinted[day]+=amount;accountDailyMinted[account][day]+=amount;
    }
}
