export type ThemePreference = 'light' | 'dark' | 'system'

export interface RetrievalIndexStatus {
  enabled: boolean
  state: 'UNPREPARED' | 'READY' | 'NEEDS_PREPARE' | 'FAILED'
  operation: 'NONE' | 'PREPARE' | 'SEARCH'
  stage: 'IDLE' | 'SCANNING' | 'BUILDING' | 'SYNCING' | 'RETRIEVING' | 'SELECTING' | 'ASSEMBLING'
  processedFiles: number
  totalFiles?: number | null
  indexedFiles: number
  skippedFiles: number
  chunkCount: number
  lastSuccessAt?: string | null
  error?: { code: string; message: string } | null
}

export const MAX_PROMPT_LENGTH = 20_000
export const MAX_INTERVENTION_LENGTH = 4_000

export type TaskStepStatus = 'PENDING' | 'IN_PROGRESS' | 'COMPLETED'

export interface TaskStep {
  content: string
  status: TaskStepStatus
}

export interface TaskPlan {
  revision: number
  steps: TaskStep[]
}

export interface RunIntervention {
  interventionId: string
  content: string
  acceptedAt: number
  appliedAfterToolRound?: number
  state: 'queued' | 'applied'
}

export function parseTaskPlan(value: unknown): TaskPlan | undefined {
  if (typeof value !== 'object' || value === null) return undefined
  const candidate = value as { revision?: unknown; steps?: unknown }
  if (!Number.isSafeInteger(candidate.revision) || (candidate.revision as number) < 1
    || !Array.isArray(candidate.steps) || candidate.steps.length === 0) return undefined
  const steps: TaskStep[] = []
  for (const item of candidate.steps) {
    if (typeof item !== 'object' || item === null) return undefined
    const step = item as { content?: unknown; status?: unknown }
    if (typeof step.content !== 'string' || !step.content.trim()
      || (step.status !== 'PENDING'
        && step.status !== 'IN_PROGRESS'
        && step.status !== 'COMPLETED')) return undefined
    steps.push({ content: step.content, status: step.status })
  }
  return { revision: candidate.revision as number, steps }
}

export interface OpenAIModel {
  id: string
  ownedBy?: string
}

export function normalizeOpenAIModels(value: unknown): OpenAIModel[] {
  if (!Array.isArray(value)) return []

  const models = new Map<string, OpenAIModel>()
  for (const item of value) {
    if (typeof item !== 'object' || item === null) continue
    const candidate = item as { id?: unknown; ownedBy?: unknown }
    if (typeof candidate.id !== 'string') continue
    const id = candidate.id.trim()
    if (!id || id.length > 256) continue
    const ownedBy = typeof candidate.ownedBy === 'string' && candidate.ownedBy.trim()
      ? candidate.ownedBy.trim().slice(0, 128)
      : undefined
    const existing = models.get(id)
    if (existing?.ownedBy !== undefined && ownedBy === undefined) continue
    models.set(id, ownedBy === undefined ? { id } : { id, ownedBy })
  }

  return [...models.values()].sort((left, right) => left.id.localeCompare(right.id))
}

export interface OpenAIPublicSettings {
  baseUrl: string
  models: OpenAIModel[]
  model: string
  reasoningEffort: string
}

export interface OpenAIConnectionInput {
  baseUrl: string
  apiKey: string
}

export interface Workspace {
  id: string
  name: string
  path: string
}

export interface ConversationToolHistory {
  name: string
  arguments: string
  result: string
}

export type MemoryScope = 'GLOBAL' | 'WORKSPACE'
export type MemoryKind = 'PREFERENCE' | 'FACT' | 'DECISION' | 'STATUS'
export type MemoryChangeType = 'ADDED' | 'UPDATED' | 'DELETED'

export interface MemoryEntry {
  id: string
  scope: MemoryScope
  workspaceId?: string
  kind: MemoryKind
  subjectKey: string
  content: string
  sourceSessionId: string
  sourceRunId: string
  createdAt: string
  updatedAt: string
}

export interface MemoryChange {
  changeType: MemoryChangeType
  id: string
  scope: MemoryScope
  kind: MemoryKind
  subjectKey: string
  content: string
}

export interface ToolStep {
  id: string
  label: string
  detail?: string
  state:
    | 'awaiting_confirmation'
    | 'running'
    | 'completed'
    | 'failed'
    | 'rejected'
    | 'expired'
    | 'cancelled'
  code?: string
  confirmation?: {
    id: string
    operation: 'create' | 'delete' | 'move' | 'overwrite' | 'replace'
    path: string
    summary: string
    diff: string
    expiresAt: string
    submitting: boolean
    error?: string
  }
}

export interface RunMetrics {
  steps: number
  durationMs: number
  toolCalls?: number
  inputTokens?: number
  outputTokens?: number
}

export interface Message {
  id: string
  role: 'user' | 'assistant'
  content: string
  createdAt: number
  state: 'complete' | 'running' | 'error' | 'cancelled'
  statusLabel?: string
  errorCode?: string
  errorRetryable?: boolean
  processNotes?: string[]
  tools?: ToolStep[]
  toolHistory?: ConversationToolHistory[]
  memoryChanges?: MemoryChange[]
  taskPlan?: TaskPlan
  interventions?: RunIntervention[]
  metrics?: RunMetrics
}

export interface Session {
  id: string
  workspaceId: string
  title: string
  createdAt: number
  updatedAt: number
  messages: Message[]
}

export type AgentEvent =
  | { type: 'run_started'; runId: string }
  | { type: 'status'; label: string }
  | {
      type: 'tool_confirmation_required'
      id: string
      confirmationId: string
      label: string
      operation: 'create' | 'delete' | 'move' | 'overwrite' | 'replace'
      path: string
      summary: string
      diff: string
      expiresAt: string
    }
  | { type: 'tool_started'; id: string; label: string; detail?: string }
  | {
      type: 'tool_finished'
      id: string
      state: 'completed' | 'failed'
      detail?: string
      code?: string
    }
  | { type: 'text_delta'; delta: string }
  | { type: 'assistant_step'; content: string }
  | { type: 'memory_changed'; changes: MemoryChange[] }
  | { type: 'task_plan_updated'; plan: TaskPlan }
  | { type: 'run_intervention_applied'; interventionId: string }
  | {
      type: 'completed'
      metrics: RunMetrics
      toolHistory: ConversationToolHistory[]
    }
  | { type: 'error'; message: string; code?: string; retryable?: boolean }

export interface RunRequest {
  sessionId: string
  idempotencyKey: string
  prompt: string
  model: string
  reasoningEffort?: string
  openai: OpenAIConnectionInput
}

export function createId(prefix: string): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) {
    return `${prefix}-${crypto.randomUUID()}`
  }
  return `${prefix}-${Date.now()}-${Math.random().toString(16).slice(2)}`
}

export function titleFromPrompt(prompt: string): string {
  const compact = prompt.replace(/\s+/g, ' ').trim()
  if (compact.length === 0) return '新会话'
  return compact.length > 24 ? `${compact.slice(0, 24)}…` : compact
}

export function settleRunningTools(
  tools: readonly ToolStep[] | undefined,
  state: Exclude<ToolStep['state'], 'running' | 'awaiting_confirmation'>,
  detail: string,
): ToolStep[] | undefined {
  if (tools === undefined) return undefined
  return tools.map(tool => {
    if (tool.state !== 'running' && tool.state !== 'awaiting_confirmation') return tool
    const terminalDetail = tool.detail?.trim()
      ? `${tool.detail}\n${detail}`
      : detail
    return { ...tool, state, detail: terminalDetail }
  })
}

function completedStatusLabel(tools: readonly ToolStep[] | undefined): string {
  if (!tools?.length) return '已回答（未执行工具）'
  const mutations = tools.filter(tool => tool.confirmation !== undefined)
  if (mutations.length > 0 && mutations.every(tool => tool.state === 'completed')) {
    return '文件变更已完成'
  }
  if (mutations.length > 0) return '文件变更未完成'
  return '已回答（执行了工具）'
}

export function applyAgentEvent(message: Message, event: AgentEvent): Message {
  switch (event.type) {
    case 'run_started':
      return message
    case 'status':
      return { ...message, statusLabel: event.label }
    case 'tool_confirmation_required':
      /*
       * 背景：后端先发送 tool_started，再进入 SSE 静默的 WAITING_APPROVAL，审批快照随后由 Run 查询发现。
       * 设计意图：按同一 toolCallId 原地升级已有工具卡，保留开始事件提供的上下文，而不是追加重复卡片。
       * 关键约束：不能让 running 卡片与审批卡片并存；否则用户批准后只有其中一张能被 tool_finished 正确收口。
       */
      return {
        ...message,
        statusLabel: '等待审批文件变更',
        tools: (message.tools ?? []).some(tool => tool.id === event.id)
          ? (message.tools ?? []).map(tool => tool.id === event.id
              ? {
                  ...tool,
                  label: event.label,
                  state: 'awaiting_confirmation',
                  confirmation: {
                    id: event.confirmationId,
                    operation: event.operation,
                    path: event.path,
                    summary: event.summary,
                    diff: event.diff,
                    expiresAt: event.expiresAt,
                    submitting: false,
                  },
                }
              : tool)
          : [
              ...(message.tools ?? []),
              {
                id: event.id,
                label: event.label,
                state: 'awaiting_confirmation',
                confirmation: {
                  id: event.confirmationId,
                  operation: event.operation,
                  path: event.path,
                  summary: event.summary,
                  diff: event.diff,
                  expiresAt: event.expiresAt,
                  submitting: false,
                },
              },
            ],
      }
    case 'tool_started':
      return {
        ...message,
        tools: (message.tools ?? []).some(tool => tool.id === event.id)
          ? (message.tools ?? []).map(tool => tool.id === event.id
              ? { ...tool, label: event.label, detail: event.detail, state: 'running' }
              : tool)
          : [
              ...(message.tools ?? []),
              { id: event.id, label: event.label, detail: event.detail, state: 'running' },
            ],
      }
    case 'tool_finished':
      return {
        ...message,
        tools: (message.tools ?? []).map(tool =>
          tool.id === event.id
            ? {
                ...tool,
                detail: event.detail ?? tool.detail,
                state: event.code === 'CONFIRMATION_REJECTED'
                  ? 'rejected'
                  : event.code === 'CONFIRMATION_EXPIRED'
                    ? 'expired'
                    : event.state,
                code: event.code,
              }
            : tool,
        ),
      }
    case 'assistant_step':
      return {
        ...message,
        processNotes: [...(message.processNotes ?? []), event.content],
      }
    case 'text_delta':
      return { ...message, content: message.content + event.delta }
    case 'memory_changed':
      return {
        ...message,
        memoryChanges: [...(message.memoryChanges ?? []), ...event.changes],
      }
    case 'task_plan_updated':
      return { ...message, taskPlan: event.plan }
    case 'run_intervention_applied':
      /*
       * 背景：后端只在安全边界真正消费补充要求后发送该事件。
       * 设计意图：原地更新已排队条目，让 UI 区分“已接收”和“已进入下一次模型请求”。
       * 关键约束：只能按 interventionId 收口，不能按文本或数组位置匹配，否则重复内容会更新错条目。
       */
      return {
        ...message,
        interventions: message.interventions?.map(intervention =>
          intervention.interventionId === event.interventionId
            ? { ...intervention, state: 'applied' }
            : intervention,
        ),
      }
    case 'completed':
      return {
        ...message,
        state: 'complete',
        statusLabel: completedStatusLabel(message.tools),
        toolHistory: event.toolHistory.length ? event.toolHistory : undefined,
        tools: settleRunningTools(
          message.tools,
          'failed',
          '运行已结束，但未收到工具完成事件',
        ),
        metrics: event.metrics,
      }
    case 'error':
      return {
        ...message,
        state: 'error',
        statusLabel: '运行失败',
        content: message.content || event.message,
        errorCode: event.code,
        errorRetryable: event.retryable,
        tools: settleRunningTools(message.tools, 'failed', event.message),
      }
  }
}
