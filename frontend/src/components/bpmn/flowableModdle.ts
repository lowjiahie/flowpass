/**
 * Moddle descriptor for the few Flowable attributes the platform's flows use, so the designer can read and write
 * them as real properties (and keep them when it exports XML). Everything else Flowable offers is refused by the
 * server's validator, so it is deliberately not described here.
 */
export const flowableModdle = {
  name: 'Flowable',
  uri: 'http://flowable.org/bpmn',
  prefix: 'flowable',
  xml: { tagAlias: 'lowerCase' },
  types: [
    {
      name: 'ApprovalUserTask',
      extends: ['bpmn:UserTask'],
      properties: [{ name: 'assignee', isAttr: true, type: 'String' }],
    },
    {
      name: 'ApprovalLoop',
      extends: ['bpmn:MultiInstanceLoopCharacteristics'],
      properties: [
        { name: 'collection', isAttr: true, type: 'String' },
        { name: 'elementVariable', isAttr: true, type: 'String' },
      ],
    },
  ],
} as const
