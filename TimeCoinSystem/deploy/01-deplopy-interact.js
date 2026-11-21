const { network } = require("hardhat");

module.exports = async ({ getNamedAccounts, deployments }) => {
  const { deploy, log } = deployments;
  const { deployer } = await getNamedAccounts();
  const chainId = network.config.chainId;

  const required = name => {
    const value = process.env[name];
    if (!value) throw new Error(`Deployment requires ${name}`);
    return value;
  };
  const limits = ['BLOCKCHAIN_MAX_SUPPLY','BLOCKCHAIN_LIFETIME_MINT_THRESHOLD',
    'BLOCKCHAIN_GLOBAL_DAILY_MINT_LIMIT','BLOCKCHAIN_ACCOUNT_DAILY_MINT_LIMIT'].map(name => {
    const value=required(name);
    if (!/^[1-9][0-9]*$/.test(value)) throw new Error(`${name} must be a positive integer`);
    return value;
  });
  limits.push('1'); // 此脚本仅部署Ethereum；FISCO部署构造参数须为1000。
  const legacy = process.env.DEPLOY_LEGACY_TIMECOIN === 'true';
  const contract = legacy ? 'TimeCoin' : 'GovernedTimeCoin';
  const args = legacy ? [limits] : [required('GOVERNANCE_OPERATOR'), required('GOVERNANCE_DOMAIN'),
    required('GOVERNANCE_APPROVERS').split(',').map(s=>s.trim()), required('GOVERNANCE_THRESHOLD'), limits];
  const timeCoin = await deploy(contract, {
    from: deployer,
    args,
    log: true,
  });

  log("------------------------------------------");
};

module.exports.tags = ["all", "TimeCoin", "GovernedTimeCoin"];
