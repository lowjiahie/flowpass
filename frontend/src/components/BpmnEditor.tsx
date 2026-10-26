import { useEffect, useRef, useState } from 'react'
import { Alert, Input, Select, Space, Typography } from 'antd'
import Modeler from 'bpmn-js/lib/Modeler'
import 'bpmn-js/dist/assets/diagram-js.css'
import 'bpmn-js/dist/assets/bpmn-js.css'
import 'bpmn-js/dist/assets/bpmn-font/css/bpmn-embedded.css'
import { approvalModule } from '@/components/bpmn/approvalModule'
import { flowableModdle } from '@/components/bpmn/flowableModdle'
import {
  collectionOfRound,
  END_EVENT,
  EXCLUSIVE_GATEWAY,
  hasApproversOfRound,
  REJECTED,
  RETURNED,
  roundOf,
  SEQUENCE_FLOW,
  USER_TASK,
} from '@/components/bpmn/types'
import type {
  BpmnFactory,
  CanvasService,
  DiagramElement,
  EventBus,
  Modeling,
  ModdleElement,
} from '@/components/bpmn/types'

interface Props {
  xml: string
  /** Called with the exported XML after every edit on the canvas. */
  onChange: (xml: string) => void
  height?: number
}

/** Fit the diagram into its container; a container that is not laid out yet (hidden tab) is retried on resize. */
function fitWhenVisible(canvas: CanvasService, container: HTMLElement | null): () => void {
  const fit = () => {
    if (!container || container.clientWidth === 0 || container.clientHeight === 0) return false
    try {
      canvas.zoom('fit-viewport')
      return true
    } catch {
      return false
    }
  }
  if (fit() || !container || typeof ResizeObserver === 'undefined') return () => undefined
  const observer = new ResizeObserver(() => {
    if (fit()) observer.disconnect()
  })
  observer.observe(container)
  return () => observer.disconnect()
}

// ---- condition presets of a flow leaving a decision -----------------------------------------------------

type ConditionKind = 'default' | 'rejected' | 'returned' | 'has-approvers' | 'custom'

interface ConditionState {
  kind: ConditionKind
  round: number
  custom: string
}

function bodyOf(flow: DiagramElement): string {
  const expression = flow.businessObject.get('conditionExpression') as ModdleElement | undefined
  const body = expression?.get('body')
  return typeof body === 'string' ? body.replace(/\s+/g, '') : ''
}

function conditionState(flow: DiagramElement): ConditionState {
  const source = flow.source?.businessObject
  if (source && source.get('default') === flow.businessObject) return { kind: 'default', round: 1, custom: '' }
  const body = bodyOf(flow)
  if (body === REJECTED) return { kind: 'rejected', round: 1, custom: '' }
  if (body === RETURNED) return { kind: 'returned', round: 1, custom: '' }
  const has = /^\$\{hasApprovers([2-5])?}$/.exec(body)
  if (has) return { kind: 'has-approvers', round: has[1] ? Number(has[1]) : 1, custom: '' }
  const raw = (flow.businessObject.get('conditionExpression') as ModdleElement | undefined)?.get('body')
  return { kind: 'custom', round: 1, custom: typeof raw === 'string' ? raw.replace(/^\$\{|}$/g, '') : '' }
}

const ROUND_OPTIONS = [1, 2, 3, 4, 5].map((n) => ({ value: n, label: `Round ${n}` }))

const CONDITION_OPTIONS: { value: ConditionKind; label: string }[] = [
  { value: 'default', label: 'Otherwise (default path)' },
  { value: 'rejected', label: 'An approver rejected' },
  { value: 'returned', label: 'An approver sent it back' },
  { value: 'has-approvers', label: 'The round has approvers' },
  { value: 'custom', label: 'Custom condition (e.g. amount >= 1000)' },
]

// ---- properties panel -------------------------------------------------------------------------------------

interface PanelProps {
  modeler: Modeler
  element: DiagramElement
}

function ElementPanel({ modeler, element }: PanelProps) {
  const modeling = modeler.get<Modeling>('modeling')
  const bpmnFactory = modeler.get<BpmnFactory>('bpmnFactory')
  const bo = element.businessObject
  const name = typeof bo.get('name') === 'string' ? (bo.get('name') as string) : ''

  const rename = (value: string) => modeling.updateProperties(element, { name: value })

  if (element.type === USER_TASK) {
    const round = roundOf(bo)
    const loop = bo.get('loopCharacteristics') as ModdleElement | undefined
    return (
      <Space direction="vertical" style={{ width: '100%' }}>
        <Typography.Text strong>Approval round</Typography.Text>
        <Input value={name} placeholder="Name" onChange={(e) => rename(e.target.value)} />
        {round !== null && loop ? (
          <>
            <Select
              style={{ width: '100%' }}
              value={round}
              options={ROUND_OPTIONS}
              onChange={(next: number) =>
                modeling.updateModdleProperties(element, loop, { 'flowable:collection': collectionOfRound(next) })
              }
            />
            <Typography.Text type="secondary">
              The approvers of this round come from the approver rules (round {round}). Everyone in the round
              must approve.
            </Typography.Text>
          </>
        ) : (
          <Typography.Text type="secondary">
            This task is not an approval round (it has no approvers collection).
          </Typography.Text>
        )}
      </Space>
    )
  }

  if (element.type === SEQUENCE_FLOW) {
    const fromDecision = element.source?.type === EXCLUSIVE_GATEWAY
    if (!fromDecision) {
      return (
        <Typography.Text type="secondary">
          A flow only needs a condition when it leaves a decision.
        </Typography.Text>
      )
    }
    const state = conditionState(element)
    const gateway = element.source as DiagramElement

    const apply = (kind: ConditionKind, round: number, custom: string) => {
      if (kind === 'default') {
        modeling.updateProperties(gateway, { default: element.businessObject })
        modeling.updateProperties(element, { conditionExpression: undefined })
        return
      }
      if (gateway.businessObject.get('default') === element.businessObject) {
        modeling.updateProperties(gateway, { default: undefined })
      }
      const body =
        kind === 'rejected'
          ? REJECTED
          : kind === 'returned'
            ? RETURNED
            : kind === 'has-approvers'
              ? hasApproversOfRound(round)
              : `\${${custom.trim()}}`
      const expression = bpmnFactory.create('bpmn:FormalExpression', { body })
      modeling.updateProperties(element, { conditionExpression: expression })
    }

    return (
      <Space direction="vertical" style={{ width: '100%' }}>
        <Typography.Text strong>Path out of a decision</Typography.Text>
        <Input value={name} placeholder="Label" onChange={(e) => rename(e.target.value)} />
        <Select
          style={{ width: '100%' }}
          value={state.kind}
          options={CONDITION_OPTIONS}
          onChange={(kind: ConditionKind) => apply(kind, state.round, state.custom)}
        />
        {state.kind === 'has-approvers' && (
          <Select
            style={{ width: '100%' }}
            value={state.round}
            options={ROUND_OPTIONS}
            onChange={(round: number) => apply('has-approvers', round, state.custom)}
          />
        )}
        {state.kind === 'custom' && (
          <Input
            value={state.custom}
            placeholder="amount >= 1000 && currency == 'MYR'"
            onChange={(e) => apply('custom', state.round, e.target.value)}
          />
        )}
        <Typography.Text type="secondary">
          Exactly one path out of a decision must be the default; every other path needs a condition.
        </Typography.Text>
      </Space>
    )
  }

  if (element.type === EXCLUSIVE_GATEWAY) {
    return (
      <Space direction="vertical" style={{ width: '100%' }}>
        <Typography.Text strong>Decision</Typography.Text>
        <Input value={name} placeholder="Question, e.g. Rejected?" onChange={(e) => rename(e.target.value)} />
        <Typography.Text type="secondary">
          Select a path leaving it to set its condition. After an approval round, one path must go to
          &quot;Rejected&quot; when an approver rejects.
        </Typography.Text>
      </Space>
    )
  }

  if (element.type === END_EVENT) {
    return (
      <Typography.Text type="secondary">
        The outcome of the request: the end it reaches decides whether it is approved or rejected.
      </Typography.Text>
    )
  }

  return <Typography.Text type="secondary">Select a round, decision or path to edit it.</Typography.Text>
}

// ---- editor -----------------------------------------------------------------------------------------------

/**
 * Draw the approval flow: drag approval rounds and decisions from the palette, connect them, and set each
 * path's condition in the side panel. The palette only offers what the server accepts, and the server still
 * validates the saved flow (including that every rejection ends as "rejected").
 * The bpmn.io logo is part of the library's license terms and must stay visible.
 */
export default function BpmnEditor({ xml, onChange, height = 520 }: Props) {
  const container = useRef<HTMLDivElement>(null)
  const modelerRef = useRef<Modeler | null>(null)
  const lastXml = useRef<string>('')
  const stopFit = useRef<() => void>(() => undefined)
  const onChangeRef = useRef(onChange)
  onChangeRef.current = onChange
  const [selected, setSelected] = useState<DiagramElement | null>(null)
  const [, setVersion] = useState(0)
  const [error, setError] = useState<string | null>(null)
  const [ready, setReady] = useState(false)

  useEffect(() => {
    if (!container.current) return
    const modeler = new Modeler({
      container: container.current,
      additionalModules: [approvalModule],
      moddleExtensions: { flowable: flowableModdle },
      keyboard: { bindTo: document },
    })
    modelerRef.current = modeler
    const events = modeler.get<EventBus>('eventBus')

    let timer: ReturnType<typeof setTimeout> | undefined
    const exportXml = () => {
      clearTimeout(timer)
      timer = setTimeout(() => {
        modeler
          .saveXML({ format: true })
          .then(({ xml: exported }) => {
            if (exported && exported !== lastXml.current) {
              lastXml.current = exported
              onChangeRef.current(exported)
            }
          })
          .catch(() => undefined)
      }, 150)
    }
    const onSelection = (e: { newSelection?: DiagramElement[] }) => setSelected(e.newSelection?.[0] ?? null)
    const onChanged = () => setVersion((v) => v + 1)
    events.on('commandStack.changed', exportXml)
    events.on('selection.changed', onSelection)
    events.on('element.changed', onChanged)
    setReady(true)

    return () => {
      clearTimeout(timer)
      stopFit.current()
      modeler.destroy()
      modelerRef.current = null
      setReady(false)
    }
  }, [])

  // Load XML coming from outside (generated flow, XML tab, loaded draft); skip what we exported ourselves.
  useEffect(() => {
    const modeler = modelerRef.current
    if (!ready || !modeler || xml === lastXml.current) return
    lastXml.current = xml
    if (!xml.trim()) {
      modeler.clear()
      return
    }
    modeler
      .importXML(xml)
      .then(() => {
        setError(null)
        setSelected(null)
        stopFit.current()
        stopFit.current = fitWhenVisible(modeler.get<CanvasService>('canvas'), container.current)
      })
      .catch((err: unknown) => setError(err instanceof Error ? err.message : 'The diagram could not be drawn'))
  }, [xml, ready])

  return (
    <div>
      {error && <Alert type="warning" showIcon style={{ marginBottom: 8 }} message={`Cannot draw this XML: ${error}`} />}
      <div style={{ display: 'flex', gap: 16, alignItems: 'stretch' }}>
        <div ref={container} style={{ height, flex: 1, minWidth: 0, border: '1px solid rgba(5, 5, 5, 0.06)' }} />
        <div style={{ width: 280, flexShrink: 0 }}>
          {selected && modelerRef.current ? (
            <ElementPanel modeler={modelerRef.current} element={selected} />
          ) : (
            <Typography.Text type="secondary">
              Drag an approval round or a decision from the toolbar on the left of the canvas, or hover an element
              and use its quick actions. Select an element to edit it here.
            </Typography.Text>
          )}
        </div>
      </div>
    </div>
  )
}
