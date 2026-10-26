import { useEffect, useRef, useState } from 'react'
import { Alert } from 'antd'
import NavigatedViewer from 'bpmn-js/lib/NavigatedViewer'
import 'bpmn-js/dist/assets/diagram-js.css'
import 'bpmn-js/dist/assets/bpmn-js.css'

interface CanvasService {
  zoom: (level: 'fit-viewport') => void
}

interface Props {
  xml: string
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


/**
 * Renders a BPMN 2.0 diagram (pan with drag, zoom with ctrl+wheel). Read-only: the flow is changed through the
 * rounds designer or the XML editor so that every saved flow keeps the structure the engine relies on.
 * The bpmn.io logo is part of the library's license terms and must stay visible.
 */
export default function BpmnDiagram({ xml, height = 420 }: Props) {
  const container = useRef<HTMLDivElement>(null)
  const viewer = useRef<NavigatedViewer | null>(null)
  const stopFit = useRef<() => void>(() => undefined)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!container.current) return
    const instance = new NavigatedViewer({ container: container.current })
    viewer.current = instance
    return () => {
      stopFit.current()
      instance.destroy()
      viewer.current = null
    }
  }, [])

  useEffect(() => {
    const instance = viewer.current
    if (!instance) return
    if (!xml.trim()) {
      setError(null)
      instance.clear()
      return
    }
    let cancelled = false
    instance
      .importXML(xml)
      .then(() => {
        if (cancelled) return
        setError(null)
        stopFit.current()
        stopFit.current = fitWhenVisible(instance.get<CanvasService>('canvas'), container.current)
      })
      .catch((err: unknown) => {
        if (!cancelled) setError(err instanceof Error ? err.message : 'The diagram could not be drawn')
      })
    return () => {
      cancelled = true
    }
  }, [xml])

  return (
    <>
      {error && <Alert type="warning" showIcon style={{ marginBottom: 8 }} message={`Cannot draw this XML: ${error}`} />}
      <div ref={container} style={{ height, width: '100%' }} />
    </>
  )
}
