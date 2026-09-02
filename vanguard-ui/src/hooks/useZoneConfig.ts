import { useEffect, useState } from 'react'
import type { ZoneDefinition } from '../lib/types'

const BACKEND_HOST =
  (import.meta.env.VITE_BACKEND_HOST as string | undefined)?.trim()
  || `${window.location.hostname}:8081`

const HTTP_SCHEME = window.location.protocol === 'https:' ? 'https' : 'http'
const ZONES_URL = `${HTTP_SCHEME}://${BACKEND_HOST}/api/zones`

export function useZoneConfig() {
  const [zones, setZones] = useState<ZoneDefinition[]>([])
  const [connected, setConnected] = useState(false)

  useEffect(() => {
    let cancelled = false
    let timer: number | undefined

    const load = async () => {
      try {
        const response = await fetch(ZONES_URL, { cache: 'no-store' })
        if (!response.ok) throw new Error(`HTTP ${response.status}`)

        const payload = await response.json() as ZoneDefinition[]
        if (cancelled) return

        setZones(Array.isArray(payload) ? payload : [])
        setConnected(Array.isArray(payload) && payload.length > 0)

        timer = window.setTimeout(load, 10_000)
      } catch {
        if (cancelled) return
        setConnected(false)
        timer = window.setTimeout(load, 2_000)
      }
    }

    void load()

    return () => {
      cancelled = true
      if (timer !== undefined) window.clearTimeout(timer)
    }
  }, [])

  return { zones, connected }
}
