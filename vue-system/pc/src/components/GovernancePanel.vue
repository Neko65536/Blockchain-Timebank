<template>
  <el-card v-if="enabled" style="margin:16px 0" v-loading="loading">
    <div slot="header">管理操作审批 <el-button size="mini" @click="load">刷新提案</el-button></div>
    <p>增发、冻结和解冻需不同审批人签名。请独立核对合约、账户、数量和有效期后提交签名；此页面不接收私钥。</p>
    <el-form inline>
      <el-form-item label="账户编号"><el-input v-model="account" maxlength="64" /></el-form-item>
      <el-form-item label="操作"><el-switch v-model="freeze" active-text="冻结" inactive-text="解冻" /></el-form-item>
      <el-button :disabled="loading || !account" @click="propose">创建提案</el-button>
    </el-form>
    <el-table :data="rows" row-key="id">
      <el-table-column prop="id" label="提案编号" min-width="160" show-overflow-tooltip />
      <el-table-column prop="action" label="操作" width="90" />
      <el-table-column prop="account" label="账户" width="90" />
      <el-table-column prop="value" label="数量/冻结值" width="110" />
      <el-table-column prop="state" label="状态" width="110" />
      <el-table-column label="审批人数" width="90"><template slot-scope="s">{{ s.row.approvers.length }}</template></el-table-column>
      <el-table-column label="操作" width="160"><template slot-scope="s">
        <el-button type="text" @click="selected=s.row;signature=''">详情/签名</el-button>
        <el-button v-if="s.row.action==='FREEZE' && s.row.state==='READY'" type="text" @click="execute(s.row)">执行</el-button>
      </template></el-table-column>
    </el-table>
    <el-dialog title="核对审批提案" :visible="!!selected" @close="selected=null" width="680px" append-to-body>
      <template v-if="selected">
        <p>合约：{{ selected.contract }}</p><p>提案：{{ selected.id }}</p>
        <p>操作：{{ selected.action }}；账户：{{ selected.account }}；值：{{ selected.value }}</p>
        <p>签名截止参数：{{ selected.deadline }}（以合约时间单位为准）</p>
        <p>对以下32字节摘要使用personal_sign，签名须为0x开头的65字节十六进制：</p>
        <el-input :value="selected.digest" readonly aria-label="提案摘要" />
        <el-input v-if="selected.state==='MULTISIG'" v-model="signature" type="textarea" :rows="3" placeholder="粘贴独立钱包生成的签名；不要填写私钥" />
        <p>已审批地址：{{ selected.approvers.join('，') || '暂无' }}</p>
      </template>
      <span slot="footer"><el-button @click="selected=null">关闭</el-button><el-button v-if="selected && selected.state==='MULTISIG'" type="primary" :disabled="loading || !/^0x[0-9a-fA-F]{130}$/.test(signature)" @click="approve">提交签名</el-button></span>
    </el-dialog>
    <p v-if="error" role="alert">{{ error }}</p>
  </el-card>
</template>
<script>
import request from '@/utils/request';
import { postSettlement } from '@/utils/settlement';
export default {
  data() { return { enabled:false,loading:false,rows:[],selected:null,signature:'',account:'',freeze:true,error:'' }; },
  async mounted() {
    try {const status=await request.get('/administrator/chain/status');this.enabled=!!(status.data && status.data.governanceEnabled);if(this.enabled) await this.load();}
    catch(e) {this.error='审批服务状态读取失败';}
  },
  methods: {
    async load() {
      this.loading=true;
      try {const res=await request.get('/administrator/chain/governance');if(res.code!==1) throw new Error(res.msg);this.rows=res.data;this.error='';}
      catch(e) {this.error=e.message || '加载失败';} finally {this.loading=false;}
    },
    async mutate(action) {
      this.loading=true;
      try {const res=await action();if(res.code!==1) throw new Error(res.msg);this.$message.success('操作已受理，请查看提案与结算状态');this.selected=null;await this.load();}
      catch(e) {this.error=e.message || '操作结果待核查，请刷新后恢复原提案';} finally {this.loading=false;}
    },
    propose() {this.mutate(()=>postSettlement('/administrator/chain/governance/freeze',{userId:this.account.trim(),frozen:this.freeze}));},
    approve() {this.mutate(()=>request.post('/administrator/chain/governance/'+this.selected.id+'/approve',{signature:this.signature.trim()}));},
    execute(row) {this.mutate(()=>request.post('/administrator/chain/governance/'+row.id+'/execute'));}
  }
};
</script>
