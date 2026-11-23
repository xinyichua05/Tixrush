import { Check } from 'lucide-react'

/** "Reserved → Order created → Paid" progress. current = index of the step in progress. */
export function Stepper({ steps, current, failed = false }: { steps: string[]; current: number; failed?: boolean }) {
  return (
    <ol className="stepper">
      {steps.map((label, i) => {
        const state = i < current ? 'done' : i === current ? (failed ? 'failed' : 'active') : 'todo'
        return (
          <li key={label} className={`step step-${state}`}>
            <span className="step-dot">{state === 'done' ? <Check size={14} strokeWidth={3} /> : i + 1}</span>
            <span className="step-label">{label}</span>
          </li>
        )
      })}
    </ol>
  )
}
