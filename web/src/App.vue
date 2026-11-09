<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { api, clearToken, getToken, setToken } from './api'

const menu = [
  { id: 'discover', label: '寻找物品', icon: '⌕', group: 'workspace' },
  { id: 'publish', label: '登记拾得', icon: '＋', group: 'workspace' },
  { id: 'claims', label: '我的认领', icon: '▤', group: 'workspace' },
  { id: 'tasks', label: '审核待办', icon: '✓', group: 'manage' },
  { id: 'pickup', label: '现场交接', icon: '↗', group: 'manage' },
  { id: 'services', label: '微服务目录', icon: '◇', group: 'system' },
  { id: 'workflows', label: '流程管理', icon: '⌘', group: 'system' },
]

const screen = ref('discover')
const user = ref(null)
const loginForm = reactive({ username: '', password: '' })
const loginOpen = ref(false)
const busy = reactive({})
const toast = ref(null)
const booting = ref(true)
let toastTimer

const isAdmin = computed(() => ['ADMIN', 'ROLE_ADMIN'].includes(user.value?.role))
const isStaff = computed(() => ['STAFF', 'ROLE_STAFF'].includes(user.value?.role))
const canHandleTasks = computed(() => isAdmin.value || isStaff.value)
const displayName = computed(() => user.value?.username || user.value?.name || '访客')
const roleName = computed(() => isAdmin.value ? '管理员' : isStaff.value ? '交接人员' : user.value ? '认领用户' : '访客')
const screenName = computed(() => menu.find((entry) => entry.id === screen.value)?.label || '寻找物品')

const filters = reactive({ query: '', category: '', status: '' })
const items = ref([])
const selectedItem = ref(null)
const detailOpen = ref(false)
const claimAnswers = reactive({})
const claimDescription = ref('')
const itemClaims = ref([])
const selectedDisputeClaims = ref([])
const disputeReason = ref('')
const disposal = reactive({ reason: '', destination: '' })
const categories = [
  { value: 'ELECTRONICS', label: '数码电子' },
  { value: 'CARDS', label: '证件卡片' },
  { value: 'BOOKS', label: '书籍文具' },
  { value: 'DAILY', label: '日用物品' },
  { value: 'OTHER', label: '其他物品' },
]
const statusLabels = {
  PUBLISHED: '待认领', RESERVED: '待交接', DISPUTED: '争议处理中',
  HANDING_OVER: '交接中', HANDED_OVER: '已领回', DISPOSED: '已处置',
  SUBMITTED: '审核中', APPROVED: '已通过', REJECTED: '未通过',
  COMPLETED: '已完成', PENDING: '处理中',
}
const newItem = reactive({
  title: '', category: 'ELECTRONICS', publicDescription: '', foundLocation: '',
  foundAt: new Date(Date.now() - new Date().getTimezoneOffset() * 60000).toISOString().slice(0, 16),
  valuable: false,
  hiddenClues: [{ question: '', answer: '' }],
})

const myClaims = ref([])
const selectedClaim = ref(null)
const claimPickupCode = ref(null)
const tasks = ref([])
const selectedTask = ref(null)
const reviewEvidences = ref([])
const taskDecision = reactive({ decision: 'APPROVE', reason: '', winningClaimId: '', destination: '' })
const pickupForm = reactive({ claimId: '', code: '' })
const services = ref([])
const serviceName = ref('')
const definitions = ref([])
const processes = ref([])
const deploymentFile = ref(null)

const questions = computed(() => {
  const item = selectedItem.value || {}
  const list = item.verificationQuestions || item.clueQuestions || item.questions || []
  return Array.isArray(list) ? list.map((question) => typeof question === 'string' ? question : question.question).filter(Boolean) : []
})
const publicCount = computed(() => items.value.filter((item) => item.status === 'PUBLISHED').length)
const recoveredCount = computed(() => items.value.filter((item) => item.status === 'HANDED_OVER').length)

function listOf(data, keys = []) {
  if (Array.isArray(data)) return data
  for (const key of [...keys, 'content', 'items', 'data', 'records']) {
    if (Array.isArray(data?.[key])) return data[key]
  }
  return []
}

function notice(message, type = 'success') {
  clearTimeout(toastTimer)
  toast.value = { message, type }
  toastTimer = setTimeout(() => { toast.value = null }, 4500)
}

async function perform(key, fn, success) {
  busy[key] = true
  try {
    const result = await fn()
    if (success) notice(success)
    return result
  } catch (error) {
    notice(error.message || '操作失败', 'error')
    return null
  } finally {
    busy[key] = false
  }
}

function needLogin() {
  if (user.value) return true
  loginOpen.value = true
  notice('请先登录以继续操作', 'error')
  return false
}

async function login() {
  if (!loginForm.username || !loginForm.password) return notice('请输入用户名和密码', 'error')
  const result = await perform('login', () => api.login(loginForm), '登录成功')
  if (!result?.token) return
  setToken(result.token)
  user.value = result.user || await api.me()
  loginForm.password = ''
  loginOpen.value = false
  await refreshCurrent()
}

async function demoLogin(username, password) {
  loginForm.username = username
  loginForm.password = password
  await login()
}

function logout() {
  clearToken()
  user.value = null
  selectedClaim.value = null
  tasks.value = []
  services.value = []
  definitions.value = []
  screen.value = 'discover'
  notice('已退出登录')
}

async function refreshItems() {
  const result = await perform('items', () => api.items(filters))
  if (result !== null) items.value = listOf(result)
}

async function openItem(item) {
  selectedItem.value = item
  detailOpen.value = true
  claimDescription.value = ''
  Object.keys(claimAnswers).forEach((key) => delete claimAnswers[key])
  selectedDisputeClaims.value = []
  itemClaims.value = []
  const result = await perform('itemDetail', () => api.item(item.id))
  if (result) selectedItem.value = result
  if (isAdmin.value) await loadItemClaims(item.id)
}

async function loadItemClaims(itemId) {
  const result = await perform('itemClaims', () => api.claims({ itemId }))
  if (result !== null) itemClaims.value = listOf(result)
}

function addClue() { newItem.hiddenClues.push({ question: '', answer: '' }) }
function removeClue(index) { if (newItem.hiddenClues.length > 1) newItem.hiddenClues.splice(index, 1) }

async function submitItem() {
  if (!needLogin()) return
  if (!canHandleTasks.value) return notice('只有管理员或交接人员可以登记拾得物品', 'error')
  if (!newItem.title.trim() || !newItem.foundLocation.trim() || !newItem.publicDescription.trim()) {
    return notice('请填写物品名称、公开描述和拾得地点', 'error')
  }
  const clues = newItem.hiddenClues.map((clue) => ({ question: clue.question.trim(), answer: clue.answer.trim() }))
  if (clues.some((clue) => !clue.question || !clue.answer)) return notice('请填写所有核验问题和答案', 'error')
  const payload = {
    ...newItem,
    title: newItem.title.trim(),
    publicDescription: newItem.publicDescription.trim(),
    foundLocation: newItem.foundLocation.trim(),
    foundAt: new Date(newItem.foundAt).toISOString(),
    hiddenClues: clues,
  }
  const result = await perform('createItem', () => api.createItem(payload), '拾得物品已登记')
  if (!result) return
  newItem.title = ''
  newItem.publicDescription = ''
  newItem.foundLocation = ''
  newItem.valuable = false
  newItem.hiddenClues = [{ question: '', answer: '' }]
  screen.value = 'discover'
  await refreshItems()
}

async function submitClaim() {
  if (!needLogin() || !selectedItem.value) return
  if (!claimDescription.value.trim()) return notice('请填写认领描述', 'error')
  if (questions.value.some((question) => !claimAnswers[question]?.trim())) {
    return notice('请回答全部核验问题', 'error')
  }
  const result = await perform('createClaim', () => api.createClaim({
    itemId: selectedItem.value.id,
    description: claimDescription.value.trim(),
    answers: { ...claimAnswers },
  }), '认领申请已提交，请等待审核')
  if (!result) return
  detailOpen.value = false
  screen.value = 'claims'
  await loadClaims()
}

async function loadClaims() {
  if (!user.value) return
  const result = await perform('claims', () => api.claims())
  if (result !== null) myClaims.value = listOf(result)
}

async function openClaim(claim) {
  selectedClaim.value = claim
  claimPickupCode.value = null
  const result = await perform('claimDetail', () => api.claim(claim.id || claim.claimId))
  if (result) selectedClaim.value = result
}

async function fetchClaimPickupCode() {
  if (!selectedClaim.value) return
  claimPickupCode.value = await perform('claimPickupCode', () => api.pickupCode(claimIdOf(selectedClaim.value)))
}

async function loadTasks() {
  if (!canHandleTasks.value) return
  const result = await perform('tasks', () => api.tasks())
  if (result !== null) tasks.value = listOf(result)
}

async function openTask(task) {
  selectedTask.value = task
  reviewEvidences.value = []
  taskDecision.decision = 'APPROVE'
  taskDecision.reason = ''
  taskDecision.winningClaimId = ''
  taskDecision.destination = ''
  if (isAdmin.value) {
    const ids = [...new Set([
      ...(Array.isArray(task.variables?.claimIds) ? task.variables.claimIds : []),
      task.variables?.claimId,
    ].filter(Boolean).map(String))]
    if (ids.length) {
      const result = await perform('reviewEvidence', () => Promise.all(ids.map(async (claimId) => ({ claimId, ...await api.reviewEvidence(claimId) }))))
      if (selectedTask.value?.id === task.id && result) reviewEvidences.value = result
    }
  }
}

async function completeTask() {
  if (!selectedTask.value) return
  const payload = {
    decision: taskDecision.decision,
    reason: taskDecision.reason.trim(),
  }
  if (taskDecision.winningClaimId) payload.winningClaimId = taskDecision.winningClaimId.trim()
  if (taskDecision.destination.trim()) payload.destination = taskDecision.destination.trim()
  const result = await perform('completeTask', () => api.completeTask(selectedTask.value.id, payload), '任务已处理')
  if (result === null) return
  selectedTask.value = null
  await Promise.all([loadTasks(), refreshItems()])
}

async function startDispute() {
  if (selectedDisputeClaims.value.length < 2) return notice('请选择至少两份申请', 'error')
  const result = await perform('dispute', () => api.dispute({
    itemId: selectedItem.value.id,
    claimIds: selectedDisputeClaims.value,
    reason: disputeReason.value.trim(),
  }), '争议流程已发起，交接已暂停')
  if (result === null) return
  disputeReason.value = ''
  selectedDisputeClaims.value = []
  await openItem(selectedItem.value)
}

async function startDisposal() {
  if (!disposal.reason.trim() || !disposal.destination.trim()) return notice('请填写处置原因和移交去向', 'error')
  const result = await perform('disposal', () => api.disposal({
    itemId: selectedItem.value.id,
    reason: disposal.reason.trim(),
    destination: disposal.destination.trim(),
  }), '无人认领处置流程已发起')
  if (result === null) return
  disposal.reason = ''
  disposal.destination = ''
  await openItem(selectedItem.value)
}

async function redeemPickup() {
  if (!pickupForm.claimId || !pickupForm.code) return notice('请输入申请编号和领取码', 'error')
  const result = await perform('redeemPickup', () => api.redeemPickup(pickupForm.claimId, pickupForm.code.trim()), '领取码核销成功，交接已记录')
  if (result === null) return
  pickupForm.code = ''
  await Promise.all([loadTasks(), refreshItems()])
}

async function loadServices() {
  if (!isAdmin.value) return
  const result = await perform('services', () => api.services())
  if (result !== null) services.value = listOf(result, ['services'])
}

async function registerService() {
  if (!serviceName.value.trim()) return notice('请输入微服务名称', 'error')
  const result = await perform('registerService', () => api.registerService(serviceName.value.trim()), '微服务已纳管')
  if (result === null) return
  serviceName.value = ''
  await loadServices()
}

async function toggleService(service) {
  const name = service.serviceName || service.name
  const result = await perform(`service-${name}`, () => api.setServiceEnabled(name, !service.enabled), '服务状态已更新')
  if (result !== null) await loadServices()
}

async function loadWorkflows() {
  if (!isAdmin.value) return
  const [definitionResult, processResult] = await Promise.all([
    perform('definitions', () => api.definitions()),
    perform('processes', () => api.processes()),
  ])
  if (definitionResult !== null) definitions.value = listOf(definitionResult, ['definitions'])
  if (processResult !== null) processes.value = listOf(processResult, ['processes'])
}

async function uploadDefinition() {
  if (!deploymentFile.value) return notice('请选择 .bpmn 或 .xml 文件', 'error')
  const result = await perform('upload', () => api.uploadDefinition(deploymentFile.value), '流程定义已动态部署')
  if (result === null) return
  deploymentFile.value = null
  const input = document.getElementById('bpmn-file')
  if (input) input.value = ''
  await loadWorkflows()
}

async function deployV2() {
  const result = await perform('deployV2', () => api.deployDemoV2(), '贵重物品双人复核流程已部署')
  if (result !== null) await loadWorkflows()
}

async function refreshCurrent() {
  if (screen.value === 'discover') await refreshItems()
  if (screen.value === 'claims') await loadClaims()
  if (screen.value === 'tasks') await loadTasks()
  if (screen.value === 'services') await loadServices()
  if (screen.value === 'workflows') await loadWorkflows()
}

function navigate(target) {
  if (['publish', 'claims'].includes(target) && !needLogin()) return
  if (target === 'publish' && !canHandleTasks.value) return notice('登记拾得物品需要管理员或交接人员身份', 'error')
  if (target === 'tasks' && !canHandleTasks.value) return
  if (target === 'pickup' && !isStaff.value) return
  if (['services', 'workflows'].includes(target) && !isAdmin.value) return
  screen.value = target
  detailOpen.value = false
  selectedClaim.value = null
  selectedTask.value = null
  refreshCurrent()
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

function visibleMenu(entry) {
  if (entry.id === 'publish') return canHandleTasks.value
  if (entry.id === 'claims') return !!user.value
  if (entry.id === 'tasks') return canHandleTasks.value
  if (entry.id === 'pickup') return isStaff.value
  if (['services', 'workflows'].includes(entry.id)) return isAdmin.value
  return true
}

function scrollToItems() { document.getElementById('item-section')?.scrollIntoView({ behavior: 'smooth' }) }

function categoryLabel(category) { return categories.find((entry) => entry.value === category)?.label || category || '未分类' }
function statusLabel(status) { return statusLabels[status] || status || '未知状态' }
function displayDate(value) {
  if (!value) return '未记录'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? String(value) : new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(date)
}
function itemIdOf(claim) { return claim?.itemId || claim?.item?.id || claim?.variables?.itemId }
function claimIdOf(claim) { return claim?.id || claim?.claimId }
function taskType(task) {
  const labels = {
    initialReview: '归属初审', secondReview: '贵重物品复核', confirmPickup: '交接确认',
    disputeReview: '争议裁决', disposalReview: '无人认领处置审核',
  }
  return labels[task?.taskDefinitionKey] || task?.name || '待处理任务'
}
function evidenceRows(bundle) {
  const expected = bundle?.clues?.clues || {}
  const provided = bundle?.evidence?.answers || {}
  return Object.keys(expected).map((question) => ({ question, expected: expected[question], provided: provided[question] || '未填写' }))
}

onMounted(async () => {
  await refreshItems()
  if (getToken()) {
    try {
      user.value = await api.me()
    } catch {
      clearToken()
    }
  }
  booting.value = false
})
</script>

<template>
  <div class="app-shell">
    <aside class="sidebar">
      <button class="brand" type="button" @click="navigate('discover')" aria-label="返回首页">
        <span class="brand-mark"><span></span><span></span><span></span><span></span></span>
        <span><strong>拾光</strong><small>LOST & FOUND</small></span>
      </button>
      <div class="side-intro">让每一次失而复得<br />都有迹可循。</div>

      <nav class="side-nav" aria-label="主导航">
        <template v-for="group in [{ id: 'workspace', label: '日常使用' }, { id: 'manage', label: '业务办理' }, { id: 'system', label: '系统管理' }]" :key="group.id">
          <div v-if="menu.some((entry) => entry.group === group.id && visibleMenu(entry))" class="nav-group-label">{{ group.label }}</div>
          <button v-for="entry in menu.filter((item) => item.group === group.id && visibleMenu(item))" :key="entry.id"
            type="button" class="nav-item" :class="{ active: screen === entry.id }" @click="navigate(entry.id)">
            <span class="nav-icon">{{ entry.icon }}</span><span>{{ entry.label }}</span>
            <span v-if="entry.id === 'tasks' && tasks.length" class="nav-badge">{{ tasks.length }}</span>
          </button>
        </template>
      </nav>

      <div class="sidebar-footer">
        <div class="sidebar-foot-icon">✦</div>
        <div><strong>每件物品，都值得回家</strong><span>隐私核验 · 人工裁决 · 安全交接</span></div>
      </div>
    </aside>

    <div class="main-area">
      <header class="topbar">
        <div class="breadcrumb"><span>拾光系统</span><span class="crumb-separator">/</span><strong>{{ screenName }}</strong></div>
        <div class="top-actions">
          <span class="system-pill"><span class="system-dot"></span>失物归属核验平台</span>
          <button v-if="user" class="account-button" type="button" @click="logout" title="点击退出登录">
            <span class="avatar">{{ displayName.slice(0, 1).toUpperCase() }}</span>
            <span class="account-text"><strong>{{ displayName }}</strong><small>{{ roleName }} · 退出</small></span>
          </button>
          <button v-else class="button button-small button-dark" type="button" @click="loginOpen = true">登录系统</button>
        </div>
      </header>

      <main class="content">
        <template v-if="screen === 'discover'">
          <section class="hero">
            <div class="hero-copy">
              <div class="eyebrow light"><span class="eyebrow-line"></span> CAMPUS LOST & FOUND</div>
              <h1>让遗失的物品，<br /><em>找到回家的路。</em></h1>
              <p>查看公开线索，提交认领申请。隐私线索由专人核验，每一步都有记录。</p>
              <button v-if="canHandleTasks" class="button button-light" type="button" @click="navigate('publish')">＋ 登记拾得物品 <span>↗</span></button>
              <button v-else class="button button-light" type="button" @click="scrollToItems">浏览待认领物品 <span>↗</span></button>
            </div>
            <div class="hero-art" aria-hidden="true"><div class="orbit orbit-one"></div><div class="orbit orbit-two"></div><div class="hero-symbol">✦</div><div class="hero-art-label">FOUND<br />WITH<br />CARE</div></div>
          </section>

          <section class="stats-grid" aria-label="物品统计">
            <div class="stat-card"><span class="stat-icon green">▣</span><div><span>物品总数</span><strong>{{ items.length }}</strong></div><small>全部登记记录</small></div>
            <div class="stat-card"><span class="stat-icon amber">◇</span><div><span>正在寻找主人</span><strong>{{ publicCount }}</strong></div><small>可申请认领</small></div>
            <div class="stat-card"><span class="stat-icon blue">↗</span><div><span>成功交接</span><strong>{{ recoveredCount }}</strong></div><small>已回到主人身边</small></div>
          </section>

          <section id="item-section" class="section-block">
            <div class="section-heading"><div><span class="eyebrow">DISCOVER</span><h2>寻找遗失的物品</h2><p>公开信息用于查找，核验答案不会在这里展示。</p></div><button class="text-button" type="button" @click="refreshItems">{{ busy.items ? '加载中…' : '刷新列表 ↻' }}</button></div>
            <form class="filter-bar" @submit.prevent="refreshItems">
              <label class="search-field"><span class="search-icon">⌕</span><input v-model="filters.query" placeholder="搜索名称、描述或关键字" aria-label="搜索物品" /></label>
              <select v-model="filters.category" aria-label="按分类筛选"><option value="">全部分类</option><option v-for="category in categories" :key="category.value" :value="category.value">{{ category.label }}</option></select>
              <select v-model="filters.status" aria-label="按状态筛选"><option value="">全部状态</option><option value="PUBLISHED">待认领</option><option value="RESERVED">待交接</option><option value="DISPUTED">争议处理中</option><option value="HANDED_OVER">已领回</option></select>
              <button class="button button-dark" type="submit">筛选物品</button>
            </form>
            <div v-if="busy.items && !items.length" class="empty-state"><span>⌛</span><h3>正在加载物品</h3></div>
            <div v-else-if="!items.length" class="empty-state"><span>◌</span><h3>还没有符合条件的物品</h3><p>试试调整筛选条件，或等待工作人员登记拾得物品。</p><button v-if="canHandleTasks" class="button button-outline" type="button" @click="navigate('publish')">登记拾得物品</button></div>
            <div v-else class="item-grid">
              <button v-for="item in items" :key="item.id" class="item-card" type="button" @click="openItem(item)">
                <div class="item-visual" :class="`category-${(item.category || 'OTHER').toLowerCase()}`"><span>{{ item.category === 'ELECTRONICS' ? '⌁' : item.category === 'CARDS' ? '▤' : item.category === 'BOOKS' ? '▥' : item.category === 'DAILY' ? '◈' : '✦' }}</span><span class="item-number">#{{ item.id }}</span></div>
                <div class="item-body"><div class="item-tag-row"><span class="tag">{{ categoryLabel(item.category) }}</span><span class="status" :class="`status-${(item.status || '').toLowerCase()}`">{{ statusLabel(item.status) }}</span></div><h3>{{ item.title }}</h3><p>{{ item.publicDescription }}</p><div class="item-meta"><span>⌖ {{ item.foundLocation }}</span><span>{{ displayDate(item.foundAt) }}</span></div></div>
              </button>
            </div>
          </section>
        </template>

        <template v-else-if="screen === 'publish'">
          <section class="page-title"><span class="eyebrow">REPORT A FIND</span><h1>登记拾得物品</h1><p>只公开必要信息。能够证明归属的细节请填在隐私核验区。</p></section>
          <div class="form-layout"><form class="panel form-panel" @submit.prevent="submitItem"><div class="panel-heading"><span class="panel-index">01</span><div><h2>公开信息</h2><p>所有访客都可以查看以下内容。</p></div></div>
            <div class="form-grid"><label class="field"><span>物品名称 <b>*</b></span><input v-model="newItem.title" maxlength="100" placeholder="例如：黑色耳机盒" required /></label><label class="field"><span>物品分类 <b>*</b></span><select v-model="newItem.category"><option v-for="category in categories" :key="category.value" :value="category.value">{{ category.label }}</option></select></label><label class="field"><span>拾得地点 <b>*</b></span><input v-model="newItem.foundLocation" maxlength="150" placeholder="例如：教学楼 A 座一层" required /></label><label class="field"><span>拾得时间 <b>*</b></span><input v-model="newItem.foundAt" type="datetime-local" required /></label></div>
            <label class="field"><span>公开描述 <b>*</b></span><textarea v-model="newItem.publicDescription" rows="3" maxlength="500" placeholder="描述外观即可，请不要写出品牌、刻字等专属线索。" required></textarea></label>
            <div class="form-divider"></div><div class="panel-heading"><span class="panel-index green-index">02</span><div><h2>隐私核验线索</h2><p>访客只会看到问题，答案仅供授权审核人员使用。</p></div></div>
            <div v-for="(clue, index) in newItem.hiddenClues" :key="index" class="clue-row"><span class="clue-number">线索 {{ String(index + 1).padStart(2, '0') }}</span><label class="field"><span>核验问题 <b>*</b></span><input v-model="clue.question" placeholder="例如：耳机盒内部刻了什么字？" required /></label><label class="field"><span>正确答案 <b>*</b></span><input v-model="clue.answer" placeholder="仅管理员可用于核验" required /></label><button v-if="newItem.hiddenClues.length > 1" class="icon-button danger" type="button" aria-label="删除线索" @click="removeClue(index)">×</button></div>
            <button class="link-button" type="button" @click="addClue">＋ 添加一条核验线索</button>
            <div class="form-divider"></div><label class="check-row"><input v-model="newItem.valuable" type="checkbox" /><span><strong>标记为贵重物品</strong><small>部署新版流程后，贵重物品会增加第二位管理员复核。</small></span></label>
            <div class="form-actions"><button class="button button-outline" type="button" @click="navigate('discover')">取消</button><button class="button button-dark" type="submit" :disabled="busy.createItem">{{ busy.createItem ? '登记中…' : '完成登记 ↗' }}</button></div>
          </form><aside class="info-aside"><span class="aside-icon">✦</span><h3>保护真正的失主</h3><p>公开描述帮助失主找到物品；隐私问题和答案由核验服务单独保存。认领人回答后，管理员进行人工审核。</p><div class="aside-rule"></div><small>建议填写仅失主知道、但不会暴露身份的细节，例如内部刻字、配件特征或存放内容。</small></aside></div>
        </template>

        <template v-else-if="screen === 'claims'">
          <section class="page-title with-action"><div><span class="eyebrow">MY CLAIMS</span><h1>我的认领</h1><p>查看申请进度和归属核验结果。</p></div><button class="button button-dark" type="button" @click="navigate('discover')">寻找物品 ↗</button></section>
          <div v-if="!myClaims.length" class="panel empty-state"><span>▤</span><h3>尚未提交认领申请</h3><p>浏览公开物品，找到疑似遗失物后即可申请。</p><button class="button button-outline" type="button" @click="navigate('discover')">去寻找物品</button></div>
          <div v-else class="table-panel"><div class="table-top"><strong>申请记录</strong><button class="text-button" type="button" @click="loadClaims">刷新 ↻</button></div><div class="table-scroll"><table><thead><tr><th>申请编号</th><th>物品编号</th><th>认领说明</th><th>状态</th><th>提交时间</th><th></th></tr></thead><tbody><tr v-for="claim in myClaims" :key="claimIdOf(claim)"><td class="mono">#{{ claimIdOf(claim) }}</td><td>#{{ itemIdOf(claim) }}</td><td class="truncate-cell">{{ claim.description || '—' }}</td><td><span class="status" :class="`status-${(claim.status || '').toLowerCase()}`">{{ statusLabel(claim.status) }}</span></td><td>{{ displayDate(claim.createdAt) }}</td><td><button class="row-link" type="button" @click="openClaim(claim)">查看详情 ↗</button></td></tr></tbody></table></div></div>
          <div v-if="selectedClaim" class="panel detail-inline"><div class="panel-heading"><span class="panel-index">#{{ String(claimIdOf(selectedClaim)).slice(0, 8) }}</span><div><h2>认领申请详情</h2><p>申请记录与流程状态</p></div><button class="icon-button close-inline" type="button" @click="selectedClaim = null">×</button></div><div class="fact-grid"><div><span>物品编号</span><strong>#{{ itemIdOf(selectedClaim) }}</strong></div><div><span>当前状态</span><strong>{{ statusLabel(selectedClaim.status) }}</strong></div><div><span>申请时间</span><strong>{{ displayDate(selectedClaim.createdAt) }}</strong></div><div><span>流程编号</span><strong class="mono">{{ selectedClaim.processInstanceId || '—' }}</strong></div></div><p class="detail-note">{{ selectedClaim.description }}</p><div v-if="selectedClaim.status === 'APPROVED' || selectedClaim.status === 'COMPLETED'" class="claim-code-area"><button class="button button-outline" type="button" :disabled="busy.claimPickupCode" @click="fetchClaimPickupCode">查看一次性领取码</button><div v-if="claimPickupCode" class="code-result"><span>请在现场向交接员出示</span><strong class="mono">{{ claimPickupCode.code }}</strong><small>有效期至 {{ displayDate(claimPickupCode.expiresAt) }}</small></div></div></div>
        </template>

        <template v-else-if="screen === 'tasks'">
          <section class="page-title with-action"><div><span class="eyebrow">TASK INBOX</span><h1>审核待办</h1><p>归属审核、争议裁决与无人认领处置都由工作流驱动。</p></div><button class="button button-outline" type="button" @click="loadTasks">刷新任务 ↻</button></section>
          <div class="work-layout"><div class="task-list panel"><div class="table-top"><strong>待处理任务 <span class="count-pill">{{ tasks.length }}</span></strong></div><div v-if="!tasks.length" class="empty-state compact"><span>✓</span><h3>当前没有待办任务</h3><p>新申请或处置流程发起后会出现在这里。</p></div><button v-for="task in tasks" :key="task.id" class="task-card" :class="{ selected: selectedTask?.id === task.id }" type="button" @click="openTask(task)"><span class="task-symbol">{{ task.taskDefinitionKey === 'disputeReview' ? '⚖' : task.taskDefinitionKey === 'disposalReview' ? '↗' : '✓' }}</span><span class="task-card-content"><strong>{{ taskType(task) }}</strong><small>物品 #{{ task.variables?.itemId || '—' }} · 申请 #{{ task.variables?.claimId || '—' }}</small><small class="mono">任务 {{ task.id }}</small></span><span class="task-arrow">→</span></button></div>
            <div v-if="selectedTask" class="panel task-detail"><div class="panel-heading"><span class="panel-index green-index">✓</span><div><h2>{{ taskType(selectedTask) }}</h2><p>{{ isStaff ? '核对领取码后完成现场交接。' : '请查看相关申请与核验信息后作出决定。' }}</p></div></div><div class="fact-grid"><div><span>任务编号</span><strong class="mono">{{ selectedTask.id }}</strong></div><div><span>流程实例</span><strong class="mono break-all">{{ selectedTask.processInstanceId }}</strong></div><div><span>物品编号</span><strong>#{{ selectedTask.variables?.itemId || '—' }}</strong></div><div><span>申请编号</span><strong class="mono break-all">{{ selectedTask.variables?.claimId || '—' }}</strong></div></div><div class="task-variable-box"><strong>流程上下文</strong><pre>{{ JSON.stringify(selectedTask.variables || {}, null, 2) }}</pre></div>
              <div v-if="busy.reviewEvidence" class="evidence-loading">正在读取归属核验证据…</div>
              <div v-for="bundle in reviewEvidences" :key="bundle.claimId" class="evidence-panel">
                <div class="evidence-heading"><strong>申请 #{{ bundle.claimId }}</strong><span class="status" :class="bundle.evidence?.preliminaryPassed ? 'status-approved' : 'status-rejected'">{{ bundle.evidence?.preliminaryPassed ? '初步核验通过' : '初步核验未通过' }}</span></div>
                <p class="evidence-description">{{ bundle.claim?.description || '未填写认领描述' }}</p>
                <div class="evidence-score">匹配 {{ bundle.evidence?.matched ?? 0 }} / {{ bundle.evidence?.total ?? 0 }} 条 · {{ Math.round((bundle.evidence?.score || 0) * 100) }}%</div>
                <div v-for="row in evidenceRows(bundle)" :key="row.question" class="evidence-row"><strong>{{ row.question }}</strong><span>申请回答：{{ row.provided }}</span><span>登记线索：{{ row.expected }}</span></div>
              </div>
              <div v-if="isStaff"><p class="staff-task-hint">此任务通过一次性领取码核销完成。请与认领人当面核对物品，再进入交接页面操作。</p><button class="button button-dark full-width" type="button" @click="pickupForm.claimId = String(selectedTask.variables?.claimId || ''); navigate('pickup')">前往现场交接 ↗</button></div><form v-else @submit.prevent="completeTask"><div class="field"><span>处理结果</span><select v-model="taskDecision.decision"><option value="APPROVE">同意 / 通过</option><option value="REJECT">拒绝 / 不通过</option></select></div><label class="field"><span>处理说明</span><textarea v-model="taskDecision.reason" rows="3" placeholder="记录判断依据，便于后续追溯"></textarea></label><label v-if="selectedTask.taskDefinitionKey === 'disputeReview'" class="field"><span>获准申请编号</span><input v-model="taskDecision.winningClaimId" type="text" placeholder="裁决通过时填写申请编号" /></label><label v-if="selectedTask.taskDefinitionKey === 'disposalReview'" class="field"><span>移交去向</span><input v-model="taskDecision.destination" placeholder="例如：学校保卫处" /></label><button class="button button-dark full-width" type="submit" :disabled="busy.completeTask">{{ busy.completeTask ? '处理中…' : '提交处理结果 ↗' }}</button></form></div>
            <div v-else class="panel task-placeholder"><span>⌁</span><h3>选择一项任务</h3><p>左侧选择待办后，可查看流程上下文并办理。</p></div></div>
        </template>

        <template v-else-if="screen === 'pickup'">
          <section class="page-title"><span class="eyebrow">SECURE HANDOVER</span><h1>现场交接</h1><p>核对申请编号与一次性领取码。成功核销后，系统留下完整交接记录。</p></section>
          <div class="form-layout"><form class="panel form-panel" @submit.prevent="redeemPickup"><div class="panel-heading"><span class="panel-index green-index">↗</span><div><h2>核销领取码</h2><p>仅审核通过并进入交接环节的申请可核销。</p></div></div><label class="field"><span>认领申请编号</span><input v-model="pickupForm.claimId" type="text" placeholder="输入完整申请编号" required /></label><label class="field"><span>一次性领取码</span><input v-model="pickupForm.code" autocomplete="off" placeholder="输入认领人出示的领取码" required /></label><button class="button button-dark full-width" type="submit" :disabled="busy.redeemPickup">{{ busy.redeemPickup ? '核销中…' : '确认交接并核销 ↗' }}</button><p class="staff-task-hint">请让认领人登录自己的账号，在「我的认领」中出示一次性领取码。</p></form><aside class="info-aside"><span class="aside-icon">⌁</span><h3>一次核销，安全交接</h3><p>领取码只用于本次现场交接。过期、错误或已经使用过的码不能再次核销。</p><div class="aside-rule"></div><small>发生多人争认时，交接会被暂停，待管理员裁决后才能继续。</small></aside></div>
        </template>

        <template v-else-if="screen === 'services'">
          <section class="page-title with-action"><div><span class="eyebrow">SERVICE CATALOG</span><h1>微服务目录</h1><p>将注册中心发现的实例纳管，控制流程可调用的服务。</p></div><button class="button button-outline" type="button" @click="loadServices">刷新状态 ↻</button></section>
          <form class="panel service-register" @submit.prevent="registerService"><div><strong>纳管新服务</strong><span>输入服务名并验证在线实例</span></div><input v-model="serviceName" placeholder="例如：item-service" aria-label="微服务名称" /><button class="button button-dark" type="submit" :disabled="busy.registerService">{{ busy.registerService ? '注册中…' : '注册服务 ↗' }}</button></form>
          <div class="section-heading mini"><div><h2>已纳管服务</h2><p>禁用后新流程将无法调用对应操作。</p></div></div><div v-if="!services.length" class="panel empty-state"><span>◇</span><h3>暂无纳管服务</h3><p>启动四个业务微服务并输入服务名进行纳管。</p></div><div v-else class="service-grid"><article v-for="service in services" :key="service.serviceName || service.name" class="panel service-card"><div class="service-card-top"><span class="service-glyph">◇</span><span class="health-pill" :class="service.healthy ? 'healthy' : 'unhealthy'"><span></span>{{ service.healthy ? '运行正常' : '实例离线' }}</span></div><h3>{{ service.serviceName || service.name }}</h3><p>在线实例 {{ service.instances?.length ?? service.instances ?? 0 }} · 可用操作 {{ service.operations?.length ?? service.operations ?? 0 }}</p><div v-if="Array.isArray(service.operations) && service.operations.length" class="operation-list"><span v-for="operation in service.operations.slice(0, 4)" :key="operation.name || operation">{{ operation.name || operation }}</span></div><div class="service-card-foot"><span>{{ service.enabled ? '已启用' : '已停用' }}</span><button class="switch" :class="{ on: service.enabled }" type="button" :aria-label="service.enabled ? '停用服务' : '启用服务'" :disabled="busy[`service-${service.serviceName || service.name}`]" @click="toggleService(service)"><span></span></button></div></article></div>
        </template>

        <template v-else-if="screen === 'workflows'">
          <section class="page-title with-action"><div><span class="eyebrow">WORKFLOW ENGINE</span><h1>流程管理</h1><p>上传 BPMN 即可动态部署新版本，运行中的旧实例继续使用原版本。</p></div><button class="button button-outline" type="button" @click="loadWorkflows">刷新 ↻</button></section>
          <div class="deploy-grid"><div class="panel deploy-card"><span class="deploy-icon">⌘</span><h2>上传 BPMN 流程</h2><p>文件经服务操作白名单校验后部署，无需重启服务。</p><input id="bpmn-file" type="file" accept=".bpmn,.bpmn20.xml,.xml" @change="deploymentFile = $event.target.files?.[0] || null" /><button class="button button-dark" type="button" :disabled="busy.upload" @click="uploadDefinition">{{ busy.upload ? '部署中…' : '上传并部署 ↗' }}</button></div><div class="panel deploy-card accent"><span class="deploy-icon">✦</span><h2>演示贵重物品双人复核</h2><p>部署普通认领 v2：贵重物品增加第二位不同管理员复核。</p><button class="button button-light" type="button" :disabled="busy.deployV2" @click="deployV2">{{ busy.deployV2 ? '部署中…' : '一键部署 v2 ↗' }}</button></div></div>
          <div class="table-panel"><div class="table-top"><strong>已部署流程定义 <span class="count-pill">{{ definitions.length }}</span></strong></div><div v-if="!definitions.length" class="empty-state compact"><span>⌘</span><h3>暂无流程定义</h3></div><div v-else class="table-scroll"><table><thead><tr><th>流程名称</th><th>流程 Key</th><th>版本</th><th>部署编号</th><th>定义编号</th></tr></thead><tbody><tr v-for="definition in definitions" :key="definition.id"><td><strong>{{ definition.name || definition.key }}</strong></td><td class="mono">{{ definition.key }}</td><td><span class="version-badge">v{{ definition.version }}</span></td><td class="mono">{{ definition.deploymentId }}</td><td class="mono">{{ definition.id }}</td></tr></tbody></table></div></div>
          <div class="table-panel process-panel"><div class="table-top"><strong>流程实例 <span class="count-pill">{{ processes.length }}</span></strong></div><div v-if="!processes.length" class="empty-state compact"><span>◌</span><h3>暂无流程实例</h3></div><div v-else class="table-scroll"><table><thead><tr><th>实例编号</th><th>流程定义</th><th>物品</th><th>状态</th><th>启动时间</th></tr></thead><tbody><tr v-for="process in processes" :key="process.id"><td class="mono">{{ process.id }}</td><td class="mono">{{ process.processDefinitionKey || process.definitionKey || process.processDefinitionId || '—' }}</td><td>#{{ process.itemId || process.variables?.itemId || '—' }}</td><td><span class="status">{{ process.status || (process.ended ? '已结束' : '运行中') }}</span></td><td>{{ displayDate(process.startTime || process.startedAt) }}</td></tr></tbody></table></div></div>
        </template>
      </main>
    </div>

    <div v-if="detailOpen && selectedItem" class="overlay" @click.self="detailOpen = false"><aside class="drawer" role="dialog" aria-modal="true" :aria-label="selectedItem.title + '详情'"><button class="drawer-close" type="button" aria-label="关闭详情" @click="detailOpen = false">×</button><div class="drawer-hero" :class="`category-${(selectedItem.category || 'OTHER').toLowerCase()}`"><span>{{ selectedItem.category === 'ELECTRONICS' ? '⌁' : '✦' }}</span><small>#{{ selectedItem.id }}</small></div><div class="drawer-body"><div class="item-tag-row"><span class="tag">{{ categoryLabel(selectedItem.category) }}</span><span class="status" :class="`status-${(selectedItem.status || '').toLowerCase()}`">{{ statusLabel(selectedItem.status) }}</span></div><h2>{{ selectedItem.title }}</h2><p class="drawer-description">{{ selectedItem.publicDescription }}</p><div class="fact-grid"><div><span>拾得地点</span><strong>{{ selectedItem.foundLocation }}</strong></div><div><span>拾得时间</span><strong>{{ displayDate(selectedItem.foundAt) }}</strong></div></div><div class="drawer-divider"></div><div class="drawer-section-heading"><span class="eyebrow">VERIFY OWNERSHIP</span><h3>申请认领</h3><p>请描述只有失主知道的细节。答案不会公开。</p></div><form v-if="!isStaff && (selectedItem.status === 'PUBLISHED' || selectedItem.status === 'RESERVED')" @submit.prevent="submitClaim"><label class="field"><span>认领描述 <b>*</b></span><textarea v-model="claimDescription" rows="3" placeholder="请说明您与该物品的关系及专属细节" required></textarea></label><label v-for="(question, index) in questions" :key="question" class="field"><span>核验问题 {{ index + 1 }}：{{ question }} <b>*</b></span><input v-model="claimAnswers[question]" autocomplete="off" placeholder="填写您的答案" required /></label><button class="button button-dark full-width" type="submit" :disabled="busy.createClaim">{{ busy.createClaim ? '提交中…' : '提交认领申请 ↗' }}</button></form><div v-else class="notice-box">{{ isStaff ? '交接人员账号用于登记和现场交接，请切换认领用户账号提交申请。' : '该物品当前暂不接受新的认领申请。' }}</div>
            <template v-if="isAdmin"><div class="drawer-divider"></div><div class="drawer-section-heading"><span class="eyebrow">ADMIN ACTIONS</span><h3>管理员操作</h3><p>争议与处置会创建新的业务流程。</p></div><div class="admin-actions"><div class="admin-action-card"><strong>发起争议认领</strong><p>选择至少两份有效申请，暂停交接并进入人工裁决。</p><div v-if="!itemClaims.length" class="muted-small">此物品暂无申请。</div><label v-for="claim in itemClaims" :key="claimIdOf(claim)" class="claim-choice"><input v-model="selectedDisputeClaims" type="checkbox" :value="String(claimIdOf(claim))" />#{{ claimIdOf(claim) }} · {{ claim.description?.slice(0, 36) || '认领申请' }}</label><textarea v-model="disputeReason" rows="2" placeholder="争议原因"></textarea><button class="button button-outline full-width" type="button" :disabled="busy.dispute || selectedDisputeClaims.length < 2" @click="startDispute">发起争议流程</button></div><div class="admin-action-card"><strong>无人认领处置</strong><p>达到保管期且无有效申请时，发起审核与移交。</p><input v-model="disposal.reason" placeholder="处置原因" /><input v-model="disposal.destination" placeholder="拟移交去向" /><button class="button button-outline full-width" type="button" :disabled="busy.disposal" @click="startDisposal">发起处置流程</button></div></div></template></div></aside></div>

    <div v-if="loginOpen" class="overlay login-overlay" @click.self="loginOpen = false"><div class="login-dialog" role="dialog" aria-modal="true" aria-label="登录系统"><button class="drawer-close" type="button" aria-label="关闭登录" @click="loginOpen = false">×</button><div class="login-symbol">✦</div><span class="eyebrow">WELCOME BACK</span><h2>登录拾光系统</h2><p>使用演示账号体验认领、审核和交接流程。</p><form @submit.prevent="login"><label class="field"><span>用户名</span><input v-model="loginForm.username" autocomplete="username" placeholder="请输入用户名" required /></label><label class="field"><span>密码</span><input v-model="loginForm.password" type="password" autocomplete="current-password" placeholder="请输入密码" required /></label><button class="button button-dark full-width" type="submit" :disabled="busy.login">{{ busy.login ? '登录中…' : '登录系统 ↗' }}</button></form><div class="demo-users"><span>演示账号快速登录</span><div><button type="button" @click="demoLogin('student', 'student123')">学生</button><button type="button" @click="demoLogin('admin1', 'admin123')">管理员 1</button><button type="button" @click="demoLogin('admin2', 'admin123')">管理员 2</button><button type="button" @click="demoLogin('staff', 'staff123')">交接员</button></div></div></div></div>
    <div v-if="toast" class="toast" :class="toast.type" role="status"><span>{{ toast.type === 'error' ? '!' : '✓' }}</span>{{ toast.message }}<button type="button" aria-label="关闭提示" @click="toast = null">×</button></div>
    <div v-if="booting" class="boot-screen"><span class="boot-mark">✦</span>拾光系统正在加载…</div>
  </div>
</template>
