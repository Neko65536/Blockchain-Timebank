const { test } = require('node:test');
const assert = require('node:assert/strict');
const { readFile } = require('node:fs/promises');
const path = require('node:path');
const vm = require('node:vm');
const { webcrypto } = require('node:crypto');

async function client(app, storage, send) {
  const context = vm.createContext({
    atob, Uint8Array, window: { crypto: webcrypto },
    localStorage: {
      getItem: key => storage.get(key) || null,
      setItem: (key, value) => storage.set(key, value),
      removeItem: key => storage.delete(key)
    }
  });
  const token = 'header.' + Buffer.from(JSON.stringify({ id: 2 })).toString('base64url') + '.signature';
  const api = new vm.SyntheticModule(['default'], function () { this.setExport('default', { post: send }); }, { context });
  const auth = new vm.SyntheticModule(['getToken'], function () { this.setExport('getToken', () => token); }, { context });
  const source = await readFile(path.join(__dirname, '..', app, 'src/utils/settlement.js'), 'utf8');
  const module = new vm.SourceTextModule(source, { context });
  await module.link(specifier => specifier === './request' ? api : auth);
  await module.evaluate();
  return module.namespace.postSettlement;
}

for (const app of ['pc', 'phone']) {
  test(app + ': timeout and page recreation reuse request ID without persisting password', async () => {
    const storage = new Map(); let first;
    const initial = await client(app, storage, async (url, body, config) => {
      first = config.headers['Idempotency-Key']; throw new Error('timeout');
    });
    await assert.rejects(initial('/info/coin/transfer', { toUserId: '10', amount: '3', password: 'private-password' }));
    assert.equal(storage.size, 1);
    assert.ok(![...storage.values()][0].includes('private-password'));
    const reloaded = await client(app, storage, async (url, body, config) => {
      assert.equal(config.headers['Idempotency-Key'], first);
      return { code: 1, data: { state: 'PENDING' } };
    });
    await reloaded('/info/coin/transfer', { toUserId: '10', amount: '3', password: 'new-password' });
    assert.equal(storage.size, 0);
  });

  test(app + ': uncertain request cannot silently change payment payload', async () => {
    const storage = new Map(); let sends = 0;
    const post = await client(app, storage, async () => { sends++; throw new Error('network'); });
    await assert.rejects(post('/administrator/chain/mint', { userId: '10', amount: '3' }));
    await assert.rejects(post('/administrator/chain/mint', { userId: '11', amount: '3' }), /上次请求结果待核对/);
    assert.equal(sends, 1);
  });

  test(app + ': acknowledged operation permits a distinct subsequent operation', async () => {
    const storage = new Map(); const keys = [];
    const post = await client(app, storage, async (url, body, config) => {
      keys.push(config.headers['Idempotency-Key']); return { code: 1, data: { state: 'PENDING' } };
    });
    await post('/administrator/chain/mint', { userId: '10', amount: '3' });
    await post('/administrator/chain/mint', { userId: '10', amount: '3' });
    assert.notEqual(keys[0], keys[1]);
  });
}
