/**
 * Builds the platform's standard approval flow (BPMN 2.0 + diagram) for 1..5 rounds.
 *
 * The engine decides the outcome by which end event a run reaches, and relies on a fixed set of process
 * variables (approvers, approvers2..5, hasApprovers, hasApprovers2..5, rejected, returned). A hand-drawn flow
 * that forgets the "rejected?" gateway would end as *approved* after a rejection, so the visual designer
 * generates this vetted structure instead of accepting arbitrary drawings:
 *
 *   start -> approvers resolved? -> round 1 -> rejected? -> round 2 (skipped when nobody) -> rejected? -> ...
 *   -> endApproved                       (any rejection -> endRejected; a return goes back one round)
 *
 * It mirrors the shipped purchase_request definition, so the backend's validator and engine adapter treat both
 * the same way. No imports: it is pure and can be run in Node for checks.
 */

export const MAX_ROUNDS = 5

const ORDINAL = ['first', 'second', 'third', 'fourth', 'fifth']
const FLOWABLE_NS = 'http://flowable.org/bpmn'

export interface FlowRound {
  /** Task name shown on the diagram and in the todo list. */
  name: string
}

function esc(value: string): string {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

const capitalise = (value: string) => value.charAt(0).toUpperCase() + value.slice(1)

/** `approveTask`, `approveTask2` … */
const taskId = (k: number) => (k === 1 ? 'approveTask' : `approveTask${k}`)
const decisionId = (k: number) => (k === 1 ? 'decisionGateway' : `decisionGateway${k}`)
const collection = (k: number) => (k === 1 ? 'approvers' : `approvers${k}`)
const hasApprovers = (k: number) => (k === 1 ? 'hasApprovers' : `hasApprovers${k}`)

export function defaultRoundName(k: number): string {
  return `Approve (${ORDINAL[k - 1] ?? `round ${k}`} round)`
}

/** The standard flow for a template. `rounds` must have 1..MAX_ROUNDS entries. */
export function buildApprovalFlow(templateKey: string, rounds: FlowRound[]): string {
  const n = Math.min(Math.max(rounds.length, 1), MAX_ROUNDS)
  const shapes: string[] = []
  const edges: string[] = []
  const body: string[] = []

  // ---- layout: column x of round k's gateway; round 1 has no "has approvers?" gateway of its own ----
  const roundGatewayX = (k: number) => 540 + (k - 2) * 350
  const taskX = (k: number) => (k === 1 ? 290 : roundGatewayX(k) + 100)
  const decisionX = (k: number) => (k === 1 ? 440 : roundGatewayX(k) + 250)
  const endX = decisionX(n) + 110
  const endCx = endX + 18

  const shape = (id: string, x: number, y: number, w: number, h: number, marker = false) =>
    shapes.push(
      `      <bpmndi:BPMNShape id="Shape_${id}" bpmnElement="${id}"${marker ? ' isMarkerVisible="true"' : ''}>\n` +
        `        <dc:Bounds x="${x}" y="${y}" width="${w}" height="${h}"/>\n` +
        `      </bpmndi:BPMNShape>`,
    )
  const edge = (flowId: string, points: [number, number][]) =>
    edges.push(
      `      <bpmndi:BPMNEdge id="Edge_${flowId}" bpmnElement="${flowId}">\n` +
        points.map(([x, y]) => `        <di:waypoint x="${x}" y="${y}"/>`).join('\n') +
        `\n      </bpmndi:BPMNEdge>`,
    )
  const flow = (id: string, from: string, to: string, condition?: string) =>
    body.push(
      condition
        ? `    <sequenceFlow id="${id}" sourceRef="${from}" targetRef="${to}">\n` +
            `      <conditionExpression xsi:type="tFormalExpression">${condition}</conditionExpression>\n` +
            `    </sequenceFlow>`
        : `    <sequenceFlow id="${id}" sourceRef="${from}" targetRef="${to}"/>`,
    )
  const approvalTask = (k: number, name: string) =>
    body.push(
      `    <userTask id="${taskId(k)}" name="${esc(name)}" flowable:assignee="\${approver}">\n` +
        `      <multiInstanceLoopCharacteristics isSequential="false"\n` +
        `                                        flowable:collection="${collection(k)}"\n` +
        `                                        flowable:elementVariable="approver">\n` +
        `        <completionCondition>\${rejected || returned || nrOfCompletedInstances == nrOfInstances}</completionCondition>\n` +
        `      </multiInstanceLoopCharacteristics>\n` +
        `    </userTask>`,
    )

  // ---- start, "approvers resolved?", manual review ----
  body.push(`    <startEvent id="start" name="Start"/>`)
  shape('start', 100, 240, 36, 36)
  flow('flow_start_to_gateway', 'start', 'hasApproversGateway')
  edge('flow_start_to_gateway', [[136, 258], [190, 258]])

  body.push(`    <exclusiveGateway id="hasApproversGateway" name="Approvers resolved?" default="flow_to_manual"/>`)
  shape('hasApproversGateway', 190, 233, 50, 50, true)
  flow('flow_to_approve', 'hasApproversGateway', 'approveTask', '${hasApprovers}')
  edge('flow_to_approve', [[240, 258], [290, 258]])
  flow('flow_to_manual', 'hasApproversGateway', 'manualReview')
  edge('flow_to_manual', [[215, 283], [215, 420], [290, 420]])
  // Nobody resolved: park on an unassigned task for manual handling. Never auto-approve.
  body.push(`    <userTask id="manualReview" name="Manual review (no approver resolved)"/>`)
  shape('manualReview', 290, 380, 100, 80)
  flow('flow_manual_to_approved', 'manualReview', 'endApproved')
  edge('flow_manual_to_approved', [[390, 420], [endCx, 420], [endCx, 276]])

  // ---- rounds ----
  for (let k = 1; k <= n; k++) {
    const name = rounds[k - 1]?.name.trim() || defaultRoundName(k)
    const last = k === n

    if (k >= 2) {
      const g = `round${k}Gateway`
      const gx = roundGatewayX(k)
      body.push(`    <exclusiveGateway id="${g}" name="${esc(`${capitalise(ORDINAL[k - 1] ?? `round ${k}`)} round?`)}" default="flow_round${k}_skip"/>`)
      shape(g, gx, 233, 50, 50, true)
      flow(`flow_round${k}_open`, g, taskId(k), `\${${hasApprovers(k)}}`)
      edge(`flow_round${k}_open`, [[gx + 50, 258], [taskX(k), 258]])
      flow(`flow_round${k}_skip`, g, 'endApproved')
      const skipY = 365 - (k - 2) * 8
      edge(`flow_round${k}_skip`, [[gx + 25, 283], [gx + 25, skipY], [endCx, skipY], [endCx, 276]])
    }

    approvalTask(k, name)
    shape(taskId(k), taskX(k), 218, 100, 80)

    const d = decisionId(k)
    const dx = decisionX(k)
    flow(k === 1 ? 'flow_approve_to_decision' : `flow_approve${k}_to_decision`, taskId(k), d)
    edge(k === 1 ? 'flow_approve_to_decision' : `flow_approve${k}_to_decision`, [[taskX(k) + 100, 258], [dx, 258]])

    const defaultFlow = last ? 'flow_to_approved' : k === 1 ? 'flow_to_round2' : `flow_to_round${k + 1}`
    body.push(`    <exclusiveGateway id="${d}" name="Rejected?" default="${defaultFlow}"/>`)
    shape(d, dx, 233, 50, 50, true)

    const rejected = k === 1 ? 'flow_to_rejected' : `flow_to_rejected${k}`
    flow(rejected, d, 'endRejected', '${rejected}')
    edge(rejected, [[dx + 25, 233], [dx + 25, 118], [endX, 118]])

    if (k >= 2) {
      const back = k === 2 ? 'flow_return_to_round1' : `flow_return_to_round${k - 1}`
      const backY = 325 - (k - 2) * 8
      const px = taskX(k - 1) + 50
      flow(back, d, taskId(k - 1), '${returned}')
      edge(back, [[dx + 25, 283], [dx + 25, backY], [px, backY], [px, 298]])
    }

    if (last) {
      flow('flow_to_approved', d, 'endApproved')
      edge('flow_to_approved', [[dx + 50, 258], [endX, 258]])
    } else {
      const next = `round${k + 1}Gateway`
      flow(defaultFlow, d, next)
      edge(defaultFlow, [[dx + 50, 258], [roundGatewayX(k + 1), 258]])
    }
  }

  body.push(`    <endEvent id="endApproved" name="Approved"/>`)
  shape('endApproved', endX, 240, 36, 36)
  body.push(`    <endEvent id="endRejected" name="Rejected"/>`)
  shape('endRejected', endX, 100, 36, 36)

  return (
    `<?xml version="1.0" encoding="UTF-8"?>\n` +
    `<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"\n` +
    `             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"\n` +
    `             xmlns:flowable="${FLOWABLE_NS}"\n` +
    `             xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI"\n` +
    `             xmlns:dc="http://www.omg.org/spec/DD/20100524/DC"\n` +
    `             xmlns:di="http://www.omg.org/spec/DD/20100524/DI"\n` +
    `             targetNamespace="http://flowpass.com/approval"\n` +
    `             id="Definitions_${templateKey}">\n` +
    `  <process id="${templateKey}" name="${esc(templateKey)}" isExecutable="true">\n` +
    body.join('\n') +
    `\n  </process>\n` +
    `  <bpmndi:BPMNDiagram id="Diagram_${templateKey}">\n` +
    `    <bpmndi:BPMNPlane id="Plane_${templateKey}" bpmnElement="${templateKey}">\n` +
    [...shapes, ...edges].join('\n') +
    `\n    </bpmndi:BPMNPlane>\n` +
    `  </bpmndi:BPMNDiagram>\n` +
    `</definitions>\n`
  )
}

/**
 * The round names of a flow, read from its approval tasks (those looping over `approvers`, `approvers2` …),
 * or `null` when the XML has none (empty draft) or cannot be read.
 */
export function readRounds(xml: string): FlowRound[] | null {
  if (!xml.trim()) return null
  const doc = new DOMParser().parseFromString(xml, 'application/xml')
  if (doc.getElementsByTagName('parsererror').length > 0) return null
  const found = new Map<number, string>()
  for (const task of Array.from(doc.getElementsByTagName('userTask'))) {
    const loop = task.getElementsByTagName('multiInstanceLoopCharacteristics')[0]
    if (!loop) continue
    const coll = loop.getAttributeNS(FLOWABLE_NS, 'collection') ?? loop.getAttribute('flowable:collection')
    const match = coll ? /^approvers([2-5])?$/.exec(coll) : null
    if (match) found.set(match[1] ? Number(match[1]) : 1, task.getAttribute('name') ?? '')
  }
  if (found.size === 0) return null
  return [...found.entries()].sort((a, b) => a[0] - b[0]).map(([, name]) => ({ name }))
}
