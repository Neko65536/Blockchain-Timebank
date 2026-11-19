<template>
  <el-card style="margin:20px 0">
    <h2>运营问答Agent</h2>
    <p>支持活动报名排行、每日时间币流转。可问“上周哪个活动报名最多”，或明确指定不超过31天的日期范围。</p>
    <el-input v-model="question" maxlength="2000" placeholder="上周时间币流转情况如何？" @keyup.enter.native="ask" />
    <el-button type="primary" :loading="busy" @click="ask">查询</el-button>
    <template v-if="result">
      <p>{{ result.start }}至{{ result.end }}（不含结束日，{{ result.timezone }}）</p>
      <p>{{ result.answer }}</p><p>{{ result.scope }}</p>
      <div ref="chart" style="height:320px;width:100%" role="img" aria-label="查询结果图表，下方提供精确数据表" />
      <el-table :data="result.rows"><el-table-column prop="label" label="活动或日期"/><el-table-column v-if="result.chartType === 'bar'" prop="activityId" label="活动编号"/><el-table-column prop="value" label="统计值"/></el-table>
      <p>图表用于比较，精确值以表格为准。查询记录：{{ result.auditId }}</p>
    </template>
  </el-card>
</template>
<script>
import request from '@/utils/request';
import * as echarts from 'echarts';
export default {
  data:()=>({question:'',busy:false,result:null}),
  mounted(){window.addEventListener('resize',this.resize);},
  beforeDestroy(){window.removeEventListener('resize',this.resize);if(this.chart)this.chart.dispose();},
  methods:{
    resize(){if(this.chart)this.chart.resize();},
    async ask(){if(this.busy || !this.question.trim())return;this.busy=true;
      try{const response=await request.post('/administrator/agent/analytics',{question:this.question},{timeout:150000});if(response.code!==1)throw new Error(response.msg);
        this.result=response.data;await this.$nextTick();if(!this.chart)this.chart=echarts.init(this.$refs.chart);
        this.chart.setOption({tooltip:{trigger:'axis',renderMode:'richText'},grid:{containLabel:true},xAxis:{type:'category',data:this.result.rows.map(r=>r.label),axisLabel:{width:100,overflow:'truncate'}},yAxis:{type:'value'},series:[{type:this.result.chartType,data:this.result.rows.map(r=>Number(r.value))}]},true);
      }catch(e){this.result=null;if(this.chart){this.chart.dispose();this.chart=null;}this.$message.error(e.response?.data?.msg || e.message || '查询失败');}finally{this.busy=false;}}
  }
};
</script>
