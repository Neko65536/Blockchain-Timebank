<template>
  <div>
    <h2>审核预筛Agent</h2>
    <p>每批最多5条。风险标签仅供辅助，所有活动仍由管理员明确决定；活动修改后须重新预筛。</p>
    <el-button :loading="busy" @click="reload">刷新</el-button>
    <el-table :data="candidates" @selection-change="selected = $event">
      <el-table-column type="selection"/><el-table-column prop="id" label="编号"/><el-table-column prop="title" label="待审核活动"/><el-table-column prop="address" label="地址"/>
    </el-table>
    <el-button type="primary" :loading="busy" :disabled="!selected.length || selected.length > 5" @click="screen">批量预筛</el-button>
    <el-table :data="reviews">
      <el-table-column prop="activity_id" label="活动编号"/>
      <el-table-column label="风险"><template slot-scope="s"><el-tag :type="s.row.risk === 'HIGH' ? 'danger' : 'info'">{{ s.row.risk === 'HIGH' ? '高风险·人工核查' : '低风险·待人工决定' }}</el-tag></template></el-table-column>
      <el-table-column label="预筛依据"><template slot-scope="s"><div>{{ findings(s.row).tags.join('、') }}</div><div>{{ findings(s.row).reason }}</div></template></el-table-column>
      <el-table-column label="原始资料"><template slot-scope="s"><el-button @click="showSnapshot(s.row)">核对快照</el-button></template></el-table-column>
      <el-table-column label="人工审核"><template slot-scope="s">
        <template v-if="s.row.state === 'AWAITING_HUMAN'"><el-button :disabled="busy" @click="decide(s.row,'APPROVE')">通过</el-button><el-button :disabled="busy" @click="decide(s.row,'REJECT')">拒绝</el-button></template>
        <div v-else>{{ s.row.decision }} · 审核人{{ s.row.reviewer_id }}：{{ s.row.note }}</div>
      </template></el-table-column>
    </el-table>
    <el-dialog title="预筛时的活动资料" :visible.sync="snapshotOpen"><el-descriptions v-if="snapshot" :column="1" border><el-descriptions-item v-for="field in snapshotFields" :key="field.key" :label="field.label">{{ snapshot[field.key] }}</el-descriptions-item></el-descriptions></el-dialog>
  </div>
</template>
<script>
import request from '@/utils/request';
export default {
  data:()=>({busy:false,candidates:[],selected:[],reviews:[],key:null,snapshot:null,snapshotOpen:false,
    snapshotFields:[{key:'title',label:'标题'},{key:'description',label:'描述'},{key:'address',label:'地址'},{key:'date',label:'日期'},{key:'begin',label:'开始时间'},{key:'end',label:'结束时间'},{key:'quota',label:'名额'},{key:'volunteerReward',label:'每人答谢'}]}),
  mounted(){this.reload();},
  methods:{
    unwrap(r){if(r.code!==1)throw new Error(r.msg || '操作失败');return r.data;},
    async run(fn){this.busy=true;try{await fn();}catch(e){this.$message.error(e.response?.data?.msg || e.message || '请求失败');}finally{this.busy=false;}},
    async load(){this.candidates=this.unwrap(await request.get('/administrator/agent/reviews/candidates'));this.reviews=this.unwrap(await request.get('/administrator/agent/reviews'));},
    reload(){return this.run(()=>this.load());},
    findings(row){return JSON.parse(row.findings);},
    showSnapshot(row){this.snapshot=JSON.parse(row.snapshot);this.snapshotOpen=true;},
    screen(){const ids=this.selected.map(x=>x.id).sort((a,b)=>a-b), signature=JSON.stringify(ids);if(!this.key || this.key.signature!==signature)this.key={signature,value:'review-'+Date.now()+'-'+Math.random().toString(36).slice(2)};
      return this.run(async()=>{this.unwrap(await request.post('/administrator/agent/reviews',{ids},{headers:{'Idempotency-Key':this.key.value},timeout:150000}));await this.load();});},
    async decide(row,decision){let value;try{({value}=await this.$prompt('请核对快照，并填写'+(decision==='APPROVE'?'通过':'拒绝')+'理由。此操作将正式修改活动审核状态。','人工确认',{inputValidator:v=>!!v && v.trim().length>0 && v.length<=500,inputErrorMessage:'请填写1至500字理由'}));}catch(e){return;}
      return this.run(async()=>{this.unwrap(await request.post('/administrator/agent/reviews/'+row.id+'/decision',{decision,note:value,confirmed:true}));await this.load();});}
  }
};
</script>
