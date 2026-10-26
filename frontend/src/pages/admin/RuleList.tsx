import { useEffect, useState } from 'react'
import { Select } from 'antd'
import { listTemplates } from '@/api/templates'
import PageContainer from '@/components/PageContainer'
import RulesPanel from '@/pages/admin/RulesPanel'

/** The approval matrix across all templates; a template can also be edited from its own page. */
export default function RuleList() {
  const [keys, setKeys] = useState<string[]>([])
  const [filter, setFilter] = useState<string | undefined>(undefined)

  useEffect(() => {
    listTemplates()
      .then((all) => setKeys(all.map((t) => t.templateKey)))
      .catch(() => setKeys([]))
  }, [])

  return (
    <PageContainer
      title="Approver Rules"
      subtitle="Which approvers a request gets, decided by its amount and fields. The first matching rule (lowest priority number) wins."
      extra={
        <Select
          allowClear
          placeholder="All templates"
          style={{ width: 220 }}
          value={filter}
          onChange={setFilter}
          options={keys.map((k) => ({ value: k, label: k }))}
        />
      }
    >
      <RulesPanel key={filter ?? 'all'} templateKey={filter} templateKeys={keys} />
    </PageContainer>
  )
}
