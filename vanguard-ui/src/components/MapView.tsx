import React, { useEffect, useState } from 'react'
import maplibregl from 'maplibre-gl'
import {
  AlertTriangle,
  Crosshair,
  Filter,
  Layers,
  Maximize2,
  Radio,
} from 'lucide-react'

import type { FusedTrack, ZoneDefinition } from '../lib/types'
import type { TrailPoint } from '../lib/uiTypes'

import {
  MAP_CENTER,
  MAP_ZOOM,
  METERS_PER_DEG_LAT,
  METERS_PER_DEG_LNG,
  SENSORS,
  TRACK_COLORS,
} from '../config/tactical'

import {
  LegendDot,
  MapToggle,
} from './ui/UiPrimitives'

import { headingDeg } from '../lib/uiUtils'

export function MapView({
  visible,
  mapContainer,
  mapRef,
  aliveTracks,
  trails,
  layers,
  setLayers,
  selectedId,
  setSelectedId,
  connected,
  zones,
}: {
  visible: boolean
  mapContainer: React.RefObject<HTMLDivElement>
  mapRef: React.MutableRefObject<maplibregl.Map | null>
  aliveTracks: Array<[string, FusedTrack]>
  trails: React.MutableRefObject<Map<string, TrailPoint[]>>
  layers: Record<string, boolean>
  setLayers: React.Dispatch<React.SetStateAction<any>>
  selectedId: string | null
  setSelectedId: (id: string) => void
  connected: boolean
  zones: ZoneDefinition[]
}) {
  const [, forceMapRender] = useState(0)

  useEffect(() => {
    if (!mapContainer.current || mapRef.current) return

    const sources: Record<string, any> = {
      imagery: {
        type: 'raster',
        tiles: [
          'https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}',
        ],
        tileSize: 256,
        attribution: 'Esri, Maxar, Earthstar Geographics, and the GIS User Community',
      },
      reference: {
        type: 'raster',
        tiles: [
          'https://services.arcgisonline.com/ArcGIS/rest/services/Reference/World_Boundaries_and_Places/MapServer/tile/{z}/{y}/{x}',
        ],
        tileSize: 256,
        attribution: 'Esri',
      },
    }

    const styleLayers: any[] = [
      {
        id: 'background',
        type: 'background',
        paint: {
          'background-color': '#070b0c',
        },
      },
      {
        id: 'world-imagery',
        type: 'raster',
        source: 'imagery',
        paint: {
          'raster-opacity': 1,
          'raster-saturation': -0.10,
          'raster-contrast': 0.12,
          'raster-brightness-min': 0.03,
          'raster-brightness-max': 0.90,
        },
      },
      {
        id: 'world-reference',
        type: 'raster',
        source: 'reference',
        paint: {
          'raster-opacity': 0.94,
        },
      },
    ]

    const map = new maplibregl.Map({
      container: mapContainer.current,
      style: { version: 8, sources, layers: styleLayers } as any,
      center: MAP_CENTER,
      zoom: MAP_ZOOM,
      pitch: 0,
      bearing: 0,
      attributionControl: false,
    })

    map.addControl(new maplibregl.NavigationControl({ showCompass: true }), 'top-right')
    map.addControl(new maplibregl.ScaleControl({ maxWidth: 100, unit: 'metric' }), 'bottom-right')
    map.addControl(new maplibregl.AttributionControl({ compact: true }), 'bottom-left')

    const refresh = () => forceMapRender(value => value + 1)
    map.on('move', refresh)
    map.on('zoom', refresh)
    map.on('resize', refresh)
    map.on('load', refresh)

    mapRef.current = map

    return () => {
      map.off('move', refresh)
      map.off('zoom', refresh)
      map.off('resize', refresh)
      map.remove()
      mapRef.current = null
    }
  }, [mapContainer, mapRef])

  useEffect(() => {
    const map = mapRef.current
    if (!map) return

    if (!map.isStyleLoaded()) {
      const onLoad = () => syncZoneLayers(map, zones, layers.geo)
      map.once('load', onLoad)
      return () => {
        map.off('load', onLoad)
      }
    }

    syncZoneLayers(map, zones, layers.geo)
  }, [zones, layers.geo, mapRef])

  useEffect(() => {
    if (!visible) return
    const timer = window.setTimeout(() => mapRef.current?.resize(), 0)
    return () => window.clearTimeout(timer)
  }, [visible, mapRef])

  return (
    <div className={`map-view ${visible ? 'visible' : 'hidden'}`}>
      <div ref={mapContainer} className="map-canvas" />

      <div className="map-toolbar">
        <MapToggle icon={<Crosshair size={16} />} label="Tracks" active={layers.tracks} onClick={() => setLayers((value: any) => ({ ...value, tracks: !value.tracks }))} />
        <MapToggle icon={<Layers size={16} />} label="Uncertainty" active={layers.ellipse} onClick={() => setLayers((value: any) => ({ ...value, ellipse: !value.ellipse }))} />
        <MapToggle icon={<Filter size={16} />} label="Zones" active={layers.geo} onClick={() => setLayers((value: any) => ({ ...value, geo: !value.geo }))} />
        <MapToggle icon={<Maximize2 size={16} />} label="Trails" active={layers.trails} onClick={() => setLayers((value: any) => ({ ...value, trails: !value.trails }))} />
        <MapToggle icon={<Radio size={16} />} label="Sensors" active={layers.sensors} onClick={() => setLayers((value: any) => ({ ...value, sensors: !value.sensors }))} />
      </div>

      {!connected && (
        <div className="connection-banner">
          <AlertTriangle size={15} />
          Waiting for backend streams on :8081
        </div>
      )}

      <div className="zone-caption">
        <div>{zones.length} ACTIVE GEOFENCES</div>
        <span>BACKEND-SYNCHRONIZED CORE / WARNING / ADVISORY GEOMETRY</span>
      </div>

      <div className="map-legend">
        <LegendDot color={TRACK_COLORS.CONFIRMED} label="Confirmed track" />
        <LegendDot color={TRACK_COLORS.TENTATIVE} label="Tentative track" />
        <LegendDot color={TRACK_COLORS.COASTING} label="Coasting track" />
        <LegendDot color="#d9535f" label="Restricted core" />
        <LegendDot color="#7b4bc4" label="Sensor site" />
      </div>

      <svg className="track-overlay" viewBox={`0 0 ${mapContainer.current?.clientWidth || 1000} ${mapContainer.current?.clientHeight || 700}`}>
        {mapRef.current && layers.geo && zones.map(zone => {
          const point = mapRef.current!.project(zone.center)
          return (
            <g key={`label-${zone.zoneId}`} className="zone-svg-label">
              <rect x={point.x - 47} y={point.y - 11} width={94} height={23} rx={2} fill="rgba(255,255,255,.9)" stroke={zone.color} strokeWidth={1} />
              <text x={point.x} y={point.y - 1} textAnchor="middle" fill={zone.color} fontSize={10} fontWeight={750}>{zone.zoneId}</text>
              <text x={point.x} y={point.y + 9} textAnchor="middle" fill="#627080" fontSize={7.5}>RESTRICTED AIRSPACE</text>
            </g>
          )
        })}

        {mapRef.current && layers.tracks && aliveTracks.map(([id, track], trackIndex) => {
          const map = mapRef.current!
          const point = map.project([track.px, track.py])
          const color = TRACK_COLORS[track.state]
          const selected = selectedId === id
          const heading = headingDeg(track.vx, track.vy)
          const speedMps = Math.hypot(track.vx, track.vy)
          const trail = trails.current.get(id) || []

          // Deterministic tactical label staggering keeps dense
          // multi-target scenes readable without moving the track itself.
          const labelOffsets = [
            { x: 13,   y: -25 },
            { x: 15,   y: 12 },
            { x: -126, y: -27 },
            { x: -126, y: 11 },
            { x: 18,   y: -43 },
            { x: -126, y: -45 },
            { x: 20,   y: 27 },
            { x: -126, y: 27 }
          ]

          const labelOffset =
            labelOffsets[trackIndex % labelOffsets.length]

          const ellipseMajorM = Math.max(1, (track.ellipseMajor ?? track.uncertainty * 2) / 2)
          const ellipseMinorM = Math.max(1, (track.ellipseMinor ?? track.uncertainty * 1.2) / 2)
          const eastPoint = map.project([track.px + ellipseMajorM / METERS_PER_DEG_LNG, track.py])
          const northPoint = map.project([track.px, track.py + ellipseMinorM / METERS_PER_DEG_LAT])
          const rx = Math.min(85, Math.max(4, Math.abs(eastPoint.x - point.x)))
          const ry = Math.min(70, Math.max(3, Math.abs(northPoint.y - point.y)))

          return (
            <g key={id} className="track-group" onClick={() => setSelectedId(id)}>
              {layers.trails && trail.length > 1 && (
                <polyline
                  points={trail.map(trailPoint => {
                    const projected = map.project([trailPoint.lng, trailPoint.lat])
                    return `${projected.x},${projected.y}`
                  }).join(' ')}
                  fill="none"
                  stroke={color}
                  strokeWidth={1.6}
                  opacity={0.72}
                  strokeDasharray="7 6"
                />
              )}

              {layers.ellipse && (
                <ellipse
                  cx={point.x}
                  cy={point.y}
                  rx={rx}
                  ry={ry}
                  transform={`rotate(${track.ellipseAngle ?? heading} ${point.x} ${point.y})`}
                  fill={selected ? 'rgba(22,132,180,.08)' : 'rgba(22,132,180,.025)'}
                  stroke="#238db8"
                  strokeWidth={1.2}
                  opacity={selected ? 0.82 : 0.42}
                  strokeDasharray="4 3"
                />
              )}
              {selected && (
                <circle
                  cx={point.x}
                  cy={point.y}
                  r={18}
                  fill="none"
                  stroke="#fff6d7"
                  strokeWidth={1}
                  opacity={0.72}
                  strokeDasharray="3 4"
                />
              )}

              <g
                transform={`translate(${point.x} ${point.y}) rotate(${heading})`}
                className="aircraft-glyph"
              >
                <path
                  d="M0,-13
                     L2.2,-4.5
                     L10.5,-1
                     L10.5,1.5
                     L2.8,1.2
                     L1.6,8.5
                     L5,11
                     L5,12.5
                     L0,10.8
                     L-5,12.5
                     L-5,11
                     L-1.6,8.5
                     L-2.8,1.2
                     L-10.5,1.5
                     L-10.5,-1
                     L-2.2,-4.5
                     Z"
                  fill={selected ? '#fff8d8' : color}
                  stroke="#06100f"
                  strokeWidth={1.1}
                />
              </g>

              <g transform={`translate(${point.x + labelOffset.x},${point.y + labelOffset.y}) scale(0.84)`}>
                <rect width={140} height={34} rx={2} fill={selected ? 'rgba(6,13,14,.96)' : 'rgba(6,13,14,.88)'} stroke={color} strokeWidth={selected ? 1.4 : 0.8} />
                <text x={9} y={13} fill="#f4f7fa" fontSize={10.5} fontWeight={750}>{id}</text>
                <text x={9} y={26} fill="#a7b4c0" fontSize={8.5}>{track.state} · {Math.round(speedMps)} m/s</text>
              </g>
            </g>
          )
        })}

        {mapRef.current && layers.sensors && SENSORS.map(sensor => {
          const point = mapRef.current!.project([sensor.lng, sensor.lat])
          return (
            <g key={sensor.id}>
              <circle cx={point.x} cy={point.y} r={11} fill="rgba(255,255,255,.92)" stroke="#7549bb" strokeWidth={1.6} />
              <circle cx={point.x} cy={point.y} r={3.5} fill="#7549bb" />
              <text x={point.x} y={point.y + 25} fill="#293746" fontSize={10} textAnchor="middle" fontWeight={750}>{sensor.id}</text>
            </g>
          )
        })}
      </svg>
    </div>
  )
}

function syncZoneLayers(map: maplibregl.Map, zones: ZoneDefinition[], visible: boolean) {
  const features = zones.flatMap(zone => [
    zoneFeature(zone.advisory, zone, 'advisory'),
    zoneFeature(zone.warning, zone, 'warning'),
    zoneFeature(zone.core, zone, 'core'),
  ])

  const data = {
    type: 'FeatureCollection',
    features,
  } as any

  const existing = map.getSource('backend-zones') as maplibregl.GeoJSONSource | undefined
  if (existing) {
    existing.setData(data)
  } else {
    map.addSource('backend-zones', { type: 'geojson', data })

    map.addLayer({
      id: 'zones-core-fill',
      type: 'fill',
      source: 'backend-zones',
      filter: ['==', ['get', 'level'], 'core'],
      paint: {
        'fill-color': ['get', 'color'],
        'fill-opacity': 0.16,
      },
    } as any)

    map.addLayer({
      id: 'zones-advisory',
      type: 'line',
      source: 'backend-zones',
      filter: ['==', ['get', 'level'], 'advisory'],
      paint: {
        'line-color': ['get', 'color'],
        'line-width': 1.5,
        'line-opacity': 0.45,
        'line-dasharray': [4, 3],
      },
    } as any)

    map.addLayer({
      id: 'zones-warning',
      type: 'line',
      source: 'backend-zones',
      filter: ['==', ['get', 'level'], 'warning'],
      paint: {
        'line-color': ['get', 'color'],
        'line-width': 1.8,
        'line-opacity': 0.68,
        'line-dasharray': [5, 3],
      },
    } as any)

    map.addLayer({
      id: 'zones-core-line',
      type: 'line',
      source: 'backend-zones',
      filter: ['==', ['get', 'level'], 'core'],
      paint: {
        'line-color': ['get', 'color'],
        'line-width': 2.4,
        'line-opacity': 0.94,
        'line-dasharray': [5, 3],
      },
    } as any)
  }

  for (const layerId of ['zones-core-fill', 'zones-advisory', 'zones-warning', 'zones-core-line']) {
    if (map.getLayer(layerId)) {
      map.setLayoutProperty(layerId, 'visibility', visible ? 'visible' : 'none')
    }
  }
}

function zoneFeature(
  coordinates: [number, number][],
  zone: ZoneDefinition,
  level: 'core' | 'warning' | 'advisory',
) {
  return {
    type: 'Feature',
    properties: {
      zoneId: zone.zoneId,
      label: zone.label,
      color: zone.color,
      level,
    },
    geometry: {
      type: 'Polygon',
      coordinates: [coordinates],
    },
  }
}
