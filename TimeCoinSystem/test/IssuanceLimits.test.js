const { expect } = require('chai');
const { ethers } = require('hardhat');
const { time } = require('@nomicfoundation/hardhat-network-helpers');

describe('J1 issuance invariants', function () {
  let operator,a,b,coin;
  const limits=[1000,150,200,100,1];
  beforeEach(async function () {
    [operator,a,b]=await ethers.getSigners();
    coin=await (await ethers.getContractFactory('GovernedTimeCoin')).deploy(operator.address,ethers.id('quota-test'),[a.address,b.address],2,limits);
  });
  async function proposal(label,account,amount,action=1,deadline) {
    const id=ethers.id(label);
    deadline=deadline || (Math.floor(((await time.latest())+28800)/86400)+1)*86400-28801;
    const digest=await coin.proposalDigest(id,action,account,amount,deadline);
    const signers=[a,b].sort((x,y)=>x.address.toLowerCase().localeCompare(y.address.toLowerCase()));
    const signatures=await Promise.all(signers.map(s=>s.signMessage(ethers.getBytes(digest))));
    return [id,action,account,amount,deadline,signatures];
  }
  async function mint(label,user,amount,action=1){return coin.execute(...await proposal(label,user,amount,action));}
  async function nextDay(){await time.increaseTo((Math.floor(((await time.latest())+28800)/86400)+1)*86400-28800);}

  it('bounds owner-only legacy mint as well as governed mint',async function () {
    const legacy=await (await ethers.getContractFactory('TimeCoin')).deploy(limits);
    await legacy.mint('1',100);await expect(legacy.mint('1',1)).to.be.revertedWith('account daily cap exceeded');
    await nextDay();await expect(legacy.mint('1',51)).to.be.revertedWith('lifetime review required');
    expect(await legacy.totalSupply()).to.equal(100);
  });
  it('enforces account/global daily caps and rolls back counters and replay markers on rejection',async function () {
    await mint('first','1',100);const failed=await proposal('overflow','1',1);
    await expect(coin.execute(...failed)).to.be.revertedWith('account daily cap exceeded');
    expect(await coin.executed(failed[0])).to.equal(false);
    await mint('second','2',100);await expect(mint('third','3',1)).to.be.revertedWith('global daily cap exceeded');
    const day=await coin.currentDayIndex();expect(await coin.globalDailyMinted(day)).to.equal(200);
    expect(await coin.accountDailyMinted('1',day)).to.equal(100);expect(await coin.totalSupply()).to.equal(200);
  });
  it('daily reset does not reset lifetime; override must be a separately signed action',async function () {
    await mint('first','1',100);await nextDay();
    await expect(mint('normal','1',51)).to.be.revertedWith('lifetime review required');
    const normal=await proposal('tamper','1',51);normal[1]=3;
    await expect(coin.execute(...normal)).to.be.revertedWith('invalid approvals');
    await expect(mint('reviewed','1',51,3)).to.emit(coin,'LifetimeMintReviewed').withArgs('1',100,51);
    expect(await coin.lifetimeMinted('1')).to.equal(151);
    await expect(mint('reviewed-over-daily','1',50,3)).to.be.revertedWith('account daily cap exceeded');
  });
  it('total cap cannot be waived by signatures, new accounts, or a new day; transfers preserve supply',async function () {
    for(let day=0;day<5;day++){
      if(day>0)await nextDay();
      await mint(`a-${day}`,`a${day}`,100);await mint(`b-${day}`,`b${day}`,100);
    }
    await nextDay();await expect(mint('over-total','new',1,3)).to.be.revertedWith('supply cap exceeded');
    await coin.transfer('a0','b0',25);expect(await coin.totalSupply()).to.equal(1000);
    expect(await coin.lifetimeMinted('b0')).to.equal(100);
  });
  it('mint proposals are bound to their Beijing calendar day',async function () {
    const tomorrow=(Math.floor(((await time.latest())+28800)/86400)+2)*86400-28801;
    const future=await proposal('future','1',1,1,tomorrow);
    await expect(coin.execute(...future)).to.be.revertedWith('mint day mismatch');
    const old=await proposal('old','1',1);await nextDay();
    await expect(coin.execute(...old)).to.be.revertedWith('proposal expired');
    await coin.execute(...future);
  });
  it('uses the same Beijing boundary for seconds and FISCO milliseconds',async function () {
    const fisco=await (await ethers.getContractFactory('TimeCoin')).deploy([1000,150,200,100,1000]);
    const boundary=20000*86400-28800;
    expect(await coin.dayIndexAt(boundary-1)).to.equal(19999);
    expect(await coin.dayIndexAt(boundary)).to.equal(20000);
    expect(await fisco.dayIndexAt(BigInt(boundary)*1000n-1n)).to.equal(19999);
    expect(await fisco.dayIndexAt(BigInt(boundary)*1000n)).to.equal(20000);
  });
  it('rejects absent or inconsistent immutable limits',async function () {
    const factory=await ethers.getContractFactory('TimeCoin');
    await expect(factory.deploy([0,1,1,1,1])).to.be.revertedWith('invalid supply cap');
    await expect(factory.deploy([100,10,101,1,1])).to.be.revertedWith('invalid issuance limits');
    await expect(factory.deploy([100,10,10,11,1])).to.be.revertedWith('invalid issuance limits');
    await expect(factory.deploy([100,10,10,1,60])).to.be.revertedWith('invalid timestamp divisor');
  });
});
