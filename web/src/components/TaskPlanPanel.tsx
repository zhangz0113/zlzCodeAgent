import { Check, Circle, ListChecks, LoaderCircle } from 'lucide-react'
import type { RunIntervention, TaskPlan, TaskStepStatus } from '../domain'

interface TaskPlanPanelProps {
  plan?: TaskPlan
  interventions?: readonly RunIntervention[]
}

function StepIcon({ status }: { status: TaskStepStatus }) {
  if (status === 'COMPLETED') return <Check size={14} />
  if (status === 'IN_PROGRESS') return <LoaderCircle className="spin" size={14} />
  return <Circle size={12} />
}

export function TaskPlanPanel({ plan, interventions = [] }: TaskPlanPanelProps) {
  const completed = plan?.steps.filter(step => step.status === 'COMPLETED').length ?? 0

  return (
    <details className="taskPlanPanel" open>
      <summary>
        <ListChecks size={16} />
        <strong>{plan === undefined ? '任务补充' : '任务计划'}</strong>
        {plan !== undefined && (
          <span>{completed}/{plan.steps.length} 完成 · 第 {plan.revision} 版</span>
        )}
      </summary>
      {plan !== undefined && (
        <ol className="taskPlanSteps">
          {plan.steps.map((step, index) => (
            <li data-status={step.status} key={`${index}-${step.content}`}>
              <span className="taskPlanStepIcon"><StepIcon status={step.status} /></span>
              <span>{step.content}</span>
            </li>
          ))}
        </ol>
      )}
      {interventions.length > 0 && (
        <div className="runInterventions">
          <strong>运行中补充</strong>
          {interventions.map(intervention => (
            <div className="runIntervention" data-state={intervention.state} key={intervention.interventionId}>
              <span>{intervention.content}</span>
              <small>{intervention.state === 'applied' ? '已应用' : '等待应用'}</small>
            </div>
          ))}
        </div>
      )}
    </details>
  )
}
