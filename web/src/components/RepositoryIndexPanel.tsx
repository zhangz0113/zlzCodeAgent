import { useEffect, useRef, useState } from 'react'
import { LoaderCircle, RefreshCw } from 'lucide-react'
import type { AgentGateway } from '../agent-gateway'
import type { RetrievalIndexStatus, Workspace } from '../domain'

interface Props {
  workspace?: Workspace
  gateway: Pick<AgentGateway, 'getRetrievalIndex' | 'prepareRetrievalIndex'>
}

const stateLabels: Record<RetrievalIndexStatus['state'], string> = {
  UNPREPARED: '尚未准备', READY: '已就绪', NEEDS_PREPARE: '需要重新准备', FAILED: '准备失败',
}
const stageLabels: Record<RetrievalIndexStatus['stage'], string> = {
  IDLE: '空闲', SCANNING: '扫描文件', BUILDING: '构建检索表示', SYNCING: '同步索引',
  RETRIEVING: '召回材料', SELECTING: '选择源码', ASSEMBLING: '装配证据',
}

export function RepositoryIndexPanel({ workspace, gateway }: Props) {
  const [status, setStatus] = useState<RetrievalIndexStatus | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  const [starting, setStarting] = useState(false)
  const prepareAction = useRef<(() => Promise<void>) | null>(null)

  useEffect(() => {
    setStatus(null); setError(null); setLoading(workspace !== undefined); setStarting(false)
    if (!workspace) { prepareAction.current = null; return }
    let alive = true
    let timer: ReturnType<typeof setTimeout> | undefined
    let controller: AbortController | undefined
    const schedule = (value: RetrievalIndexStatus) => {
      if (value.operation !== 'NONE') timer = setTimeout(() => { void fetchStatus(false) }, 1000)
    }
    const fetchStatus = async (prepare: boolean) => {
      if (timer !== undefined) clearTimeout(timer)
      controller?.abort()
      const request = new AbortController(); controller = request
      if (prepare) setStarting(true)
      setError(null)
      try {
        const value = await (prepare ? gateway.prepareRetrievalIndex(workspace.id, request.signal)
          : gateway.getRetrievalIndex(workspace.id, request.signal))
        if (!alive || controller !== request) return
        setStatus(value); schedule(value)
      } catch (failure) {
        if (!alive || controller !== request || request.signal.aborted) return
        setError(failure instanceof Error ? failure.message : '读取索引状态失败')
      } finally {
        if (alive && controller === request) { setLoading(false); setStarting(false) }
      }
    }
    /*
     * 背景：设置弹窗关闭或切换工作区后，旧轮询响应可能晚到。
     * 设计意图：请求和定时器归属于这一次 effect，取消请求并用身份拒绝过期响应。
     * 关键约束：清理时必须同时停计时器并失效响应；否则旧工作区状态会覆盖新面板。
     */
    prepareAction.current = () => fetchStatus(true)
    void fetchStatus(false)
    return () => { alive = false; if (timer !== undefined) clearTimeout(timer); controller?.abort(); prepareAction.current = null }
  }, [workspace?.id, gateway])

  const active = starting || status?.operation !== undefined && status.operation !== 'NONE'
  return (
    <section className="settingsSection repositoryIndexPanel" aria-label="代码检索索引">
      <div className="settingsSectionTitle">
        <div><h3>代码检索索引</h3><p>准备当前工作区的源码依据，支持 Agent 按业务问题查找实现。</p></div>
        <span className={`settingsStatus${status?.enabled && status.state === 'READY' ? ' settingsStatusConfigured' : ''}`}>
          {status ? status.enabled ? active ? '操作中' : stateLabels[status.state] : '未启用' : '待查询'}
        </span>
      </div>
      {!workspace ? <p className="settingsHint">选择工作区后可准备代码索引。</p>
        : <>
          <p className="repositoryIndexWorkspace">当前工作区：{workspace.name}</p>
          {loading && <p role="status"><LoaderCircle className="spin" size={14} /> 正在读取索引状态…</p>}
          {status && !status.enabled && <p className="settingsHint">后端尚未启用代码检索。启用后可在此准备索引。</p>}
          {status?.enabled && <>
            <p aria-live="polite" className="repositoryIndexProgress">
              {active ? `${stageLabels[status.stage]} · 已处理 ${status.processedFiles}${status.totalFiles == null ? ' 个文件' : ` / ${status.totalFiles} 个文件`}`
                : `已索引 ${status.indexedFiles} 个文件 · ${status.chunkCount} 个片段 · 跳过 ${status.skippedFiles} 个文件`}
            </p>
            {status.lastSuccessAt && <p className="settingsHint">上次同步：{new Date(status.lastSuccessAt).toLocaleString()}</p>}
            <p className="settingsHint">搜索前仍会同步文件变化；大量修改后可先更新索引。</p>
          </>}
          {(error || status?.error) && <p className="settingsFeedback settingsFeedback-error" role="alert">{error || status?.error?.message}</p>}
          <button className="repositoryIndexButton" type="button" disabled={loading || active || status?.enabled === false}
            onClick={() => { void prepareAction.current?.() }}>
            {active ? <LoaderCircle className="spin" size={14} /> : <RefreshCw size={14} />}
            {active ? '索引操作中…' : status?.state === 'READY' ? '更新索引' : '准备索引'}
          </button>
        </>}
    </section>
  )
}
