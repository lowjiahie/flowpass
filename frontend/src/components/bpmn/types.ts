/**
 * The small slice of bpmn-js / diagram-js services the designer uses. bpmn-js types its service lookup as
 * `get<T>(name)`, so these describe only what we call; they are not a model of the library.
 */

export interface ModdleElement {
  $type: string
  id?: string
  name?: string
  get(name: string): unknown
  set(name: string, value: unknown): void
}

export interface DiagramElement {
  id: string
  type: string
  businessObject: ModdleElement
  source?: DiagramElement
  target?: DiagramElement
  waypoints?: unknown[]
}

export interface Modeling {
  updateProperties(element: DiagramElement, properties: Record<string, unknown>): void
  updateModdleProperties(element: DiagramElement, moddle: ModdleElement, properties: Record<string, unknown>): void
  removeElements(elements: DiagramElement[]): void
}

export interface BpmnFactory {
  create(type: string, attrs?: Record<string, unknown>): ModdleElement
}

export interface ElementFactory {
  createShape(attrs: Record<string, unknown>): DiagramElement
}

export interface CanvasService {
  zoom(level: 'fit-viewport'): void
  getRootElement(): DiagramElement
}

export interface Selection {
  get(): DiagramElement[]
}

export interface EventBus {
  on(event: string, callback: (event: { newSelection?: DiagramElement[]; element?: DiagramElement }) => void): void
  off(event: string, callback: (event: unknown) => void): void
}

export interface CommandStack {
  /** Increments with every change; used only to detect "something changed". */
  _stackIdx?: number
}

export const USER_TASK = 'bpmn:UserTask'
export const EXCLUSIVE_GATEWAY = 'bpmn:ExclusiveGateway'
export const SEQUENCE_FLOW = 'bpmn:SequenceFlow'
export const START_EVENT = 'bpmn:StartEvent'
export const END_EVENT = 'bpmn:EndEvent'

/** `${...}` expression bodies the platform's engine adapter and validator rely on. */
export const REJECTED = '${rejected}'
export const RETURNED = '${returned}'
export const COMPLETION = '${rejected || returned || nrOfCompletedInstances == nrOfInstances}'

export const collectionOfRound = (round: number): string => (round === 1 ? 'approvers' : `approvers${round}`)
export const hasApproversOfRound = (round: number): string =>
  round === 1 ? '${hasApprovers}' : `\${hasApprovers${round}}`

/** The round (1..5) an approval task belongs to, or null when the task is not a round task. */
export function roundOf(element: ModdleElement): number | null {
  const loop = element.get('loopCharacteristics') as ModdleElement | undefined
  const collection = loop?.get('flowable:collection')
  if (typeof collection !== 'string') return null
  const match = /^approvers([2-5])?$/.exec(collection)
  return match ? (match[1] ? Number(match[1]) : 1) : null
}
