<script setup lang="ts">
import {
  Dialog,
  Toast,
  VAlert,
  VButton,
  VCard,
  VLoading,
  VPageHeader,
  VSpace,
  VStatusDot,
  VTag,
} from '@halo-dev/components'
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import RiRobot2Line from '~icons/ri/robot-2-line'
import {
  describeError,
  fullSync,
  getStatus,
  retryFailures,
  testConnection,
  type Connection,
  type Report,
  type RunResult,
  type Status,
} from '../api'

type Action = 'test' | 'sync' | 'confirm' | 'retry'

const POLL_MS = 3000
/** Give up on a run's progress after this many /status failures in a row. */
const MAX_FAILED_POLLS = 5

const status = ref<Status>()
const loadError = ref<string>()
const busy = ref<Action>()
/** The answer to the last button press, shown under the buttons. */
const outcome = ref<{
  title: string
  report?: Report
  error?: string
  waiting?: boolean
  message?: string
}>()
let poller: ReturnType<typeof setInterval> | undefined
let pollInFlight = false
let failedPolls = 0
let waitingTitle = '同步'

const connection = computed<Connection | undefined>(() => status.value?.connection)
const running = computed(() => status.value?.sync.running === true)
/** Buttons stay off while a request is out, and while a sync (maybe a scheduled one) is running. */
const locked = computed(() => busy.value !== undefined || running.value)
const configured = computed(() => status.value?.configured === true)
const guardPending = computed(() => status.value?.sync.guardPending === true)
const last = computed(() => status.value?.sync.last ?? null)
const retryCount = computed(() => status.value?.failures.length ?? 0)

const originProblem = computed(() => {
  const origin = connection.value?.origin
  return origin && !origin.ok ? origin : undefined
})

// ------------------------------------------------------------------ loading

/** Reads the status. Returns false when it could not (the error is shown). */
async function load(check: boolean): Promise<boolean> {
  try {
    const next = await getStatus(check)
    if (!check && status.value) {
      // a poll does not look at MomoBird: keep the last look
      next.connection = status.value.connection
    }
    status.value = next
    loadError.value = undefined
    return true
  } catch (error) {
    loadError.value = describeError(error)
    return false
  }
}

/** What the page shows once a sync it was waiting for has finished. */
function showFinished(title: string) {
  const report = status.value?.sync.last
  if (!report) {
    outcome.value = undefined
    return
  }
  outcome.value = {
    title,
    report,
    error: report.ok ? undefined : (report.error ?? '没有成功'),
  }
}

function startPolling(title: string) {
  if (poller !== undefined) return
  waitingTitle = title
  failedPolls = 0
  poller = setInterval(async () => {
    // a slow answer must not be overtaken by the next tick
    if (pollInFlight) return
    pollInFlight = true
    try {
      if (!(await load(false))) {
        failedPolls += 1
        if (failedPolls >= MAX_FAILED_POLLS) {
          stopPolling()
          outcome.value = {
            title: waitingTitle,
            error: '读不到同步进度（连续失败），同步可能仍在进行。请稍后刷新页面查看结果。',
          }
        }
        return
      }
      failedPolls = 0
      if (!running.value) {
        stopPolling()
        showFinished(waitingTitle)
        await load(true)
      }
    } finally {
      pollInFlight = false
    }
  }, POLL_MS)
}

function stopPolling() {
  if (poller !== undefined) {
    clearInterval(poller)
    poller = undefined
  }
}

onMounted(async () => {
  await load(true)
  if (running.value) startPolling('同步')
})

onBeforeUnmount(stopPolling)

// ------------------------------------------------------------------ actions

async function check() {
  busy.value = 'test'
  try {
    const result = await testConnection()
    if (status.value) {
      status.value.configured = result.configured
      status.value.problems = result.problems
      status.value.connection = result.connection
    }
    if (!result.configured) {
      Toast.warning(result.problems[0] ?? '还没有配置好')
    } else if (result.connection.ok) {
      Toast.success('连接正常')
    } else {
      Toast.error(result.connection.error ?? '连接失败')
    }
  } catch (error) {
    Toast.error(describeError(error))
  } finally {
    busy.value = undefined
  }
}

async function run(action: Action, title: string, call: () => Promise<RunResult>) {
  busy.value = action
  outcome.value = undefined
  try {
    const result = await call()
    if (result.accepted) {
      outcome.value = { title, waiting: true, message: result.message ?? undefined }
      if (status.value) status.value.sync.running = true
      startPolling(title)
      return
    }
    outcome.value = {
      title,
      report: result.report ?? undefined,
      error: result.ok ? undefined : (result.message ?? '没有成功'),
    }
    await load(true)
  } catch (error) {
    outcome.value = { title, error: describeError(error) }
  } finally {
    busy.value = undefined
  }
}

const startFullSync = () => run('sync', '全量同步', () => fullSync(false))
const startRetry = () => run('retry', '重试失败项', () => retryFailures())

function confirmRemovals() {
  const count = last.value?.wouldRemove || last.value?.heldRemovals || 0
  Dialog.warning({
    title: '确认撤下这些文章？',
    description:
      `将从 MomoBird 撤下 ${count > 0 ? count + ' 篇' : '这些'}在 Halo 里已经没有的文章，` +
      '撤下后访客的问答里不会再引用它们。确认要撤下吗？',
    confirmText: '确认撤下',
    confirmType: 'danger',
    onConfirm: () => {
      run('confirm', '确认撤下', () => fullSync(true))
    },
  })
}

// ------------------------------------------------------------------ text

function when(iso: string | null | undefined): string {
  if (!iso) return '—'
  const date = new Date(iso)
  return Number.isNaN(date.getTime()) ? '—' : date.toLocaleString('zh-CN', { hour12: false })
}

function modeText(mode: Report['mode']): string {
  return mode === 'full' ? '全量同步' : mode === 'retry' ? '重试失败项' : '增量同步'
}

function summary(report: Report): string {
  return `推送 ${report.pushed} 篇，未变 ${report.unchanged} 篇，撤下 ${report.removed} 篇，失败 ${report.failed} 篇`
}

const lastText = computed(() => {
  const report = last.value
  if (!report) return '还没有同步过'
  return `${when(report.finishedAt)} ${modeText(report.mode)}${report.ok ? '完成' : '没有完成'}`
})

const countText = computed(() => {
  const sync = status.value?.sync
  const local = sync?.local
  const remote = connection.value?.source?.documentCount ?? sync?.remote
  const left = local === null || local === undefined ? '还没有对账过' : `Halo 应同步 ${local} 篇`
  const right = remote === null || remote === undefined ? '' : `，MomoBird 上有 ${remote} 篇`
  const ready = connection.value?.source?.readyDocumentCount
  const indexed =
    ready !== null &&
    ready !== undefined &&
    remote !== null &&
    remote !== undefined &&
    ready < remote
      ? `（其中 ${ready} 篇已建好索引）`
      : ''
  return left + right + indexed
})

function failureText(f: { kind: string; code: string | null; message: string | null }): string {
  return f.kind === 'refused' ? (f.code ?? '被 MomoBird 拒绝') : `${f.message ?? '等待重试'}`
}
</script>

<template>
  <VPageHeader title="MomoBird">
    <template #icon>
      <RiRobot2Line class="mb-icon" />
    </template>
  </VPageHeader>

  <div class="mb-page">
    <VLoading v-if="!status && !loadError" />

    <VAlert
      v-else-if="!status"
      type="error"
      title="读取状态失败"
      :description="loadError"
      :closable="false"
    />

    <template v-else>
      <VAlert
        v-if="loadError"
        class="mb-block"
        type="warning"
        title="刷新状态失败"
        :description="loadError"
        :closable="false"
      />

      <VAlert
        v-if="!configured"
        class="mb-block"
        type="warning"
        title="还没有配置好"
        :description="
          status.problems.join('；') +
          '。设置在「插件 → MomoBird AI → 设置 → 连接」，改好后回到这里点「测试连接」。'
        "
        :closable="false"
      />

      <VAlert
        v-if="connection?.checked && !connection.ok"
        class="mb-block"
        type="error"
        title="连不上 MomoBird"
        :description="connection.error ?? ''"
        :closable="false"
      />

      <VAlert
        v-if="originProblem"
        class="mb-block"
        type="error"
        title="站点地址对不上，同步已被拦下"
        :closable="false"
      >
        <template #description>
          <p>{{ originProblem.reason }}</p>
          <p class="mb-hint">
            外部访问地址在这里设置：新版 Halo 是「系统设置 → 基本设置 → 外部访问地址」； Halo 2.17
            等旧版本后台没有这一项，要在启动参数或配置文件里设置 <code>halo.external-url</code>。
            改好后点「测试连接」确认。
          </p>
        </template>
      </VAlert>

      <VAlert
        v-if="connection?.plan && !connection.plan.active"
        class="mb-block"
        type="warning"
        title="套餐已到期"
        description="到期后文章不再推送，问答助手也会暂停显示。在 MomoBird 控制台续费或兑换后，下次同步会补上。"
        :closable="false"
      />

      <VCard title="连接状态" class="mb-block">
        <dl class="mb-facts">
          <div>
            <dt>项目</dt>
            <dd>{{ connection?.project?.name || '—' }}</dd>
          </div>
          <div>
            <dt>知识源</dt>
            <dd>
              <template v-if="connection?.source">
                {{ connection.source.name }}
                <VTag :theme="connection.source.isHalo ? 'primary' : 'danger'">
                  {{ connection.source.kind || '未知' }}
                </VTag>
              </template>
              <template v-else>—</template>
            </dd>
          </div>
          <div>
            <dt>套餐</dt>
            <dd>{{ connection?.plan?.text || '—' }}</dd>
          </div>
          <div>
            <dt>问答助手</dt>
            <dd>
              <VStatusDot
                v-if="connection?.widget"
                :state="connection.widget.available ? 'success' : 'default'"
                :text="connection.widget.text"
              />
              <template v-else>—</template>
              <span v-if="!status.widgetEnabled" class="mb-muted">（插件设置里没有打开）</span>
            </dd>
          </div>
          <div>
            <dt>站点地址</dt>
            <dd>
              <template v-if="connection?.origin">
                <VStatusDot
                  :state="connection.origin.ok ? 'success' : 'error'"
                  :text="
                    connection.origin.ok ? '一致（' + connection.origin.siteOrigin + '）' : '不一致'
                  "
                />
              </template>
              <template v-else>—</template>
              <div class="mb-muted">
                Halo 外部访问地址：{{ status.externalUrl || '还没有设置' }}
              </div>
            </dd>
          </div>
          <div>
            <dt>文章</dt>
            <dd>{{ countText }}</dd>
          </div>
          <div>
            <dt>上次同步</dt>
            <dd>
              {{ lastText }}
              <div v-if="last" class="mb-muted">{{ summary(last) }}</div>
              <div v-if="last?.error" class="mb-error">{{ last.error }}</div>
            </dd>
          </div>
        </dl>
        <p v-for="note in connection?.notes ?? []" :key="note" class="mb-muted">{{ note }}</p>
        <p v-if="connection && !connection.checked && configured" class="mb-muted">
          还没有检查过连接，点「测试连接」看看。
        </p>
      </VCard>

      <VCard title="操作" class="mb-block">
        <VSpace wrap>
          <VButton :loading="busy === 'test'" :disabled="locked" @click="check">测试连接</VButton>
          <VButton
            type="secondary"
            :loading="busy === 'sync'"
            :disabled="locked || !configured"
            @click="startFullSync"
          >
            立即全量同步
          </VButton>
          <VButton
            :loading="busy === 'retry'"
            :disabled="locked || !configured || retryCount === 0"
            @click="startRetry"
          >
            重试失败项{{ retryCount > 0 ? '（' + retryCount + '）' : '' }}
          </VButton>
        </VSpace>

        <p v-if="running && busy === undefined" class="mb-muted mb-top">
          同步正在进行，完成后这里会自动更新。
        </p>

        <VAlert
          v-if="guardPending"
          class="mb-top"
          type="warning"
          title="清理保护：有文章没有撤下"
          :closable="false"
        >
          <template #description>
            <p>
              MomoBird 上有
              {{ last?.wouldRemove || last?.heldRemovals || '一些' }}
              篇文章在 Halo 里已经没有了，但数量很多（或 Halo 里一篇都没有、或知识源类型不是
              halo）， 为防止误删，本次没有撤下。
            </p>
            <p v-for="note in last?.notes ?? []" :key="note" class="mb-muted">{{ note }}</p>
            <p>
              如果确实要撤下它们，点下面的按钮；如果不是，请先检查 Halo
              里的文章有没有被删或改成私有。
            </p>
          </template>
          <template #actions>
            <VButton
              type="danger"
              size="sm"
              :loading="busy === 'confirm'"
              :disabled="locked"
              @click="confirmRemovals"
            >
              确认撤下
            </VButton>
          </template>
        </VAlert>

        <div v-if="outcome" class="mb-top">
          <VAlert
            v-if="outcome.waiting"
            type="info"
            :title="outcome.title + '仍在进行'"
            :description="
              (outcome.message ? outcome.message + '。' : '') +
              '文章比较多，需要一些时间。你可以离开这个页面，同步不会中断。'
            "
            :closable="false"
          />
          <VAlert
            v-else-if="outcome.error"
            type="error"
            :title="outcome.title + '没有完成'"
            :description="outcome.error"
            :closable="false"
          />
          <VAlert
            v-else-if="outcome.report"
            type="success"
            :title="outcome.title + '完成'"
            :description="summary(outcome.report)"
            :closable="false"
          />
        </div>
      </VCard>

      <VCard title="失败的文章" class="mb-block">
        <p v-if="status.failures.length === 0" class="mb-muted">没有失败的文章。</p>
        <table v-else class="mb-table">
          <thead>
            <tr>
              <th>文章标题</th>
              <th>错误</th>
              <th>时间</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="f in status.failures" :key="f.name">
              <td>{{ f.title || f.name }}</td>
              <td>
                <code v-if="f.kind === 'refused'">{{ failureText(f) }}</code>
                <span v-else>{{ failureText(f) }}</span>
              </td>
              <td>{{ when(f.at) }}</td>
            </tr>
          </tbody>
        </table>
        <p v-if="status.failures.length > 0" class="mb-muted mb-top">
          被拒绝的文章和暂时没推送成功的文章都在这里；点「重试失败项」再推送一次，每天的全量同步也会重试。
        </p>
      </VCard>
    </template>
  </div>
</template>

<style lang="scss" scoped>
.mb-page {
  padding: 1rem;
  max-width: 64rem;
}

.mb-icon {
  width: 1.25rem;
  height: 1.25rem;
}

.mb-block {
  margin-bottom: 1rem;
}

.mb-top {
  margin-top: 1rem;
}

.mb-facts {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(16rem, 1fr));
  gap: 1rem 1.5rem;
  padding: 1rem;
  margin: 0;

  dt {
    font-size: 0.75rem;
    color: #6b7280;
    margin-bottom: 0.25rem;
  }

  dd {
    margin: 0;
    font-size: 0.875rem;
    color: #111827;
    word-break: break-word;
  }
}

.mb-muted {
  font-size: 0.75rem;
  color: #6b7280;
}

.mb-error {
  font-size: 0.75rem;
  color: #dc2626;
}

.mb-hint {
  margin-top: 0.5rem;
}

p.mb-muted {
  padding: 0 1rem;
  margin: 0.5rem 0;
}

.mb-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 0.875rem;

  th,
  td {
    text-align: left;
    padding: 0.5rem 1rem;
    border-bottom: 1px solid #e5e7eb;
    vertical-align: top;
  }

  th {
    font-size: 0.75rem;
    color: #6b7280;
    font-weight: 500;
  }
}
</style>
