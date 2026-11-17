const { expect } = require('chai');
const { ethers } = require('hardhat');
const { time } = require('@nomicfoundation/hardhat-network-helpers');

describe('GovernedTimeCoin', function () {
  let coin, operator, a, b, c, outsider, deadline;
  beforeEach(async function () {
    [operator,a,b,c,outsider] = await ethers.getSigners();
    coin = await (await ethers.getContractFactory('GovernedTimeCoin')).deploy(operator.address,ethers.id('test-chain:group0'),[a.address,b.address,c.address],2);
    deadline = (await time.latest()) + 3600;
  });
  async function signatures(id,action,user,value,signers=[a,b],target=coin) {
    const digest=await target.proposalDigest(id,action,user,value,deadline);
    const entries=await Promise.all(signers.map(async s=>({address:s.address.toLowerCase(),signature:await s.signMessage(ethers.getBytes(digest))})));
    return entries.sort((x,y)=>x.address.localeCompare(y.address)).map(x=>x.signature);
  }
  async function execute(label,action,user,value) {
    const id=ethers.id(label);return coin.execute(id,action,user,value,deadline,await signatures(id,action,user,value));
  }
  it('requires independent approvals and emits mint/transfer events', async function () {
    await expect(await execute('mint',1,'1',50)).to.emit(coin,'Mint').withArgs('1',50);
    await expect(coin.transfer('1','2',12)).to.emit(coin,'Transfer').withArgs('1','2',12);
    expect(await coin.balanceOf('1')).to.equal(38);expect(await coin.balanceOf('2')).to.equal(12);
  });
  it('rejects unauthorized callers, excess, zero and self transfers', async function () {
    await execute('mint',1,'1',50);
    await expect(coin.connect(outsider).transfer('1','2',1)).to.be.revertedWith('not operator');
    await expect(coin.transfer('1','2',51)).to.be.revertedWith('invalid amount');
    await expect(coin.transfer('1','2',0)).to.be.revertedWith('invalid amount');
    await expect(coin.transfer('1','1',1)).to.be.revertedWith('same account');
    await expect(coin.transfer('','2',1)).to.be.revertedWith('invalid account');
  });
  it('freezes mint and both transfer directions and supports approved unfreeze', async function () {
    await execute('mint',1,'1',50);await execute('mint2',1,'2',50);
    await expect(await execute('freeze',2,'1',1)).to.emit(coin,'Freeze').withArgs('1',true);
    await expect(coin.transfer('1','2',1)).to.be.revertedWith('account frozen');
    await expect(coin.transfer('2','1',1)).to.be.revertedWith('account frozen');
    await expect(execute('mint3',1,'1',1)).to.be.revertedWith('account frozen');
    await execute('unfreeze',2,'1',0);await coin.transfer('1','2',1);
  });
  it('rejects duplicate approvers, insufficient approvals, nonmembers and parameter tampering', async function () {
    const id=ethers.id('proposal');
    await expect(coin.execute(id,1,'1',5,deadline,await signatures(id,1,'1',5,[a,a]))).to.be.revertedWith('invalid approvals');
    await expect(coin.execute(id,1,'1',5,deadline,await signatures(id,1,'1',5,[a]))).to.be.revertedWith('approval count');
    await expect(coin.execute(id,1,'1',5,deadline,await signatures(id,1,'1',5,[a,outsider]))).to.be.revertedWith('invalid approvals');
    await expect(coin.execute(id,1,'2',5,deadline,await signatures(id,1,'1',5))).to.be.revertedWith('invalid approvals');
  });
  it('rejects replay, expired proposals, invalid amounts and cross-contract signatures', async function () {
    const id=ethers.id('proposal'), signed=await signatures(id,1,'1',5);
    await coin.execute(id,1,'1',5,deadline,signed);
    await expect(coin.execute(id,1,'1',5,deadline,signed)).to.be.revertedWith('proposal used');
    const other=await (await ethers.getContractFactory('GovernedTimeCoin')).deploy(operator.address,ethers.id('test-chain:group0'),[a.address,b.address,c.address],2);
    await expect(other.execute(id,1,'1',5,deadline,signed)).to.be.revertedWith('invalid approvals');
    await expect(execute('zero',1,'1',0)).to.be.revertedWith('invalid action');
    await expect(execute('large',1,'1',2n**63n)).to.be.revertedWith('invalid action');
    await time.increaseTo(deadline+1);
    await expect(execute('late',1,'1',5)).to.be.revertedWith('proposal expired');
  });
  it('rejects duplicate and operator approvers at deployment', async function () {
    const factory=await ethers.getContractFactory('GovernedTimeCoin');
    await expect(factory.deploy(operator.address,ethers.id('domain'),[a.address,a.address],2)).to.be.revertedWith('invalid approver');
    await expect(factory.deploy(operator.address,ethers.id('domain'),[a.address,operator.address],2)).to.be.revertedWith('invalid approver');
  });
});
