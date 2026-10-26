import {
  COMPLETION,
  collectionOfRound,
  EXCLUSIVE_GATEWAY,
  END_EVENT,
  roundOf,
  START_EVENT,
  USER_TASK,
} from './types'
import type { BpmnFactory, CanvasService, DiagramElement, ElementFactory, Modeling, ModdleElement } from './types'

/**
 * A bpmn-js module that replaces the stock palette and context pad with the elements the platform accepts:
 * an approval round (a multi-instance user task) and a decision (exclusive gateway). The stock ones offer
 * script tasks, service tasks, sub-processes … which the server refuses; hiding them keeps the drawing honest.
 * The start event and the two end events (endApproved / endRejected) come with the generated flow and stay.
 */

type MouseEventLike = unknown

interface PaletteEntry {
  group: string
  className?: string
  title?: string
  separator?: boolean
  action?: Record<string, (event: MouseEventLike) => void>
}

interface PadEntry {
  group: string
  className: string
  title: string
  action: Record<string, (event: MouseEventLike, element: DiagramElement) => void>
}

interface Create {
  start(event: MouseEventLike, shape: DiagramElement, context?: Record<string, unknown>): void
}

interface Registrable {
  registerProvider(provider: unknown): void
}

interface HandTool {
  activateHand(event: MouseEventLike): void
}

interface LassoTool {
  activateSelection(event: MouseEventLike): void
}

interface AutoPlace {
  append(source: DiagramElement, shape: DiagramElement): void
}

interface Connect {
  start(event: MouseEventLike, source: DiagramElement): void
}

/** The lowest round number (1..5) no approval task uses yet, or 5 when all are taken. */
function nextRound(canvas: CanvasService): number {
  const flowElements = (canvas.getRootElement().businessObject.get('flowElements') as ModdleElement[] | undefined) ?? []
  const used = new Set(
    flowElements.filter((e) => e.$type === USER_TASK).map((e) => roundOf(e)).filter((r): r is number => r !== null),
  )
  for (let round = 1; round <= 5; round++) {
    if (!used.has(round)) return round
  }
  return 5
}

function approvalTask(
  elementFactory: ElementFactory,
  bpmnFactory: BpmnFactory,
  canvas: CanvasService,
): DiagramElement {
  const round = nextRound(canvas)
  const completion = bpmnFactory.create('bpmn:FormalExpression', { body: COMPLETION })
  const loop = bpmnFactory.create('bpmn:MultiInstanceLoopCharacteristics', {
    isSequential: false,
    'flowable:collection': collectionOfRound(round),
    'flowable:elementVariable': 'approver',
    completionCondition: completion,
  })
  const shape = elementFactory.createShape({ type: USER_TASK })
  shape.businessObject.set('name', `Approval round ${round}`)
  shape.businessObject.set('flowable:assignee', '${approver}')
  shape.businessObject.set('loopCharacteristics', loop)
  return shape
}

class ApprovalPalette {
  static $inject = ['palette', 'create', 'elementFactory', 'bpmnFactory', 'handTool', 'lassoTool', 'canvas']

  constructor(
    palette: Registrable,
    private readonly create: Create,
    private readonly elementFactory: ElementFactory,
    private readonly bpmnFactory: BpmnFactory,
    private readonly handTool: HandTool,
    private readonly lassoTool: LassoTool,
    private readonly canvas: CanvasService,
  ) {
    palette.registerProvider(this)
  }

  getPaletteEntries(): Record<string, PaletteEntry> {
    const startTask = (event: MouseEventLike) =>
      this.create.start(event, approvalTask(this.elementFactory, this.bpmnFactory, this.canvas))
    const startGateway = (event: MouseEventLike) =>
      this.create.start(event, this.elementFactory.createShape({ type: EXCLUSIVE_GATEWAY }))
    return {
      'hand-tool': {
        group: 'tools',
        className: 'bpmn-icon-hand-tool',
        title: 'Move the canvas',
        action: { click: (event) => this.handTool.activateHand(event) },
      },
      'lasso-tool': {
        group: 'tools',
        className: 'bpmn-icon-lasso-tool',
        title: 'Select several elements',
        action: { click: (event) => this.lassoTool.activateSelection(event) },
      },
      'tool-separator': { group: 'tools', separator: true },
      'create.approval-task': {
        group: 'activity',
        className: 'bpmn-icon-user-task',
        title: 'Approval round (drag onto the canvas)',
        action: { dragstart: startTask, click: startTask },
      },
      'create.decision': {
        group: 'gateway',
        className: 'bpmn-icon-gateway-xor',
        title: 'Decision (drag onto the canvas)',
        action: { dragstart: startGateway, click: startGateway },
      },
    }
  }
}

class ApprovalContextPad {
  static $inject = ['contextPad', 'modeling', 'elementFactory', 'bpmnFactory', 'canvas', 'autoPlace', 'connect', 'create']

  constructor(
    contextPad: Registrable,
    private readonly modeling: Modeling,
    private readonly elementFactory: ElementFactory,
    private readonly bpmnFactory: BpmnFactory,
    private readonly canvas: CanvasService,
    private readonly autoPlace: AutoPlace,
    private readonly connect: Connect,
    private readonly create: Create,
  ) {
    contextPad.registerProvider(this)
  }

  getContextPadEntries(element: DiagramElement): Record<string, PadEntry> {
    const entries: Record<string, PadEntry> = {}
    const isFlow = element.type === 'bpmn:SequenceFlow'
    const isEnd = element.type === END_EVENT

    if (!isFlow && !isEnd) {
      const appendTask = (_event: MouseEventLike, source: DiagramElement) =>
        this.autoPlace.append(source, approvalTask(this.elementFactory, this.bpmnFactory, this.canvas))
      const appendGateway = (_event: MouseEventLike, source: DiagramElement) =>
        this.autoPlace.append(source, this.elementFactory.createShape({ type: EXCLUSIVE_GATEWAY }))
      entries['append.approval-task'] = {
        group: 'model',
        className: 'bpmn-icon-user-task',
        title: 'Add an approval round after this',
        action: {
          click: appendTask,
          dragstart: (event, source) =>
            this.create.start(event, approvalTask(this.elementFactory, this.bpmnFactory, this.canvas), { source }),
        },
      }
      entries['append.decision'] = {
        group: 'model',
        className: 'bpmn-icon-gateway-xor',
        title: 'Add a decision after this',
        action: {
          click: appendGateway,
          dragstart: (event, source) =>
            this.create.start(event, this.elementFactory.createShape({ type: EXCLUSIVE_GATEWAY }), { source }),
        },
      }
      entries['connect'] = {
        group: 'connect',
        className: 'bpmn-icon-connection-multi',
        title: 'Connect to another element',
        action: {
          click: (event, source) => this.connect.start(event, source),
          dragstart: (event, source) => this.connect.start(event, source),
        },
      }
    }

    // The start event and the two outcomes are part of the contract; everything else can go.
    if (element.type !== START_EVENT && !isEnd) {
      entries['delete'] = {
        group: 'edit',
        className: 'bpmn-icon-trash',
        title: 'Delete',
        action: { click: (_event, target) => this.modeling.removeElements([target]) },
      }
    }
    return entries
  }
}

export const approvalModule = {
  __init__: ['paletteProvider', 'contextPadProvider'],
  paletteProvider: ['type', ApprovalPalette],
  contextPadProvider: ['type', ApprovalContextPad],
}
