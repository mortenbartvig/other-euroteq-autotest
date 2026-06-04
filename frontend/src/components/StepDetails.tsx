import { useRef } from 'react';

interface Step {
  step?: string;
  phase?: string;
  status?: string;
  url?: string;
  httpStatus?: number;
  error?: string;
  durationMs?: number;
  mismatches?: Record<string, { sent: unknown; saved: unknown }>;
  note?: string;
  [key: string]: unknown;
}

interface StepDetailsProps {
  stepDetailsJson: string;
}

// ── Constants ──────────────────────────────────────────────────────────────

const PHASE_ORDER = ['setup', 'enrollment', 'duplicate', 'verification', 'cleanup', 'boundary'];
const PHASE_LABELS: Record<string, string> = {
  setup:        'Setup',
  enrollment:   'Broker Enrollment',
  duplicate:    'Duplicate Check',
  verification: 'Verification',
  cleanup:      'Cleanup',
  boundary:     'Boundary Tests',
};
const PHASE_COLORS: Record<string, string> = {
  setup:        '#3b82f6',
  enrollment:   '#8b5cf6',
  duplicate:    '#f59e0b',
  verification: '#10b981',
  cleanup:      '#6b7280',
  boundary:     '#ec4899',
};

const ACTOR_BY_STEP: Record<string, { label: string; color: string }> = {
  getToken:                   { label: 'Mock OAuth',   color: '#d97706' },
  brokerOAuthLogin:           { label: 'Mock OAuth',   color: '#d97706' },
  getPersonId:                { label: 'Home Server',  color: '#16a34a' },
  patchRemoteStateAssociated: { label: 'Home Server',  color: '#16a34a' },
  verifyRemoteStateAssociated:{ label: 'Home Server',  color: '#16a34a' },
  patchAssociationResult:     { label: 'Home Server',  color: '#16a34a' },
  verifyAssociationResult:    { label: 'Home Server',  color: '#16a34a' },
  cancelAssociation:          { label: 'Home Server',  color: '#16a34a' },
  verifyCanceled:             { label: 'Home Server',  color: '#16a34a' },
  postCancelUpdateAttempt:    { label: 'Home Server',  color: '#16a34a' },
  brokerInitEnrollment:       { label: 'Host Server',  color: '#2563eb' },
  brokerRedirectCallback:     { label: 'Host Server',  color: '#2563eb' },
  brokerStart:                { label: 'Host Server',  color: '#2563eb' },
  invalidBasicAuthTest:       { label: 'Host Server',  color: '#2563eb' },
  missingOfferingDataTest:    { label: 'Host Server',  color: '#2563eb' },
};

// ── Status helpers ─────────────────────────────────────────────────────────

function statusColor(status?: string): string {
  if (!status) return '#9ca3af';
  switch (status.toLowerCase()) {
    case 'success': return '#16a34a';
    case 'error':
    case 'failed':  return '#dc2626';
    case 'mismatch':return '#ea580c';
    case 'denied':  return '#7c3aed';
    case 'warning': return '#d97706';
    case 'skipped': return '#9ca3af';
    default:        return '#9ca3af';
  }
}

function statusBadgeStyle(status?: string): React.CSSProperties {
  const color = statusColor(status);
  return {
    display: 'inline-flex', alignItems: 'center',
    padding: '1px 8px', borderRadius: '999px',
    fontSize: '0.7rem', fontWeight: 700, letterSpacing: '0.04em',
    background: color + '20', color, border: `1px solid ${color}60`,
    textTransform: 'uppercase',
  };
}

function formatDurationMs(ms?: number): string | null {
  if (ms === undefined || ms === null) return null;
  if (ms < 1000) return `${ms}ms`;
  return `${(ms / 1000).toFixed(1)}s`;
}

// ── Flow strip ─────────────────────────────────────────────────────────────

function FlowStrip({ steps, onSelect }: { steps: Step[]; onSelect: (i: number) => void }) {
  const PHASES_SEEN = PHASE_ORDER.filter(p => steps.some(s => (s.phase ?? 'setup') === p));

  return (
    <div style={{ display: 'flex', flexWrap: 'wrap', gap: '8px', padding: '10px 12px',
                  background: '#f8fafc', borderBottom: '1px solid #e2e8f0', alignItems: 'center' }}>
      {PHASES_SEEN.map(phase => {
        const phaseSteps = steps.map((s, i) => ({ s, i })).filter(({ s }) => (s.phase ?? 'setup') === phase);
        return (
          <div key={phase} style={{ display: 'flex', alignItems: 'center', gap: '4px' }}>
            <span style={{ fontSize: '0.62rem', fontWeight: 700, color: PHASE_COLORS[phase] ?? '#6b7280',
                           textTransform: 'uppercase', letterSpacing: '0.06em', whiteSpace: 'nowrap' }}>
              {PHASE_LABELS[phase] ?? phase}
            </span>
            {phaseSteps.map(({ s, i }) => (
              <button key={i} title={`#${i + 1} ${s.step ?? ''} — ${s.status ?? '?'}`}
                onClick={() => onSelect(i)}
                style={{ width: 14, height: 14, borderRadius: '50%', border: 'none', cursor: 'pointer',
                         background: statusColor(s.status), flexShrink: 0, padding: 0,
                         outline: '2px solid transparent', transition: 'outline 0.1s' }}
                onMouseEnter={e => (e.currentTarget.style.outline = `2px solid ${statusColor(s.status)}`)}
                onMouseLeave={e => (e.currentTarget.style.outline = '2px solid transparent')}
              />
            ))}
            <span style={{ color: '#d1d5db', fontSize: '0.8rem' }}>│</span>
          </div>
        );
      })}
    </div>
  );
}

// ── Mismatch diff table ────────────────────────────────────────────────────

function formatMismatchValue(v: unknown): string {
  if (v === null || v === undefined) return 'null';
  if (typeof v === 'object') return JSON.stringify(v, null, 2);
  return String(v);
}

function MismatchTable({ mismatches }: { mismatches: Record<string, { sent: unknown; saved: unknown }> }) {
  return (
    <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '0.78rem', marginTop: '6px' }}>
      <thead>
        <tr style={{ background: '#fef2f2' }}>
          <th style={thStyle}>Field</th>
          <th style={thStyle}>Sent</th>
          <th style={thStyle}>Received</th>
          <th style={{ ...thStyle, width: '40px' }}></th>
        </tr>
      </thead>
      <tbody>
        {Object.entries(mismatches).map(([field, { sent, saved }]) => (
          <tr key={field}>
            <td style={tdStyle}><code>{field}</code></td>
            <td style={{ ...tdStyle, color: '#16a34a', whiteSpace: 'pre-wrap', fontFamily: 'monospace' }}>
              {formatMismatchValue(sent)}
            </td>
            <td style={{ ...tdStyle, color: '#dc2626', whiteSpace: 'pre-wrap', fontFamily: 'monospace' }}>
              {formatMismatchValue(saved)}
            </td>
            <td style={{ ...tdStyle, textAlign: 'center' }}>✗</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
const thStyle: React.CSSProperties = { padding: '4px 8px', textAlign: 'left', borderBottom: '1px solid #fca5a5',
  fontWeight: 700, color: '#991b1b', fontSize: '0.7rem', textTransform: 'uppercase' };
const tdStyle: React.CSSProperties = { padding: '4px 8px', borderBottom: '1px solid #fee2e2', fontFamily: 'monospace' };

// ── Single step row ────────────────────────────────────────────────────────

const SKIP_KEYS = new Set(['step', 'phase', 'status', 'url', 'httpStatus', 'error', 'durationMs', 'mismatches', 'note']);

function StepRow({ step, index, stepRef }: { step: Step; index: number; stepRef: React.Ref<HTMLDivElement> }) {
  const actor = ACTOR_BY_STEP[step.step ?? ''];
  const dur = formatDurationMs(step.durationMs);
  const extras = Object.entries(step).filter(([k]) => !SKIP_KEYS.has(k));

  return (
    <div ref={stepRef} style={{ padding: '10px 14px', borderBottom: '1px solid #f1f5f9' }}>
      {/* Header row */}
      <div style={{ display: 'flex', alignItems: 'center', gap: '8px', flexWrap: 'wrap' }}>
        <span style={{ fontSize: '0.7rem', color: '#9ca3af', fontWeight: 600, minWidth: '22px' }}>
          #{index + 1}
        </span>
        {step.step && (
          <span style={{ fontSize: '0.82rem', fontWeight: 700, color: '#1e293b', fontFamily: 'monospace' }}>
            {step.step}
          </span>
        )}
        {step.status && <span style={statusBadgeStyle(step.status)}>{step.status}</span>}
        {step.httpStatus !== undefined && (
          <span style={{ fontSize: '0.72rem', color: '#64748b', background: '#f1f5f9',
                         padding: '1px 6px', borderRadius: '4px', fontFamily: 'monospace' }}>
            HTTP {step.httpStatus}
          </span>
        )}
        {actor && (
          <span style={{ fontSize: '0.68rem', fontWeight: 700, color: actor.color,
                         background: actor.color + '15', padding: '1px 7px', borderRadius: '999px',
                         border: `1px solid ${actor.color}40` }}>
            {actor.label}
          </span>
        )}
        {dur && (
          <span style={{ fontSize: '0.68rem', color: '#94a3b8', marginLeft: 'auto' }}>⏱ {dur}</span>
        )}
      </div>

      {/* URL */}
      {step.url && (
        <div style={{ marginTop: '4px', fontSize: '0.73rem', color: '#64748b',
                      fontFamily: 'monospace', wordBreak: 'break-all' }}>
          {step.url}
        </div>
      )}

      {/* Note */}
      {step.note && (
        <div style={{ marginTop: '5px', fontSize: '0.76rem', color: '#64748b',
                      fontStyle: 'italic', lineHeight: 1.5 }}>
          {step.note}
        </div>
      )}

      {/* Error */}
      {step.error && (
        <div style={{ marginTop: '5px', padding: '6px 10px', background: '#fef2f2',
                      borderLeft: '3px solid #dc2626', borderRadius: '4px',
                      fontSize: '0.76rem', color: '#991b1b', wordBreak: 'break-word' }}>
          {step.error}
        </div>
      )}

      {/* Mismatch diff table */}
      {step.mismatches && Object.keys(step.mismatches).length > 0 && (
        <MismatchTable mismatches={step.mismatches as Record<string, { sent: unknown; saved: unknown }>} />
      )}

      {/* Extra fields */}
      {extras.map(([k, v]) => (
        <div key={k} style={{ display: 'flex', gap: '8px', marginTop: '3px',
                               fontSize: '0.73rem', alignItems: 'flex-start' }}>
          <span style={{ color: '#94a3b8', fontFamily: 'monospace', minWidth: '100px',
                         flexShrink: 0, paddingTop: '1px' }}>{k}:</span>
          <span style={{ color: '#475569', fontFamily: 'monospace', wordBreak: 'break-all' }}>
            {typeof v === 'object' ? JSON.stringify(v, null, 2) : String(v)}
          </span>
        </div>
      ))}
    </div>
  );
}

// ── Phase section ──────────────────────────────────────────────────────────

function PhaseSection({ phase, steps, startIndex, stepRefs }:
  { phase: string; steps: Step[]; startIndex: number; stepRefs: React.MutableRefObject<(HTMLDivElement | null)[]> }) {
  const color = PHASE_COLORS[phase] ?? '#6b7280';
  const allOk  = steps.every(s => s.status === 'success' || s.status === 'skipped');
  const anyErr = steps.some(s => s.status === 'error' || s.status === 'mismatch');

  return (
    <div style={{ marginBottom: '2px' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: '8px',
                    padding: '6px 14px', background: color + '10',
                    borderLeft: `3px solid ${color}`, borderBottom: '1px solid #e2e8f0' }}>
        <span style={{ fontSize: '0.7rem', fontWeight: 800, color, textTransform: 'uppercase',
                       letterSpacing: '0.08em' }}>
          {PHASE_LABELS[phase] ?? phase}
        </span>
        <span style={{ fontSize: '0.68rem', color: '#94a3b8' }}>{steps.length} step{steps.length !== 1 ? 's' : ''}</span>
        {anyErr && <span style={{ fontSize: '0.68rem', color: '#dc2626', fontWeight: 700 }}>● issues</span>}
        {!anyErr && allOk && <span style={{ fontSize: '0.68rem', color: '#16a34a' }}>✓ all ok</span>}
      </div>
      {steps.map((step, i) => (
        <StepRow
          key={startIndex + i}
          step={step}
          index={startIndex + i}
          stepRef={el => { stepRefs.current[startIndex + i] = el; }}
        />
      ))}
    </div>
  );
}

// ── Main export ────────────────────────────────────────────────────────────

export function StepDetails({ stepDetailsJson }: StepDetailsProps) {
  const stepRefs = useRef<(HTMLDivElement | null)[]>([]);

  let steps: Step[];
  try {
    const parsed = JSON.parse(stepDetailsJson);
    steps = Array.isArray(parsed) ? parsed : [parsed];
  } catch {
    return <pre style={{ fontSize: '0.78rem', padding: '12px' }}>{stepDetailsJson}</pre>;
  }

  if (steps.length === 0) return <p style={{ padding: '12px', color: '#94a3b8' }}>No steps recorded.</p>;

  function scrollTo(i: number) {
    stepRefs.current[i]?.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
  }

  // Group steps by phase
  const grouped = new Map<string, { step: Step; originalIndex: number }[]>();
  steps.forEach((s, i) => {
    const phase = s.phase ?? 'setup';
    if (!grouped.has(phase)) grouped.set(phase, []);
    grouped.get(phase)!.push({ step: s, originalIndex: i });
  });

  // Order phases
  const orderedPhases: string[] = [];
  for (const p of PHASE_ORDER) { if (grouped.has(p)) orderedPhases.push(p); }
  for (const [p] of grouped)   { if (!orderedPhases.includes(p)) orderedPhases.push(p); }

  return (
    <div style={{ border: '1px solid #e2e8f0', borderRadius: '8px', overflow: 'hidden',
                  background: 'white', fontSize: '0.82rem' }}>
      <FlowStrip steps={steps} onSelect={scrollTo} />
      {orderedPhases.map(phase => {
        const entries = grouped.get(phase)!;
        const firstIndex = entries[0].originalIndex;
        return (
          <PhaseSection
            key={phase}
            phase={phase}
            steps={entries.map(e => e.step)}
            startIndex={firstIndex}
            stepRefs={stepRefs}
          />
        );
      })}
    </div>
  );
}
