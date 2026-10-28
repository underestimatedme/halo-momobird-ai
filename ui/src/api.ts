import { axiosInstance } from '@halo-dev/api-client'

/** The plugin's endpoints, mounted by Halo under /apis/api.momobird.ai/v1alpha1 (see AdminEndpoint.java). */
const BASE = '/apis/api.momobird.ai/v1alpha1'

export interface OriginCheck {
  ok: boolean
  siteOrigin: string | null
  sourceOrigin: string | null
  reason: string | null
}

export interface Report {
  ok: boolean
  mode: 'full' | 'incremental' | 'retry'
  pushed: number
  unchanged: number
  removed: number
  failed: number
  planRequired: number
  heldRemovals: number
  guardTripped: boolean
  originBlocked: boolean
  wrongKind: boolean
  startedAt: string | null
  finishedAt: string | null
  error: string | null
  local: number
  remote: number
  skipped: number
  wouldRemove: number
  sourceKind: string | null
  origin: OriginCheck | null
  widgetEnabled: boolean
  notes: string[]
}

export interface Connection {
  checked: boolean
  ok: boolean
  error: string | null
  project: { name: string } | null
  source: {
    name: string
    kind: string
    isHalo: boolean
    documentCount: number | null
    readyDocumentCount: number | null
  } | null
  plan: { status: string; active: boolean; endsAt: string | null; text: string } | null
  widget: { available: boolean; reason: string; text: string } | null
  origin: OriginCheck | null
  notes: string[]
}

export interface Failure {
  name: string
  title: string | null
  kind: 'refused' | 'pending'
  code: string | null
  message: string | null
  at: string | null
}

export interface Status {
  configured: boolean
  problems: string[]
  baseUrl: string
  externalUrl: string | null
  widgetEnabled: boolean
  podcastEnabled: boolean
  connection: Connection
  sync: {
    running: boolean
    guardPending: boolean
    planRequired: boolean
    local: number | null
    remote: number | null
    /** The latest run of any kind. */
    last: Report | null
    /** The last full sync: the clean-up guard's counts and notes belong to it. */
    lastFull: Report | null
  }
  failures: Failure[]
}

export interface TestResult {
  configured: boolean
  problems: string[]
  connection: Connection
}

/** The answer to a sync or retry. 202: it is still running; read the result from the status. */
export interface RunResult {
  ok: boolean
  accepted: boolean
  running: boolean
  message: string | null
  report: Report | null
}

export async function getStatus(check: boolean): Promise<Status> {
  const { data } = await axiosInstance.get<Status>(`${BASE}/status`, { params: { check } })
  return data
}

export async function testConnection(): Promise<TestResult> {
  const { data } = await axiosInstance.post<TestResult>(`${BASE}/test`)
  return data
}

export async function fullSync(confirmRemovals: boolean): Promise<RunResult> {
  const { data } = await axiosInstance.post<RunResult>(`${BASE}/sync`, { confirmRemovals })
  return data
}

export async function retryFailures(): Promise<RunResult> {
  const { data } = await axiosInstance.post<RunResult>(`${BASE}/retry`)
  return data
}

/** A sentence for the owner out of a failed request. */
export function describeError(error: unknown): string {
  const response = (error as { response?: { status?: number; data?: { message?: string } } })
    ?.response
  if (response?.status === 401) return '登录已过期，请刷新页面重新登录'
  if (response?.status === 403) return '当前账号没有管理 MomoBird 的权限'
  if (response?.data?.message) return response.data.message
  if (response?.status) return `请求失败（HTTP ${response.status}）`
  return '连不上 Halo，请检查网络后重试'
}
