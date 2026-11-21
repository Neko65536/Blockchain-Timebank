<template>
  <el-card class="publish-agent">
    <h3>活动发布Agent</h3>
    <p>描述需求后补全表单，核对费用并确认才会提交。不会自动支付。</p>
    <el-input v-model="text" type="textarea" maxlength="4000" placeholder="例如：希望周末有人陪我去社区超市买菜" />
    <el-button :loading="busy" @click="generate">生成新草稿</el-button>
    <el-button @click="openHistory">我的历史草稿</el-button>
    <el-dialog title="历史草稿" :visible.sync="historyVisible" width="90%">
      <el-table :data="history.items" size="small" v-loading="historyBusy">
        <el-table-column prop="id" label="任务编号" min-width="120" />
        <el-table-column prop="state" label="状态" width="100" />
        <el-table-column prop="createdAt" label="创建时间" min-width="150" />
        <el-table-column label="操作" width="90">
          <template slot-scope="scope"><el-button type="text" @click="loadTask(scope.row.id)">查看</el-button></template>
        </el-table-column>
      </el-table>
      <el-pagination layout="prev, pager, next" :total="history.total" :page-size="history.size"
        :current-page.sync="history.page" @current-change="loadHistory" />
    </el-dialog>
    <template v-if="task">
      <p>任务：{{ task.id }} · {{ stateLabel }}</p>
      <el-form label-position="top" :disabled="busy || task.state === 'SUBMITTED'">
        <el-form-item v-for="field in fields" :key="field.key" :label="field.label">
          <el-input v-if="!field.numeric" v-model="draft[field.key]" :type="field.type || 'text'" />
          <el-input-number v-else v-model="draft[field.key]" :min="field.key === 'quota' ? 1 : 0" :precision="0" />
        </el-form-item>
        <el-form-item label="服务类型">
          <el-select v-model="draft.serviceType" @change="extra = {}"><el-option v-for="(item,key) in services" :key="key" :label="item.label" :value="key" /></el-select>
        </el-form-item>
        <el-form-item v-for="(label,key) in extraFields" :key="key" :label="label"><el-input v-model="extra[key]" /></el-form-item>
        <el-button @click="save">保存补充并校验</el-button>
      </el-form>
      <ul><li v-for="issue in task.feedback" :key="issue">{{ issue }}</li></ul>
      <p>当前发布费：{{ task.publishFee }}时间币；每人答谢：{{ draft.volunteerReward == null ? '待填写' : draft.volunteerReward }}时间币。</p>
      <el-button type="primary" :disabled="busy || task.state !== 'READY' || dirty" @click="confirm">确认费用并提交审核</el-button>
      <p v-if="dirty">表单有修改，请先保存并重新校验。</p>
      <p v-if="task.result">结算编号：{{ task.result.settlementId }}；状态：{{ task.result.state }}。提交不等于已扣费或审核通过，请在活动列表查看后续状态。</p>
      <details><summary>处理记录</summary><ul><li v-for="(step,index) in task.steps" :key="index">{{ step.tool }}：{{ step.detail }}</li></ul></details>
    </template>
  </el-card>
</template>
<script>
import request from '@/utils/request';
const base = '/users/old/agent/publish';
export default {
  data: () => ({ text: '', task: null, draft: {}, extra: {}, saved: '', busy: false, key: null,
    historyVisible: false, historyBusy: false, history: { items: [], total: 0, page: 1, size: 10 },
    fields: [ { key:'title',label:'标题' },{key:'description',label:'描述',type:'textarea'}, {key:'quota',label:'名额',numeric:true},
      {key:'date',label:'活动日期（yyyy-MM-dd）'},{key:'begin',label:'开始时间（HH:mm:ss）'},{key:'end',label:'结束时间（HH:mm:ss）'},
      {key:'deadline',label:'报名截止（yyyy-MM-ddTHH:mm:ss）'},{key:'address',label:'具体地址'},{key:'phone',label:'联系电话'},{key:'volunteerReward',label:'每人答谢时间币',numeric:true}],
    services: {
      other_service:{label:'其他服务',fields:{customCategory:'服务类别',serviceDetails:'服务详情'}},
      cleaning:{label:'清洁',fields:{cleaningScope:'清洁范围',homeArea:'房屋面积'}},
      shopping_companion:{label:'陪同购物',fields:{destination:'目的地',budgetRange:'预算范围'}},
      clinic_companion:{label:'陪诊',fields:{hospitalAddress:'医院地址',visitType:'就诊类型',registrationNeeded:'是否需要挂号'}},
      purchase:{label:'代购',fields:{shoppingList:'购物清单',maxBudget:'预算上限'}},
      medical_rehab:{label:'康复陪护',fields:{hospitalAddress:'医院地址',department:'科室',appointmentTime:'预约时间'}},
      health_manage:{label:'健康管理',fields:{healthTaskType:'健康任务',frequency:'频次'}}
    }
  }),
  computed: {
    extraFields() { return (this.services[this.draft.serviceType] || { fields:{} }).fields; },
    dirty() { return this.saved !== JSON.stringify([this.draft,this.extra]); },
    stateLabel() { return {READY:'等待确认',NEEDS_INPUT:'待补充',SUBMITTED:'已提交'}[this.task.state] || this.task.state; }
  },
  methods: {
    openHistory() { this.historyVisible=true; this.history.page=1; this.loadHistory(); },
    async loadHistory() { this.historyBusy=true; try {
      this.history = this.unwrap(await request.get(base+'/history',{params:{page:this.history.page,size:this.history.size}}));
    } catch(e) { this.$message.error('加载历史失败'); } finally { this.historyBusy=false; } },
    async loadTask(id) { this.historyVisible=false; await this.run(async()=>this.load(this.unwrap(await request.get(base+'/'+id)))); },
    load(data) { this.task=data; this.draft=JSON.parse(JSON.stringify(data.draft)); this.extra=JSON.parse(this.draft.extraJson || '{}'); this.saved=JSON.stringify([this.draft,this.extra]); },
    async run(action) { this.busy=true; try { await action(); } catch(e) { this.$message.error((e.response && e.response.data && e.response.data.msg) || e.message || '操作失败，请重试'); } finally { this.busy=false; } },
    unwrap(r) { if(r.code!==1) throw new Error(r.msg || '请求失败');return r.data; },
    generate() { if(!this.text.trim()) return; if(!this.key || this.key.text!==this.text) this.key={text:this.text,value:'agent-'+Date.now()+'-'+Math.random().toString(36).slice(2)};
      return this.run(async()=>this.load(this.unwrap(await request.post(base,{text:this.text},{headers:{'Idempotency-Key':this.key.value},timeout:150000})))); },
    save() { return this.run(async()=>{ const draft={...this.draft,extraJson:JSON.stringify(this.extra)}; this.load(this.unwrap(await request.put(base+'/'+this.task.id,{revision:this.task.revision,draft}))); }); },
    async confirm() { try { await this.$confirm('确认当前表单及发布费'+this.task.publishFee+'时间币？此操作会通过原发布流程创建结算。','确认提交'); } catch(e) { return; }
      return this.run(async()=>{ this.unwrap(await request.post(base+'/'+this.task.id+'/confirm',{revision:this.task.revision,acceptedFee:this.task.publishFee,confirmed:true})); this.load(this.unwrap(await request.get(base+'/'+this.task.id))); }); }
  }
};
</script>
<style scoped>.publish-agent{margin-bottom:20px}.el-button{margin-top:12px}.el-form{margin-top:16px}</style>
