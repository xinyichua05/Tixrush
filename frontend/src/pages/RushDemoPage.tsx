import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Activity, Eye, Flame, Info, Radio, RotateCcw, Square, Users } from 'lucide-react'
import { useCallback, useState } from 'react'
import { toast } from 'sonner'
import { getRushState, joinRush, startRush, stopRush } from '../api/rush'
import type { RushLineStyle, RushScenarioId, RushState } from '../api/types'
import { ActivityFeed, LatestActivity } from '../components/rush/ActivityFeed'
import { CrowdMap } from '../components/rush/CrowdMap'
import { MyStatusPanel } from '../components/rush/MyStatusPanel'
import { FinalNumbers, RushStats, RushTips } from '../components/rush/RushSummary'
import { LineStylePicker } from '../components/rush/LineStylePicker'
import { ScenarioPicker } from '../components/rush/ScenarioPicker'
import { Dialog, ErrorMessage, Loading } from '../components/ui'
import { formatSinceOpen, LINE_STYLES, phaseMessage } from '../lib/rush'

const STYLE_KEY = 'tixrush.practice.style'

/** The last line style you picked (a convenience only: storage can be blocked, then we start with Random lobby). */
function savedStyle(): RushLineStyle {
  try {
    return localStorage.getItem(STYLE_KEY) === 'FASTEST_CLICK' ? 'FASTEST_CLICK' : 'RANDOM_LOBBY'
  } catch {
    return 'RANDOM_LOBBY'
  }
}

const isActive = (s: RushState) => s.phase !== 'IDLE' && s.phase !== 'FINISHED'

const PHASE_LABEL: Record<RushState['phase'], string> = {
  IDLE: 'Not started',
  LOBBY: 'Lobby',
  OPEN: 'Sale open',
  WAITING_FOR_RETURNS: 'All reserved: line paused',
  FINISHED: 'Finished',
}

/**
 * The Practice rush: a famous concert goes on sale, hundreds of virtual fans want in, and you're one of them.
 * Everything comes from GET /api/rush-demo, polled twice a second while a rush runs.
 */
export function RushDemoPage() {
  const queryClient = useQueryClient()
  const { data: state, error, isPending } = useQuery({
    queryKey: ['rush'],
    queryFn: getRushState,
    refetchInterval: (q) => (q.state.data && isActive(q.state.data) ? 500 : 3000),
  })
  const [picking, setPicking] = useState<RushScenarioId | null>(null)
  // The simulation shows only after you pick a show (or tap "Watch it"). Opening the page starts at the picker.
  const [watching, setWatching] = useState(false)
  // The full "What just happened" log lives in a slide-in panel. `seen` = the newest event you've looked at, per run.
  const [logOpen, setLogOpen] = useState(false)
  const [seen, setSeen] = useState<{ run: string | null; upTo: number }>({ run: null, upTo: -Infinity })
  const markSeen = useCallback(() => {
    const s = queryClient.getQueryData<RushState>(['rush'])
    if (s) setSeen({ run: s.opensAt, upTo: s.feed[0]?.atMs ?? -Infinity })
  }, [queryClient])
  const openLog = () => {
    markSeen()
    setLogOpen(true)
  }
  const closeLog = useCallback(() => {
    markSeen()
    setLogOpen(false)
  }, [markSeen])
  const [lineStyle, setLineStyle] = useState<RushLineStyle>(savedStyle)
  const chooseStyle = (v: RushLineStyle) => {
    setLineStyle(v)
    try {
      localStorage.setItem(STYLE_KEY, v)
    } catch {
      // storage blocked: the choice just isn't remembered
    }
  }

  const refresh = () => queryClient.invalidateQueries({ queryKey: ['rush'] })
  const save = (s: RushState) => queryClient.setQueryData(['rush'], s)

  const start = useMutation({
    mutationFn: async (id: RushScenarioId) => {
      // A run is still going (e.g. you left and came back): picking a show starts fresh, like Restart.
      const latest = queryClient.getQueryData<RushState>(['rush'])
      if (latest && isActive(latest)) await stopRush()
      const started = await startRush(id, lineStyle)
      // Random lobby: you're in the lobby with everyone else. Fastest click: you click Join yourself at 0.
      return lineStyle === 'RANDOM_LOBBY' ? joinRush() : started
    },
    onMutate: (id) => setPicking(id),
    onSuccess: (s) => {
      save(s)
      setWatching(true)
      window.scrollTo({ top: 0, behavior: 'smooth' })
    },
    onError: (e) => {
      toast.error(e instanceof Error ? e.message : 'Could not start the rush')
      refresh()
    },
    onSettled: () => setPicking(null),
  })
  const join = useMutation({
    mutationFn: joinRush,
    onSuccess: save,
    onError: (e) => toast.error(e instanceof Error ? e.message : 'Could not join'),
  })
  // Stop: end this run now. The result screen (with the picker) shows straight away.
  const stop = useMutation({
    mutationFn: stopRush,
    onSuccess: save,
    onError: (e) => toast.error(e instanceof Error ? e.message : 'Could not stop'),
  })
  // Restart: stop, then the same show and line style again, in one click.
  const restart = useMutation({
    mutationFn: async ({ scenario, style }: { scenario: RushScenarioId; style: RushLineStyle }) => {
      await stopRush()
      const started = await startRush(scenario, style)
      return style === 'RANDOM_LOBBY' ? joinRush() : started
    },
    onSuccess: (s) => {
      save(s)
      setWatching(true)
      toast.success('Started a new practice rush')
    },
    onError: (e) => {
      toast.error(e instanceof Error ? e.message : 'Could not restart')
      refresh()
    },
  })
  const busy = stop.isPending || restart.isPending

  if (isPending) return <div className="container"><Loading label="Loading…" /></div>
  if (!state) return <div className="container"><ErrorMessage error={error} /></div>

  // What you see: the start view (picker + tips) until you pick a show, then the live simulation.
  const showSim = watching && state.phase !== 'IDLE'
  const running = isActive(state)
  const finished = state.phase === 'FINISHED'
  const fastest = state.lineStyle === 'FASTEST_CLICK'
  const styleLabel = LINE_STYLES.find((s) => s.id === state.lineStyle)?.label ?? ''
  const seenUpTo = seen.run === state.opensAt ? seen.upTo : -Infinity // a new run: everything is new
  const unseen = logOpen ? 0 : state.feed.filter((f) => f.atMs > seenUpTo).length

  const pickerCard = (title: string, note?: string) => (
    <section className="section">
      <h2 className="section-title">{title}</h2>
      {note && <p className="muted">{note}</p>}
      <div className="rush-setup">
        <h3 className="rush-side-title">1. How does the line work?</h3>
        <LineStylePicker value={lineStyle} onChange={chooseStyle} />
        <h3 className="rush-side-title">2. Pick a show</h3>
        <ScenarioPicker onPick={(id) => start.mutate(id)} busy={picking} lineStyle={lineStyle} />
      </div>
    </section>
  )
  const tipsCard = (style: RushLineStyle | null) => (
    <section className="section">
      <h2 className="section-title">Tips for a big sale</h2>
      <RushTips lineStyle={style} />
    </section>
  )

  if (!showSim) {
    return (
      <div className="container rush-page">
        <header className="rush-head">
          <div>
            <span className="eyebrow"><Flame size={14} /> Practice rush</span>
            <h1 className="rush-title">Practice for the <span className="grad-text">big sale</span></h1>
            <p className="muted">
              A famous concert goes on sale and hundreds of virtual fans want the same seats. Join the line and see if
              you get one. Just for fun: practice tickets are not real.
            </p>
          </div>
        </header>

        <ErrorMessage error={error} />

        {running && (
          <div className="glass rush-resume">
            <span className="rush-resume-text">
              <Radio size={15} /> A practice rush is running now: <strong>{state.scenarioLabel}</strong> · {styleLabel}
            </span>
            <button type="button" className="btn btn-glass btn-sm" onClick={() => setWatching(true)}>
              <Eye size={15} /> Watch it
            </button>
          </div>
        )}

        {pickerCard('Start a practice rush', running ? 'Picking a show stops the one running now and starts a new one.' : undefined)}
        {tipsCard(lineStyle)}
      </div>
    )
  }

  return (
    <div className="container rush-page">
      <header className="rush-head">
        <div>
          <span className="eyebrow"><Flame size={14} /> Practice rush</span>
          <h1 className="rush-title">{state.eventName}</h1>
          <p className="muted">
            {`Practice · ${styleLabel} · ${state.scenarioLabel} · ${state.fans.toLocaleString()} fans · ${state.tickets} tickets`}
          </p>
        </div>
        <div className="rush-badges">
          <span className={`phase-pill phase-${state.phase.toLowerCase()}`}>
            {!finished && <Radio size={14} />} {PHASE_LABEL[state.phase]}
          </span>
          {state.phase !== 'LOBBY' && <span className="chip mono">{formatSinceOpen(state.msSinceOpen)}</span>}
          {state.scenario && state.lineStyle && (
            <button
              type="button"
              className="btn btn-glass btn-sm"
              disabled={busy}
              onClick={() => restart.mutate({ scenario: state.scenario!, style: state.lineStyle! })}
            >
              <RotateCcw size={15} /> {restart.isPending ? 'Restarting…' : finished ? 'Run again' : 'Restart'}
            </button>
          )}
          {!finished && (
            <button type="button" className="btn btn-sm btn-stop" disabled={busy} onClick={() => stop.mutate()}>
              <Square size={13} fill="currentColor" /> {stop.isPending ? 'Stopping…' : 'Stop'}
            </button>
          )}
        </div>
      </header>

      <ErrorMessage error={error} />

      <div className="rush-grid">
        <section className="glass rush-me">
          <MyStatusPanel state={state} onJoin={() => join.mutate()} joining={join.isPending} onChanged={refresh} />
        </section>

        <aside className="glass rush-side">
          <h3 className="rush-side-title">
            <Activity size={16} /> {finished ? 'Final numbers' : 'Right now'}
          </h3>
          {finished ? <FinalNumbers state={state} /> : <RushStats state={state} />}
          <p className="explainer"><Info size={14} /> {phaseMessage(state.phase, state.lineStyle)}</p>
          <h3 className="rush-side-title">What just happened</h3>
          <LatestActivity items={state.feed} unseen={unseen} open={logOpen} onToggle={logOpen ? closeLog : openLog} />
        </aside>
      </div>

      <section className="glass rush-crowd-panel">
        <div className="rush-crowd-head">
          <h3 className="rush-side-title"><Users size={16} /> The crowd</h3>
          <span className="small muted">One dot per fan · front of the line is top-left</span>
        </div>
        <CrowdMap
          crowd={state.crowd}
          myIndex={state.myIndex}
          shuffling={state.phase === 'LOBBY' && !fastest}
          note={state.phase !== 'LOBBY' ? undefined
            : fastest ? 'Fans appear here in the order they click Join'
            : 'Drawing places in line when the sale opens…'}
        />
      </section>

      <Dialog variant="drawer" modal={false} title="What just happened" open={logOpen} onClose={closeLog}>
        <ActivityFeed items={state.feed} />
      </Dialog>

      {finished && (
        <>
          {tipsCard(state.lineStyle)}
          {pickerCard('Try again', fastest
            ? 'Can you click faster next time? Or switch styles and see how a random lobby feels.'
            : 'Your place in line is random every time, so the next run can end differently.')}
        </>
      )}
    </div>
  )
}
