<template>
  <div class="page">
    <el-card shadow="never" class="hero">
      <div class="hero-head">
        <div>
          <h2>学员与课程</h2>
          <p>约课要过五道闸：课程开放、未满员、课时余额够、没重复约过、<b>先修路径已走通</b>。只有「已完成」记录能解锁后续课程，在学 / 旧记录 / 已退课 / 状态缺失都不算。</p>
        </div>
        <div class="hero-actions">
          <el-button @click="openBatch">一键多科报名</el-button>
          <el-button type="primary" @click="openEnroll">单个约课</el-button>
        </div>
      </div>
    </el-card>

    <el-card shadow="never" class="path-card">
      <template #header>
        <div class="list-head">
          <span>学员课程路径（选择学员：可学 / 受阻 / 最短缺口路径一次看清）</span>
          <el-select v-model="pathStudentId" placeholder="选择学员查看路径" size="small" filterable style="width: 240px" @change="loadPaths">
            <el-option v-for="item in students" :key="item.id" :label="`${item.studentNo} ${item.name}`" :value="item.id" />
          </el-select>
        </div>
      </template>
      <el-skeleton v-if="pathLoading" :rows="3" animated />
      <template v-else>
        <div v-if="pathOrphans.length" class="path-orphans">
          <el-tag type="warning" size="small" effect="plain">学习记录关联课程缺失</el-tag>
          <span v-for="o in pathOrphans" :key="o.courseId" class="orphan-item">{{ o.courseNo }} {{ o.title }}</span>
        </div>
        <el-table :data="pathRows" size="small" border>
          <el-table-column prop="courseNo" label="课程编号" width="110" />
          <el-table-column prop="title" label="课程名称" min-width="150" />
          <el-table-column label="学习状态" width="120">
            <template #default="{ row }">
              <el-tag size="small" :type="learningTagType(row.learningStatus)" effect="light">
                {{ row.learningStatusName }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="路径结论" width="110">
            <template #default="{ row }">
              <el-tag size="small" :type="conclusionTagType(row.conclusion)" effect="dark">
                {{ row.conclusionName }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="最短缺口路径（深→浅）" min-width="260">
            <template #default="{ row }">
              <span v-if="!row.missingPath.length" class="muted">—</span>
              <template v-else>
                <el-tag
                  v-for="(node, idx) in row.missingPath"
                  :key="node.courseId"
                  size="small"
                  :type="node.placeholder ? 'danger' : node.open ? 'warning' : 'info'"
                  effect="plain"
                  class="gap-tag"
                >
                  <span v-if="idx > 0" class="gap-arrow">→</span>{{ node.courseNo }}
                </el-tag>
              </template>
            </template>
          </el-table-column>
          <el-table-column label="先修要求" min-width="200">
            <template #default="{ row }">
              <span v-if="!row.requirements.length" class="muted">无先修，默认可学</span>
              <div v-for="(req, ri) in row.requirements" :key="ri" class="req-line">
                <el-tag size="small" :type="req.requireType === 'ELECTIVE' ? 'success' : ''" effect="plain">
                  {{ req.requireType === 'ELECTIVE' ? `任一(组${req.groupNo})` : '全部' }}
                </el-tag>
                <span v-for="c in req.courses" :key="c.courseId" class="req-course"
                  :class="{ placeholder: c.placeholder, closed: !c.open && !c.placeholder }">
                  {{ c.courseNo }}
                </span>
              </div>
            </template>
          </el-table-column>
        </el-table>
      </template>
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

                <div class="detail-path">
                  <div class="detail-path-head">
                    <h4>学员路径视图</h4>
                    <el-select
                      :model-value="detailPathStudent[row.id] || pathStudentId"
                      size="small" filterable placeholder="选择学员" style="width: 220px"
                      @update:model-value="(v) => onDetailPathStudent(row.id, v)"
                    >
                      <el-option v-for="item in students" :key="item.id"
                        :label="`${item.studentNo} ${item.name}`" :value="item.id" />
                    </el-select>
                  </div>
                  <el-skeleton v-if="detailPathLoading[row.id]" :rows="2" animated />
                  <template v-else-if="detailPaths[row.id]">
                    <div class="detail-path-conclusion">
                      <el-tag size="small" :type="conclusionTagType(detailPaths[row.id].conclusion)" effect="dark">
                        {{ detailPaths[row.id].conclusionName }}
                      </el-tag>
                      <el-tag size="small" :type="learningTagType(detailPaths[row.id].learningStatus)" effect="light">
                        {{ detailPaths[row.id].learningStatusName }}
                      </el-tag>
                      <span v-if="detailPaths[row.id].cyclePath && detailPaths[row.id].cyclePath.length" class="cycle-line">
                        检测到循环依赖，已终止遍历：{{ detailPaths[row.id].cyclePath.join(' → ') }}
                      </span>
                      <span v-if="detailPaths[row.id].dataGap" class="gap-line">
                        该路线存在数据缺口（关联课程或学习记录缺失，已保留占位）
                      </span>
                    </div>
                    <div v-if="detailPaths[row.id].missingPath.length" class="detail-gap">
                      <span class="muted">最短缺口路径：</span>
                      <el-tag
                        v-for="(node, idx) in detailPaths[row.id].missingPath"
                        :key="node.courseId"
                        size="small"
                        :type="node.placeholder ? 'danger' : node.open ? 'warning' : 'info'"
                        effect="plain"
                        class="gap-tag"
                      >
                        <span v-if="idx > 0" class="gap-arrow">→</span>{{ node.courseNo }} {{ node.title }}
                      </el-tag>
                    </div>
                    <div v-else-if="detailPaths[row.id].conclusion === 'READY'" class="muted">
                      先修已全部满足，可以约课。
                    </div>
                  </template>
                </div>
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
        <el-table-column label="路径（所选学员）" width="150">
          <template #default="{ row }">
            <template v-if="pathStudentId && pathMap[row.id]">
              <el-tag :type="conclusionTagType(pathMap[row.id].conclusion)" size="small" effect="dark">
                {{ pathMap[row.id].conclusionName }}
              </el-tag>
              <div v-if="pathMap[row.id].missingPath.length" class="cell-gap">
                缺 {{ pathMap[row.id].missingCount }} 门：{{ pathMap[row.id].missingPath.map(n => n.courseNo).join('→') }}
              </div>
              <div v-if="pathMap[row.id].cyclePath && pathMap[row.id].cyclePath.length" class="cell-cycle">
                循环：{{ pathMap[row.id].cyclePath.join('→') }}
              </div>
            </template>
            <span v-else class="muted">选择学员后解析</span>
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
        <el-table-column prop="studentName" label="学员" width="110" />
        <el-table-column prop="courseTitle" label="课程" min-width="160" />
        <el-table-column prop="teacherName" label="教师" width="100" />
        <el-table-column prop="enrollDate" label="约课日期" width="130" />
        <el-table-column prop="lessons" label="消耗课时" width="100" />
        <el-table-column label="剩余余额" width="110">
          <template #default="{ row }">{{ row.lessonBalance }} 节</template>
        </el-table-column>
        <el-table-column label="名额" width="100">
          <template #default="{ row }">{{ row.enrolledCount }} / {{ row.capacity }}</template>
        </el-table-column>
        <el-table-column prop="statusName" label="状态" width="90" />
      </el-table>
    </el-card>

    <el-dialog v-model="enrollVisible" title="单个约课" width="500">
      <el-form :model="enrollForm" label-width="92px">
        <el-form-item label="学员">
          <el-select v-model="enrollForm.studentId" filterable placeholder="选择学员" style="width: 100%">
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
              v-for="item in courses"
              :key="item.id"
              :label="`${item.courseNo} ${item.title}（${item.enrolledCount}/${item.capacity}）`"
              :value="item.id"
            />
          </el-select>
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

    <el-dialog v-model="batchVisible" title="一键多科报名" width="520">
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
          <el-select v-model="batchForm.courseIds" multiple placeholder="最多 3 门" style="width: 100%">
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
      <div class="batch-tip">本操作会一次性写入多条约课记录，并一次性扣减课时余额。</div>
      <template #footer>
        <el-button @click="batchVisible = false">取消</el-button>
        <el-button type="primary" @click="submitBatch">确认批量报名</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { courseApi, formatMoney } from '../api'

const students = ref([])
const courses = ref([])
const enrollments = ref([])
const details = reactive({})
const loading = reactive({})
const enrollFilterStudent = ref(null)

// ---------------- 学员课程路径 ----------------
const pathStudentId = ref(null)
const pathBundle = ref(null)
const pathLoading = ref(false)
const detailPathStudent = reactive({})
const detailPaths = reactive({})
const detailPathLoading = reactive({})

const pathRows = computed(() => pathBundle.value?.paths || [])
const pathOrphans = computed(() => pathBundle.value?.orphanEnrollments || [])
const pathMap = computed(() => {
  const map = {}
  for (const p of pathRows.value) {
    map[p.courseId] = p
  }
  return map
})

const conclusionTagType = (c) => ({
  READY: 'success',
  LEARNING: 'primary',
  BLOCKED: 'danger',
  CLOSED_TARGET: 'info',
  CYCLE: 'warning',
  DATA_GAP: 'warning'
}[c] || 'info')

const learningTagType = (s) => ({
  COMPLETED: 'success',
  LEARNING: 'primary',
  NORMAL: 'info',
  CANCELED: 'info',
  UNKNOWN: 'warning',
  NONE: 'info'
}[s] || 'info')

const loadPaths = async () => {
  if (!pathStudentId.value) {
    pathBundle.value = null
    return
  }
  pathLoading.value = true
  try {
    const res = await courseApi.studentPaths(pathStudentId.value)
    pathBundle.value = res.data
    // 已展开的详情行跟随当前学员刷新，保证列表与详情结论一致
    for (const courseId of Object.keys(detailPaths)) {
      const row = pathBundle.value.paths.find((p) => String(p.courseId) === String(courseId))
      if (row) {
        detailPaths[courseId] = row
      }
    }
  } finally {
    pathLoading.value = false
  }
}

const loadDetailPath = async (courseId, studentId) => {
  const sid = studentId || pathStudentId.value
  if (!sid) {
    detailPaths[courseId] = null
    return
  }
  detailPathLoading[courseId] = true
  try {
    const res = await courseApi.coursePath(sid, courseId)
    detailPaths[courseId] = res.data
  } finally {
    detailPathLoading[courseId] = false
  }
}

const onDetailPathStudent = (courseId, studentId) => {
  detailPathStudent[courseId] = studentId
  loadDetailPath(courseId, studentId)
}

const enrollVisible = ref(false)
const enrollForm = reactive({ studentId: null, courseId: null, lessons: 2, enrollDate: '' })
const batchVisible = ref(false)
const batchForm = reactive({ studentId: null, courseIds: [], lessons: 2 })

const loadStudents = async () => {
  const res = await courseApi.students()
  students.value = res.data || []
}

const loadCourses = async () => {
  const res = await courseApi.courses()
  courses.value = res.data || []
}

const loadEnrollments = async () => {
  const res = await courseApi.enrollments({ studentId: enrollFilterStudent.value })
  enrollments.value = res.data || []
}

const onExpand = async (row, expanded) => {
  if (!expanded.length) return
  loading[row.id] = true
  try {
    const res = await courseApi.courseDetail(row.id)
    details[row.id] = res.data
    if (pathStudentId.value) {
      // 列表已有同源结论，直接带出，详情再取一次单课接口做独立校验
      const same = pathBundle.value?.paths.find((p) => p.courseId === row.id)
      if (same) {
        detailPaths[row.id] = same
      }
      loadDetailPath(row.id, pathStudentId.value)
    }
  } finally {
    loading[row.id] = false
  }
}

const refreshAll = async () => {
  Object.keys(details).forEach((key) => delete details[key])
  Object.keys(detailPaths).forEach((key) => delete detailPaths[key])
  await Promise.all([loadStudents(), loadCourses(), loadEnrollments()])
  if (pathStudentId.value) {
    await loadPaths()
  }
}

const openEnroll = () => {
  Object.assign(enrollForm, { studentId: null, courseId: null, lessons: 2, enrollDate: '' })
  enrollVisible.value = true
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

onMounted(async () => {
  await refreshAll()
  // 默认选中主演示学员（ST-8001 韩青梧），打开页面即可看到全部路径结论
  const demo = students.value.find((s) => s.studentNo === 'ST-8001') || students.value[0]
  if (demo) {
    pathStudentId.value = demo.id
    await loadPaths()
  }
})
</script>

<style scoped>
.page { display: flex; flex-direction: column; gap: 16px; }
.hero :deep(.el-card__body) { padding: 18px 20px; }
.hero-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 20px; }
.hero-head h2 { margin: 0 0 6px; font-size: 20px; color: #1e857b; }
.hero-head p { margin: 0; font-size: 13px; color: #7a8a89; max-width: 720px; line-height: 1.6; }
.hero-actions { display: flex; gap: 10px; flex: none; }
.balance-card h3 { margin: 0 0 12px; font-size: 15px; color: #1e857b; }
.balance-card :deep(.el-card__body) { padding: 16px 20px; }
.table-card :deep(.el-card__body) { padding: 0; }
.list-head { display: flex; align-items: center; justify-content: space-between; }
.expand { padding: 14px 18px 18px; background: #f7fbfb; }
.expand-block { margin-top: 12px; }
.expand-block h4 { margin: 0 0 8px; font-size: 13px; color: #1e857b; font-weight: 600; }
.muted { color: #8a9a99; font-size: 12px; }
.blocked { color: #e4562f; font-size: 12px; }
.full { color: #e4562f; font-weight: 600; }
.batch-tip { font-size: 12px; color: #8a9a99; padding: 0 4px 6px 102px; }
.path-card :deep(.el-card__body) { padding: 12px 16px 16px; }
.path-orphans { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-bottom: 10px; }
.orphan-item { font-size: 12px; color: #b76b00; }
.gap-tag { margin: 2px 4px 2px 0; }
.gap-arrow { color: #b08a2e; margin-right: 4px; }
.req-line { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; line-height: 1.9; }
.req-course { font-size: 12px; color: #3f5a57; }
.req-course.placeholder { color: #e4562f; text-decoration: line-through; }
.req-course.closed { color: #8a9a99; text-decoration: line-through; }
.cell-gap { font-size: 11px; color: #b76b00; margin-top: 2px; line-height: 1.4; }
.cell-cycle { font-size: 11px; color: #b76b00; margin-top: 2px; }
.detail-path { margin-top: 14px; border-top: 1px dashed #cfe0de; padding-top: 12px; }
.detail-path-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.detail-path-head h4 { margin: 0; }
.detail-path-conclusion { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin: 10px 0 6px; }
.cycle-line, .gap-line { font-size: 12px; color: #b76b00; }
.detail-gap { display: flex; align-items: center; flex-wrap: wrap; gap: 2px; margin-top: 6px; }
</style>
