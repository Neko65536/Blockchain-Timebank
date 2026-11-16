<template>
  <el-card style="margin: 16px 0">
    <div slot="header">
      <span>结算记录</span>
      <el-button size="mini" style="float: right" :loading="loading" @click="load">刷新</el-button>
    </div>
    <p>已受理的请求会在后台处理，状态为“已完成”后才计入余额。</p>
    <el-table :data="rows" size="mini" empty-text="暂无结算记录" style="width: 100%">
      <el-table-column prop="settlementId" label="结算编号" min-width="160" show-overflow-tooltip />
      <el-table-column label="业务" min-width="100">
        <template slot-scope="scope">{{ typeLabel(scope.row.bizType) }}</template>
      </el-table-column>
      <el-table-column prop="amount" label="数量" width="65" />
      <el-table-column label="状态" min-width="120">
        <template slot-scope="scope">{{ stateLabel(scope.row.state) }}</template>
      </el-table-column>
      <el-table-column prop="lastError" label="待核查原因" min-width="180" show-overflow-tooltip />
      <el-table-column v-if="admin" label="操作" width="110">
        <template slot-scope="scope">
          <el-button v-if="scope.row.state === 'REVIEW'" type="text" @click="retry(scope.row)">恢复原结算</el-button>
        </template>
      </el-table-column>
    </el-table>
    <p v-if="error" style="color: #c45656">{{ error }}</p>
  </el-card>
</template>
<script>
import request from '@/utils/request';
import { settlementState } from '@/utils/settlement';
export default {
  props: { admin: { type: Boolean, default: false } },
  data() { return { rows: [], loading: false, error: '', timer: null }; },
  mounted() {
    this.load();
    this.timer = setInterval(() => {
      if (!document.hidden && this.rows.some(row => ['PENDING', 'PROCESSING', 'RETRY'].includes(row.state))) this.load();
    }, 5000);
  },
  beforeDestroy() { clearInterval(this.timer); },
  methods: {
    stateLabel: settlementState,
    typeLabel(type) { return ({ PUBLISH_FEE: '发布扣费', VOLUNTEER_REWARD: '服务答谢', USER_TRANSFER: '转账', ADMIN_TRANSFER: '管理员转账', ADMIN_MINT: '增发' })[type] || type; },
    async load() {
      if (this.loading) return;
      this.loading = true;
      try {
        const res = await request.get(this.admin ? '/administrator/chain/settlements' : '/info/settlements');
        if (res.code !== 1) throw new Error(res.msg);
        this.rows = res.data || [];
        this.error = '';
      } catch (e) { this.error = '结算记录加载失败，请稍后刷新'; }
      finally { this.loading = false; }
    },
    async retry(row) {
      try {
        await this.$confirm('请先核查失败原因。此操作恢复原结算及原交易，不会创建新的支付。', '恢复结算');
        const res = await request.post('/administrator/chain/settlements/' + row.settlementId + '/retry');
        if (res.code !== 1) throw new Error(res.msg);
        this.$message.success('已安排恢复原结算');
        this.load();
      } catch (e) { if (e !== 'cancel' && e !== 'close') this.$message.error(e.message || '恢复失败'); }
    }
  }
};
</script>
