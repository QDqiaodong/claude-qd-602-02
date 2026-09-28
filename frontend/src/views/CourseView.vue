<template>
  <div class="page">
    <el-card shadow="never" class="hero">
      <div class="hero-head">
        <div>
          <h2>学员与课程</h2>
          <p>约课要过五道闸：课程开放、未满员、课时余额够、没重复约过、<b>先修路径已完成</b>。
            只有「已完成」的课能解锁后续；在学、已取消、历史状态缺失都不算。等价先修组完成任意一门即可。</p>
        </div>
        <div class="hero-actions">
          <el-button @click="openBatch">一键多科报名</el-button>
          <el-button type="primary" @click="openEnroll">单个约课</el-button>
        </div>
      </div>
    </el-card>

    <el-card shadow="never" class="balance-card">
      <h3>学员课时余额</h3>
      <el-table :data="students" size="small" border>
        <el-table-column prop="studentNo" label="学号" width="110" />
        <el-table-column prop="name" label="姓名" width="100" />
        <el-table-column label="程度" width="90">
          <template #default="{ row }">{{ row.level }}</template>
        </el-table-column>
        <el-table-column label="课时余额" width="120">
          <template #default="{ row }">
            <el-tag :type="row.lessonBalance > 0 ? 'success' : 'danger'" size="small" effect="light">
              {{ row.lessonBalance }} 节
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" min-width="160">
          <template #default="{ row }">
            <span v-if="row.lessonBalance > 0" class="muted">可约课</span>
            <span v-else class="blocked">未购课，约课会被拦下</span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 学员课程路径视图：选学员后全部课程一次解析，结论与详情/刷新完全一致 -->
    <el-card shadow="never" class="path-card">
      <template #header>
        <div class="list-head">
          <span>学员课程路径</span>
          <div class="path-filter">
            <el-select v-model="pathStudentId" placeholder="选择学员查看路径" clearable size="small"
                       filterable style="width: 260px" @change="onPathStudentChange">
              <el-option v-for="item in students" :key="item.id"
                         :label="`${item.studentNo} ${item.name}`" :value="item.id" />
            </el-select>
            <el-button v-if="pathStudentId" size="small" @click="reloadPaths">刷新路径结论</el-button>
          </div>
        </div>
      </template>

      <div v-if="!pathStudentId" class="path-hint">
        选择一位学员后，这里会从每门目标课向前解析直接与多级先修：
        给出「可学 / 受阻 / 循环」结论、仍缺课程的<b>最短缺口路径</b>，以及等价先修组的完成情况。
      </div>

      <el-table v-else :data="pathRows" size="small" border row-key="courseId">
        <el-table-column type="expand">
          <template #default="{ row: p }">
            <div class="path-detail">
              <template v-if="p.groups && p.groups.length">
                <div v-for="g in p.groups" :key="g.groupId" class="prereq-group">
                  <div class="group-head">
                    <el-tag size="small" :type="g.matchMode === 'ANY' ? 'warning' : 'info'" effect="plain">
                      {{ g.matchMode === 'ANY' ? '等价先修组 · 任意一门' : '全部完成' }}
                    </el-tag>
                    <span v-if="g.remark" class="muted">{{ g.remark }}</span>
                    <el-tag size="small" :type="g.satisfied ? 'success' : 'danger'" effect="light">
                      {{ g.satisfied ? '本组已满足' : '本组未满足' }}
                    </el-tag>
                  </div>
                  <div class="group-items">
                    <el-tag v-for="it in g.items" :key="it.course.courseId" size="small"
                            :type="itemTagType(it)" effect="light" class="item-tag">
                      {{ it.course.missing ? `已删除课程#${it.course.courseId}` : it.course.courseNo + ' ' + it.course.title }}
                      （{{ it.learningStateName }}<template v-if="it.cyclePath"> · 环路</template>）
                    </el-tag>
                  </div>
                </div>
              </template>
              <div v-else class="muted">该课程没有先修要求，默认可学。</div>
              <div class="gap-line">
                <span v-if="p.verdict === 'AVAILABLE'" class="ok-text">✔ {{ p.summary }}</span>
                <span v-else-if="p.verdict === 'CYCLE'" class="cycle-text">⟳ {{ p.summary }}</span>
                <span v-else class="blocked-text">✘ {{ p.summary }}</span>
              </div>
            </div>
          </template>
        </el-table-column>

        <el-table-column prop="courseNo" label="课程编号" width="110" />
        <el-table-column prop="title" label="课程名称" min-width="170" />
        <el-table-column label="我的状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="learningTagType(row.learningState)" effect="light">
              {{ row.learningStateName }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="路径结论" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="verdictTagType(row)" effect="dark">{{ row.verdictName }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="最短缺口路径（仍缺课程）" min-width="320">
          <template #default="{ row }">
            <span v-if="row.verdict === 'AVAILABLE'" class="ok-text">—</span>
            <span v-else-if="row.verdict === 'CYCLE'" class="cycle-text">{{ row.cyclePath }}</span>
            <span v-else class="gap-chain">
              <el-tag v-for="(g, i) in row.gapPath" :key="g.courseId" size="small" type="danger"
                      effect="plain" class="gap-tag">
                {{ g.missing ? `已删除课程#${g.courseId}` : g.courseNo + ' ' + g.title }}
              </el-tag>
              <span class="muted">（缺 {{ row.missingCount }} 门 · 层级 {{ row.missingLevel }}）</span>
            </span>
          </template>
        </el-table-column>
        <el-table-column label="数据" width="120">
          <template #default="{ row }">
            <el-tag v-if="row.dataGap" size="small" type="warning" effect="dark">数据缺口</el-tag>
            <el-tag v-if="row.closed" size="small" type="info" effect="plain">已停开</el-tag>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card shadow="never" class="table-card">
      <el-table :data="courses" row-key="id" border @expand-change="onExpand">
        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="expand">
              <el-descriptions :column="4" size="small" border>
                <el-descriptions-item label="课程编号">{{ row.courseNo }}</el-descriptions-item>
                <el-descriptions-item label="授课教师">{{ row.teacherName }}</el-descriptions-item>
                <el-descriptions-item label="课时包价格">{{ formatMoney(row.price) }}</el-descriptions-item>
                <el-descriptions-item label="状态">{{ row.status === 'OPEN' ? '开放报名' : '已停开' }}</el-descriptions-item>
              </el-descriptions>

              <!-- 与路径视图完全同口径的结论：展开详情自带学员时返回 -->
              <div v-if="pathStudentId && detailPaths[row.id]" class="path-inline">
                <div class="path-inline-head">
                  <span>学员路径结论</span>
                  <el-tag size="small" :type="verdictTagType(detailPaths[row.id])" effect="dark">
                    {{ detailPaths[row.id].verdictName }}
                  </el-tag>
                  <el-tag v-if="detailPaths[row.id].learningState !== 'NONE'" size="small"
                          :type="learningTagType(detailPaths[row.id].learningState)" effect="light">
                    {{ detailPaths[row.id].learningStateName }}
                  </el-tag>
                </div>
                <div :class="detailPaths[row.id].verdict === 'AVAILABLE' ? 'ok-text'
                              : detailPaths[row.id].verdict === 'CYCLE' ? 'cycle-text' : 'blocked-text'">
                  {{ detailPaths[row.id].summary }}
                </div>
              </div>

              <el-skeleton v-if="loading[row.id]" :rows="2" animated />

              <div v-else-if="details[row.id]" class="expand-block">
                <h4>
                  已报名学员（{{ details[row.id].students.length }} 人，剩余 {{ details[row.id].remainSeats }} 个名额）
                </h4>
                <el-table v-if="details[row.id].students.length" :data="details[row.id].students" size="small" border>
                  <el-table-column prop="studentNo" label="学号" width="120" />
                  <el-table-column prop="name" label="姓名" width="120" />
                  <el-table-column prop="phone" label="手机号" width="150" />
                  <el-table-column label="程度" width="100">
                    <template #default="{ row: s }">{{ s.level }}</template>
                  </el-table-column>
                  <el-table-column label="课时余额" width="110">
                    <template #default="{ row: s }">{{ s.lessonBalance }} 节</template>
                  </el-table-column>
                </el-table>
                <el-empty v-else description="还没有学员报名" :image-size="60" />
              </div>
            </div>
          </template>
        </el-table-column>

        <el-table-column prop="courseNo" label="课程编号" width="120" />
        <el-table-column prop="title" label="课程名称" min-width="180" />
        <el-table-column prop="teacherName" label="授课教师" width="110" />
        <el-table-column label="名额" width="120">
          <template #default="{ row }">
            <span :class="row.enrolledCount >= row.capacity ? 'full' : ''">
              {{ row.enrolledCount }} / {{ row.capacity }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="剩余名额" width="110">
          <template #default="{ row }">
            <el-tag :type="row.capacity - row.enrolledCount > 0 ? 'success' : 'danger'" size="small" effect="light">
              {{ Math.max(0, row.capacity - row.enrolledCount) }}
            </el-tag>
          </template>
        </el-table-column>
        <!-- 行内结论：与路径视图、详情同源，刷新后一致 -->
        <el-table-column v-if="pathStudentId" label="路径结论" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="verdictTagType(row.path)" effect="dark">
              {{ row.path ? row.path.verdictName : '—' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="价格" width="110">
          <template #default="{ row }">{{ formatMoney(row.price) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="row.status === 'OPEN' ? 'success' : 'info'" size="small" effect="dark">
              {{ row.status === 'OPEN' ? '开放报名' : '已停开' }}
            </el-tag>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card shadow="never" class="table-card">
      <template #header>
        <div class="list-head">
          <span>约课记录</span>
          <el-select v-model="enrollFilterStudent" placeholder="按学员筛" clearable size="small" style="width: 180px" @change="loadEnrollments">
            <el-option v-for="item in students" :key="item.id" :label="`${item.studentNo} ${item.name}`" :value="item.id" />
          </el-select>
        </div>
      </template>
      <el-table :data="enrollments" size="small" border>
        <el-table-column label="学员" width="150">
          <template #default="{ row }">{{ row.studentName || `已删除学员#${row.studentId}` }}</template>
        </el-table-column>
        <el-table-column label="课程" min-width="170">
          <template #default="{ row }">
            <span v-if="row.courseTitle">{{ row.courseTitle }}</span>
            <el-tag v-else size="small" type="warning" effect="plain">已删除课程#{{ row.courseId }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="teacherName" label="教师" width="100" />
        <el-table-column prop="enrollDate" label="约课日期" width="130" />
        <el-table-column prop="lessons" label="消耗课时" width="100" />
        <el-table-column label="剩余余额" width="110">
          <template #default="{ row }">{{ row.lessonBalance == null ? '—' : `${row.lessonBalance} 节` }}</template>
        </el-table-column>
        <el-table-column label="名额" width="100">
          <template #default="{ row }">{{ row.enrolledCount == null ? '—' : `${row.enrolledCount} / ${row.capacity}` }}</template>
        </el-table-column>
        <el-table-column prop="statusName" label="状态" width="100" />
      </el-table>
    </el-card>

    <el-dialog v-model="enrollVisible" title="单个约课" width="520">
      <el-form :model="enrollForm" label-width="92px">
        <el-form-item label="学员">
          <el-select v-model="enrollForm.studentId" filterable placeholder="选择学员" style="width: 100%"
                     @change="onEnrollStudentChange">
            <el-option
              v-for="item in students"
              :key="item.id"
              :label="`${item.studentNo} ${item.name}（余额 ${item.lessonBalance} 节）`"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="课程">
          <el-select v-model="enrollForm.courseId" placeholder="选择课程" style="width: 100%">
            <el-option
              v-for="item in courseOptionsForEnroll"
              :key="item.id"
              :label="`${item.courseNo} ${item.title}（${item.enrolledCount}/${item.capacity}）${item.path ? ' · ' + item.path.verdictName : ''}`"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item v-if="enrollPreview" label="路径预检">
          <el-tag size="small" :type="verdictTagType(enrollPreview)" effect="dark">{{ enrollPreview.verdictName }}</el-tag>
          <span class="preview-text" :class="enrollPreview.verdict === 'AVAILABLE' ? 'ok-text'
                : enrollPreview.verdict === 'CYCLE' ? 'cycle-text' : 'blocked-text'">
            {{ enrollPreview.summary }}
          </span>
        </el-form-item>
        <el-form-item label="消耗课时">
          <el-input-number v-model="enrollForm.lessons" :min="1" style="width: 100%" />
        </el-form-item>
        <el-form-item label="约课日期">
          <el-date-picker v-model="enrollForm.enrollDate" type="date" value-format="YYYY-MM-DD" style="width: 100%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="enrollVisible = false">取消</el-button>
        <el-button type="primary" @click="submitEnroll">确认约课</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="batchVisible" title="一键多科报名" width="560">
      <el-form :model="batchForm" label-width="102px">
        <el-form-item label="学员">
          <el-select v-model="batchForm.studentId" filterable placeholder="选择学员" style="width: 100%">
            <el-option
              v-for="item in students"
              :key="item.id"
              :label="`${item.studentNo} ${item.name}（余额 ${item.lessonBalance} 节）`"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="选择课程">
          <el-select v-model="batchForm.courseIds" multiple placeholder="最多 3 门，同批不能互当先修" style="width: 100%">
            <el-option
              v-for="item in courses"
              :key="item.id"
              :label="`${item.courseNo} ${item.title}（${item.enrolledCount}/${item.capacity}）`"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="每门消耗课时">
          <el-input-number v-model="batchForm.lessons" :min="1" style="width: 100%" />
        </el-form-item>
      </el-form>
      <div class="batch-tip">
        本操作会一次性写入多条约课记录，并一次性扣减课时余额。
        先修闸门与单个约课同口径；同批选中的课程不能互相充当已完成先修。
      </div>
      <template #footer>
        <el-button @click="batchVisible = false">取消</el-button>
        <el-button type="primary" @click="submitBatch">确认批量报名</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { courseApi, formatMoney } from '../api'

const students = ref([])
const courses = ref([])
const enrollments = ref([])
const details = reactive({})
const detailPaths = reactive({})
const loading = reactive({})
const enrollFilterStudent = ref(null)

// 学员课程路径视图
const pathStudentId = ref(null)
const pathRows = ref([])

const enrollVisible = ref(false)
const enrollForm = reactive({ studentId: null, courseId: null, lessons: 2, enrollDate: '' })
const enrollPreview = ref(null)
const batchVisible = ref(false)
const batchForm = reactive({ studentId: null, courseIds: [], lessons: 2 })

const courseOptionsForEnroll = computed(() => courses.value)

const loadStudents = async () => {
  const res = await courseApi.students()
  students.value = res.data || []
}

const loadCourses = async () => {
  // 选了路径学员时，列表行内直接带回同一口径结论
  const res = await courseApi.courses(pathStudentId.value || undefined)
  courses.value = res.data || []
}

const loadEnrollments = async () => {
  const res = await courseApi.enrollments({ studentId: enrollFilterStudent.value })
  enrollments.value = res.data || []
}

const reloadPaths = async () => {
  if (!pathStudentId.value) {
    pathRows.value = []
    return
  }
  const res = await courseApi.coursePaths(pathStudentId.value)
  pathRows.value = res.data || []
}

const onPathStudentChange = async () => {
  Object.keys(detailPaths).forEach((key) => delete detailPaths[key])
  await Promise.all([reloadPaths(), loadCourses()])
}

const onExpand = async (row, expanded) => {
  if (!expanded.length) return
  loading[row.id] = true
  try {
    const res = await courseApi.courseDetail(row.id, pathStudentId.value || undefined)
    details[row.id] = res.data
    if (res.data.path) {
      detailPaths[row.id] = res.data.path
    }
  } finally {
    loading[row.id] = false
  }
}

const refreshAll = async () => {
  Object.keys(details).forEach((key) => delete details[key])
  Object.keys(detailPaths).forEach((key) => delete detailPaths[key])
  await Promise.all([loadStudents(), loadCourses(), loadEnrollments(), reloadPaths()])
}

const onEnrollStudentChange = () => {
  // 单个约课的学员不强行联动路径视图；课程下拉的路径结论在选中后预检
  enrollPreview.value = null
}

const submitEnroll = async () => {
  await courseApi.enroll({ ...enrollForm })
  ElMessage.success('约课成功')
  enrollVisible.value = false
  await refreshAll()
}

const openBatch = () => {
  Object.assign(batchForm, { studentId: null, courseIds: [], lessons: 2 })
  batchVisible.value = true
}

const submitBatch = async () => {
  const res = await courseApi.batchEnroll({ ...batchForm })
  ElMessage.success(`批量约课成功，共写入 ${(res.data || []).length} 条记录`)
  batchVisible.value = false
  await refreshAll()
}

// 路径预检：选了学员+课程后用统一解析给结论（仅提示，真正闸门在后端）
const updateEnrollPreview = async () => {
  if (enrollForm.studentId && enrollForm.courseId) {
    const res = await courseApi.coursePath(enrollForm.courseId, enrollForm.studentId)
    enrollPreview.value = res.data
  } else {
    enrollPreview.value = null
  }
}

watch(() => [enrollForm.studentId, enrollForm.courseId], updateEnrollPreview)

// ---------------- 结论配色（列表/详情/路径视图同一套） ----------------
const verdictTagType = (p) => {
  if (!p) return 'info'
  if (p.verdict === 'AVAILABLE') return 'success'
  if (p.verdict === 'CYCLE') return 'warning'
  return 'danger'
}

const learningTagType = (state) => {
  if (state === 'COMPLETED') return 'success'
  if (state === 'NORMAL') return 'primary'
  if (state === 'CANCELED') return 'info'
  return 'danger' // LEGACY_MISSING
}

const itemTagType = (it) => {
  if (it.satisfied) return 'success'
  if (it.dataGap) return 'warning'
  if (it.blocked) return 'danger'
  return 'info'
}

onMounted(async () => {
  await refreshAll()
})
</script>

<style scoped>
.page { display: flex; flex-direction: column; gap: 16px; }
.hero :deep(.el-card__body) { padding: 18px 20px; }
.hero-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 20px; }
.hero-head h2 { margin: 0 0 6px; font-size: 20px; color: #1e857b; }
.hero-head p { margin: 0; font-size: 13px; color: #7a8a89; max-width: 760px; line-height: 1.6; }
.hero-actions { display: flex; gap: 10px; flex: none; }
.balance-card h3 { margin: 0 0 12px; font-size: 15px; color: #1e857b; }
.balance-card :deep(.el-card__body) { padding: 16px 20px; }
.path-card :deep(.el-card__body) { padding: 0; }
.path-filter { display: flex; gap: 10px; align-items: center; }
.path-hint { padding: 22px 20px; color: #7a8a89; font-size: 13px; line-height: 1.8; }
.path-detail { padding: 12px 18px 14px; background: #f7fbfb; }
.prereq-group { margin-bottom: 10px; }
.group-head { display: flex; gap: 10px; align-items: center; margin-bottom: 6px; }
.group-items { display: flex; flex-wrap: wrap; gap: 6px; padding-left: 4px; }
.item-tag { white-space: normal; }
.gap-line { margin-top: 10px; font-size: 13px; }
.gap-chain { display: inline-flex; flex-wrap: wrap; gap: 6px; align-items: center; }
.gap-tag { white-space: normal; }
.table-card :deep(.el-card__body) { padding: 0; }
.list-head { display: flex; align-items: center; justify-content: space-between; padding: 12px 16px; }
.expand { padding: 14px 18px 18px; background: #f7fbfb; }
.expand-block { margin-top: 12px; }
.expand-block h4 { margin: 0 0 8px; font-size: 13px; color: #1e857b; font-weight: 600; }
.path-inline { margin: 10px 0 4px; padding: 10px 12px; background: #fff; border: 1px dashed #b9d6d2; border-radius: 6px; }
.path-inline-head { display: flex; gap: 8px; align-items: center; margin-bottom: 6px; font-size: 13px; font-weight: 600; color: #1e857b; }
.muted { color: #8a9a99; font-size: 12px; }
.blocked { color: #e4562f; font-size: 12px; }
.blocked-text { color: #e4562f; font-size: 12.5px; }
.ok-text { color: #2a9d5f; font-size: 12.5px; }
.cycle-text { color: #c77a12; font-size: 12.5px; }
.full { color: #e4562f; font-weight: 600; }
.preview-text { margin-left: 10px; }
.batch-tip { font-size: 12px; color: #8a9a99; padding: 0 4px 6px 102px; line-height: 1.6; }
</style>
