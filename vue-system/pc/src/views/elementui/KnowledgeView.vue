<template>
  <div>
    <h2>知识库管理</h2>
    <p>导入已核实的纯文本、FAQ或公告正文。仅索引完成的资料参与问答；公告修改后需禁用旧版本并重新导入。</p>
    <el-form label-width="90px">
      <el-form-item label="标题"><el-input v-model="form.title" maxlength="200" /></el-form-item>
      <el-form-item label="来源类型"><el-select v-model="form.type"><el-option label="文档" value="TEXT"/><el-option label="FAQ" value="FAQ"/><el-option label="公告" value="NOTICE"/></el-select></el-form-item>
      <el-form-item label="可见范围"><el-select v-model="form.audience"><el-option label="全部登录用户" :value="0"/><el-option label="老人" :value="1"/><el-option label="志愿者" :value="2"/><el-option label="管理员" :value="3"/></el-select></el-form-item>
      <el-form-item label="正文"><el-input v-model="form.content" type="textarea" :rows="8" maxlength="16000" show-word-limit/></el-form-item>
      <el-button type="primary" :loading="busy" @click="submit">提交异步索引</el-button>
      <el-button @click="load">刷新状态</el-button>
    </el-form>
    <el-table :data="rows">
      <el-table-column prop="title" label="标题"/><el-table-column prop="state" label="状态"/>
      <el-table-column prop="attempts" label="尝试次数"/><el-table-column prop="last_error" label="最近错误"/>
      <el-table-column label="操作"><template slot-scope="scope">
        <el-button v-if="scope.row.state === 'REVIEW'" type="text" @click="retry(scope.row.id)">重试</el-button>
        <el-button v-if="scope.row.state !== 'DELETED'" type="text" @click="disable(scope.row.id)">禁用</el-button>
      </template></el-table-column>
    </el-table>
  </div>
</template>
<script>
import request from '@/utils/request';
export default {
  data: () => ({ form: { title: '', content: '', type: 'TEXT', audience: 0 }, rows: [], busy: false, pending: null }),
  mounted() { this.load(); },
  methods: {
    async load() { try { const r = await request.get('/administrator/knowledge'); if (r.code !== 1) throw new Error(r.msg); this.rows = r.data; } catch (e) { this.$message.error(e.message || '加载失败'); } },
    async submit() {
      if (!this.form.title.trim() || !this.form.content.trim()) return this.$message.warning('请填写标题与正文');
      const payload = JSON.stringify(this.form);
      if (!this.pending || this.pending.payload !== payload) {
        const bytes = new Uint32Array(4); window.crypto.getRandomValues(bytes);
        this.pending = { payload, key: Array.from(bytes).map(v => v.toString(16).padStart(8, '0')).join('') };
      }
      this.busy = true;
      try {
        const r = await request.post('/administrator/knowledge', this.form, { headers: { 'Idempotency-Key': this.pending.key } });
        if (r.code !== 1) throw new Error(r.msg);
        this.$message.success('已排队，等待MQ消费者完成索引'); this.pending = null; this.form.title = ''; this.form.content = ''; await this.load();
      } catch (e) { this.$message.error(e.message || '提交失败，可重试'); } finally { this.busy = false; }
    },
    async retry(id) { try { const r = await request.post(`/administrator/knowledge/${id}/retry`); if (r.code !== 1) throw new Error(r.msg); await this.load(); } catch (e) { this.$message.error(e.message || '重试失败'); } },
    async disable(id) {
      try { await this.$confirm('禁用后该资料不再参与新问答，是否继续？', '禁用资料'); } catch (_) { return; }
      try { const r = await request.delete(`/administrator/knowledge/${id}`); if (r.code !== 1) throw new Error(r.msg); await this.load(); } catch (e) { this.$message.error(e.message || '禁用失败'); }
    }
  }
};
</script>
