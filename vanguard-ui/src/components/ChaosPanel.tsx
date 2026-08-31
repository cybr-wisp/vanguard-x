import React, { useState } from 'react';

/**
 * Safe chaos controls for the demo. Sends commands to the backend
 * to inject failures and observe system response in real time.
 *
 * Controls:
 *   - Kill/restart tracking processor
 *   - Inject packet loss percentage
 *   - Inject jitter (ms)
 *   - Spike report volume (multiplier)
 *   - Degrade one sensor's noise
 */
export const ChaosPanel: React.FC = () => {
  const [expanded, setExpanded] = useState(false);
  const [lossRate, setLossRate] = useState(0);
  const [jitterMs, setJitterMs] = useState(0);
  const [spikeMultiplier, setSpikeMultiplier] = useState(1);

  const sendChaosCommand = async (command: string, params: Record<string, number | string>) => {
    try {
      await fetch('/api/chaos', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ command, ...params }),
      });
    } catch (e) {
      console.error('Chaos command failed:', e);
    }
  };

  if (!expanded) {
    return (
      <button
        onClick={() => setExpanded(true)}
        style={{
          position: 'absolute', bottom: 12, right: 12,
          background: '#2a1a1a', border: '1px solid #633', borderRadius: 4,
          color: '#f66', fontFamily: 'monospace', fontSize: 11,
          padding: '6px 12px', cursor: 'pointer',
        }}
      >
        CHAOS
      </button>
    );
  }

  return (
    <div style={{
      position: 'absolute', bottom: 12, right: 12, width: 240,
      background: 'rgba(30, 15, 15, 0.95)', border: '1px solid #633',
      borderRadius: 6, padding: 14, fontFamily: 'monospace', fontSize: 11,
      color: '#ccc',
    }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 10 }}>
        <span style={{ color: '#f66', fontWeight: 'bold' }}>CHAOS CONTROLS</span>
        <button onClick={() => setExpanded(false)} style={{
          background: 'none', border: 'none', color: '#666', cursor: 'pointer'
        }}>x</button>
      </div>

      <div style={{ marginBottom: 8 }}>
        <label style={{ color: '#888' }}>Packet loss: {lossRate}%</label>
        <input type="range" min={0} max={50} value={lossRate}
               onChange={e => {
                 const val = parseInt(e.target.value);
                 setLossRate(val);
                 sendChaosCommand('set_loss', { rate: val / 100 });
               }}
               style={{ width: '100%' }} />
      </div>

      <div style={{ marginBottom: 8 }}>
        <label style={{ color: '#888' }}>Jitter: {jitterMs}ms</label>
        <input type="range" min={0} max={500} step={10} value={jitterMs}
               onChange={e => {
                 const val = parseInt(e.target.value);
                 setJitterMs(val);
                 sendChaosCommand('set_jitter', { ms: val });
               }}
               style={{ width: '100%' }} />
      </div>

      <div style={{ marginBottom: 10 }}>
        <label style={{ color: '#888' }}>Traffic spike: {spikeMultiplier}x</label>
        <input type="range" min={1} max={10} value={spikeMultiplier}
               onChange={e => {
                 const val = parseInt(e.target.value);
                 setSpikeMultiplier(val);
                 sendChaosCommand('set_spike', { multiplier: val });
               }}
               style={{ width: '100%' }} />
      </div>

      <button onClick={() => sendChaosCommand('kill_processor', {})}
              style={chaosBtn}>Kill Processor</button>
      <button onClick={() => sendChaosCommand('restart_processor', {})}
              style={{ ...chaosBtn, borderColor: '#363' }}>Restart Processor</button>
      <button onClick={() => {
        setLossRate(0); setJitterMs(0); setSpikeMultiplier(1);
        sendChaosCommand('reset_all', {});
      }} style={{ ...chaosBtn, borderColor: '#666' }}>Reset All</button>
    </div>
  );
};

const chaosBtn: React.CSSProperties = {
  width: '100%', marginBottom: 4, padding: '5px 0',
  background: 'none', border: '1px solid #633', borderRadius: 3,
  color: '#f88', cursor: 'pointer', fontFamily: 'monospace', fontSize: 10,
};
