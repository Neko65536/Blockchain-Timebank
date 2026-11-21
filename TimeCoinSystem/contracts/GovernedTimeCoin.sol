// SPDX-License-Identifier: MIT
pragma solidity ^0.8.0;
import "./IssuanceLimits.sol";

/// @notice 非国密EVM部署。业务服务仅持有operator调用能力，审批密钥分别由审批人保管。
contract GovernedTimeCoin is IssuanceLimits {
    address public immutable operator;
    bytes32 public immutable domain;
    uint256 public immutable threshold;
    mapping(address => bool) public isApprover;
    mapping(bytes32 => bool) public executed;
    mapping(string => bool) public frozen;
    mapping(string => uint256) private balances;
    event Mint(string userId, uint256 amount);
    event Transfer(string fromUserId, string toUserId, uint256 amount);
    event Freeze(string userId, bool frozen);
    event ProposalExecuted(bytes32 indexed id, uint8 action);

    constructor(address executor, bytes32 deploymentDomain, address[] memory approvers, uint256 required, uint256[5] memory limits) IssuanceLimits(limits) {
        require(executor != address(0) && deploymentDomain != bytes32(0), "invalid deployment");
        require(required >= 2 && required <= approvers.length && approvers.length <= 10, "invalid threshold");
        operator = executor; domain = deploymentDomain; threshold = required;
        for (uint256 i = 0; i < approvers.length; i++) {
            require(approvers[i] != address(0) && approvers[i] != executor && !isApprover[approvers[i]], "invalid approver");
            isApprover[approvers[i]] = true;
        }
    }

    modifier onlyOperator() { require(msg.sender == operator, "not operator"); _; }

    function balanceOf(string calldata userId) external view returns (uint256) { return balances[userId]; }
    // FISCO3.8的EVM时间单位为毫秒；以太坊为秒，部署后可直接核验。
    function currentTime() external view returns (uint256) { return block.timestamp; }

    /// @notice 审批人对返回的32字节摘要使用personal_sign；绑定部署域、合约、提案及全部业务参数。
    function proposalDigest(bytes32 id, uint8 action, string memory userId, uint256 value, uint256 deadline) public view returns (bytes32) {
        return keccak256(abi.encode(domain, address(this), id, action, keccak256(bytes(userId)), value, deadline, issuancePolicyHash()));
    }

    function execute(bytes32 id, uint8 action, string calldata userId, uint256 value, uint256 deadline, bytes[] calldata signatures) external onlyOperator {
        require(!executed[id] && id != bytes32(0), "proposal used");
        require(block.timestamp <= deadline, "proposal expired");
        require(bytes(userId).length > 0 && bytes(userId).length <= 64, "invalid account");
        require(signatures.length == threshold, "approval count");
        require(((action == 1 || action == 3) && value > 0 && value <= uint256(uint64(type(int64).max))) || (action == 2 && value <= 1), "invalid action");
        if(action==1 || action==3) require(dayIndexAt(deadline)==currentDayIndex(), "mint day mismatch");
        bytes32 digest = keccak256(abi.encodePacked("\x19Ethereum Signed Message:\n32", proposalDigest(id, action, userId, value, deadline)));
        address previous = address(0);
        for (uint256 i = 0; i < signatures.length; i++) {
            address approver = recover(digest, signatures[i]);
            require(isApprover[approver] && approver > previous, "invalid approvals");
            previous = approver;
        }
        executed[id] = true;
        if (action == 1 || action == 3) {
            require(!frozen[userId], "account frozen");
            _consumeMint(userId,value,action==3);
            balances[userId] += value;
            emit Mint(userId, value);
        } else {
            frozen[userId] = value == 1;
            emit Freeze(userId, value == 1);
        }
        emit ProposalExecuted(id, action);
    }

    function transfer(string calldata fromUserId, string calldata toUserId, uint256 amount) external onlyOperator {
        require(bytes(fromUserId).length > 0 && bytes(toUserId).length > 0 && bytes(fromUserId).length <= 64 && bytes(toUserId).length <= 64, "invalid account");
        require(keccak256(bytes(fromUserId)) != keccak256(bytes(toUserId)), "same account");
        require(amount > 0 && balances[fromUserId] >= amount, "invalid amount");
        require(!frozen[fromUserId] && !frozen[toUserId], "account frozen");
        balances[fromUserId] -= amount; balances[toUserId] += amount;
        emit Transfer(fromUserId, toUserId, amount);
    }

    function recover(bytes32 digest, bytes calldata signature) private pure returns (address) {
        require(signature.length == 65, "invalid signature");
        bytes32 r; bytes32 s; uint8 v;
        assembly { r := calldataload(signature.offset) s := calldataload(add(signature.offset, 32)) v := byte(0, calldataload(add(signature.offset, 64))) }
        require(uint256(s) <= 0x7fffffffffffffffffffffffffffffff5d576e7357a4501ddfe92f46681b20a0 && (v == 27 || v == 28), "noncanonical signature");
        address signer = ecrecover(digest, v, r, s);
        require(signer != address(0), "invalid signature");
        return signer;
    }
}
