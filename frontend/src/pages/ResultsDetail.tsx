import { useEffect, useRef, useState } from 'react';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import {
  ActualResult,
  TestResultDto,
  TestRun,
  extractErrorMessage,
  testRunsApi,
} from '../api/client';
import { StepDetails } from '../components/StepDetails';

// ── Helpers ────────────────────────────────────────────────────────────────

function resultBadgeClass(result: ActualResult): string {
  switch (result) {
    case 'SUCCESS': return 'badge badge-success';
    case 'DENIED':  return 'badge badge-warning';
    case 'ERROR':   return 'badge badge-danger';
    case 'SKIPPED': return 'badge badge-secondary';
    default:        return 'badge badge-secondary';
  }
}

function durationMs(start: string, end: string | null): number | null {
  if (!end) return null;
  return new Date(end).getTime() - new Date(start).getTime();
}

function formatDuration(start: string, end: string | null): string {
  const ms = durationMs(start, end);
  if (ms === null) return '—';
  if (ms < 1000) return `${ms}ms`;
  return `${(ms / 1000).toFixed(1)}s`;
}

function statusLabel(status: TestRun['status']): string {
  switch (status) {
    case 'COMPLETED_WITH_ERRORS':    return 'Completed with errors';
    case 'COMPLETED_WITH_DENIED':    return 'Completed (with denials)';
    default:                         return status;
  }
}

// ── CSV Export ─────────────────────────────────────────────────────────────

function exportCSV(results: TestResultDto[], runId: number) {
  const headers = ['ID', 'Test User', 'Home Server', 'Offering', 'Host Server', 'Result', 'Duration (s)', 'Error'];
  const rows = results.map(r => [
    r.id,
    r.testUserName,
    r.homeServerName,
    r.offeringName,
    r.hostServerName,
    r.actualResult,
    r.completedAt ? ((new Date(r.completedAt).getTime() - new Date(r.startedAt).getTime()) / 1000).toFixed(2) : '',
    r.errorMessage ?? '',
  ]);
  const csv = [headers, ...rows]
    .map(row => row.map(cell => `"${String(cell).replace(/"/g, '""')}"`).join(','))
    .join('\n');
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = `test-run-${runId}.csv`;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
}

// ── Timeline (Gantt) view ──────────────────────────────────────────────────

const RESULT_COLORS: Record<ActualResult, string> = {
  SUCCESS: '#16a34a',
  DENIED:  '#7c3aed',
  ERROR:   '#dc2626',
  SKIPPED: '#9ca3af',
};

function Timeline({ results, runStart }: { results: TestResultDto[]; runStart: Date }) {
  const canvasRef = useRef<HTMLCanvasElement>(null);

  const sorted = [...results].filter(r => r.completedAt).sort((a, b) =>
    new Date(a.startedAt).getTime() - new Date(b.startedAt).getTime()
  );

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas || sorted.length === 0) return;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    const ROW_H = 28, LABEL_W = 180, PAD = 10;
    const totalMs = Math.max(...sorted.map(r => new Date(r.completedAt!).getTime())) - runStart.getTime();
    const W = canvas.width - LABEL_W - PAD * 2;
    const H = sorted.length * ROW_H + PAD * 2;
    canvas.height = H;

    ctx.clearRect(0, 0, canvas.width, canvas.height);
    ctx.fillStyle = '#f8fafc';
    ctx.fillRect(0, 0, canvas.width, canvas.height);

    sorted.forEach((r, i) => {
      const y = PAD + i * ROW_H;
      const startMs = new Date(r.startedAt).getTime() - runStart.getTime();
      const endMs = new Date(r.completedAt!).getTime() - runStart.getTime();
      const x0 = LABEL_W + PAD + (startMs / totalMs) * W;
      const x1 = LABEL_W + PAD + (endMs / totalMs) * W;
      const bw = Math.max(x1 - x0, 4);

      // Row background
      ctx.fillStyle = i % 2 === 0 ? '#f1f5f9' : '#f8fafc';
      ctx.fillRect(0, y, canvas.width, ROW_H);

      // Label
      ctx.fillStyle = '#374151';
      ctx.font = '11px system-ui, sans-serif';
      ctx.textBaseline = 'middle';
      ctx.fillText(
        `${r.testUserName} × ${r.offeringName}`.substring(0, 28),
        PAD, y + ROW_H / 2
      );

      // Bar
      ctx.fillStyle = RESULT_COLORS[r.actualResult];
      const barY = y + 6;
      const barH = ROW_H - 12;
      ctx.beginPath();
      ctx.roundRect(x0, barY, bw, barH, 3);
      ctx.fill();

      // Duration label on bar
      const ms = endMs - startMs;
      const label = ms < 1000 ? `${ms}ms` : `${(ms / 1000).toFixed(1)}s`;
      ctx.fillStyle = '#fff';
      ctx.font = 'bold 9px system-ui, sans-serif';
      if (bw > 30) ctx.fillText(label, x0 + 4, barY + barH / 2);
    });

    // Time axis ticks
    const ticks = 5;
    ctx.strokeStyle = '#e2e8f0';
    ctx.fillStyle = '#94a3b8';
    ctx.font = '9px system-ui';
    for (let t = 0; t <= ticks; t++) {
      const x = LABEL_W + PAD + (t / ticks) * W;
      ctx.beginPath(); ctx.moveTo(x, 0); ctx.lineTo(x, H); ctx.stroke();
      const ms = (totalMs * t) / ticks;
      const label = ms < 1000 ? `${Math.round(ms)}ms` : `${(ms / 1000).toFixed(1)}s`;
      ctx.fillText(label, x + 2, PAD - 2);
    }
  }, [sorted, runStart]);

  if (sorted.length === 0) return null;

  return (
    <div className="card mb-4">
      <div className="card-header">
        <h2 className="card-title">Parallel Execution Timeline</h2>
        <span style={{ fontSize: '0.75rem', color: '#94a3b8' }}>
          Shows when each test ran relative to the run start
        </span>
      </div>
      <div className="card-body p-0" style={{ overflowX: 'auto' }}>
        <canvas ref={canvasRef} width={900} style={{ display: 'block', maxWidth: '100%' }} />
      </div>
    </div>
  );
}

// ── Result row ─────────────────────────────────────────────────────────────

function ResultRow({ result }: { result: TestResultDto }) {
  const [expanded, setExpanded] = useState(false);

  return (
    <>
      <tr>
        <td>{result.testUserName}</td>
        <td>{result.homeServerName}</td>
        <td>{result.offeringName}</td>
        <td>{result.hostServerName}</td>
        <td style={{ display: 'flex', alignItems: 'center', gap: '6px', flexWrap: 'wrap' }}>
          <span className={resultBadgeClass(result.actualResult)}>{result.actualResult}</span>
          {result.hasWarnings && (
            <span style={{ fontSize: '0.68rem', fontWeight: 700, color: '#92400e',
                           background: '#fef3c7', border: '1px solid #fbbf24',
                           borderRadius: '4px', padding: '1px 6px' }}>
              ⚠ warnings
            </span>
          )}
        </td>
        <td>{formatDuration(result.startedAt, result.completedAt)}</td>
        <td className="actions-cell">
          {result.stepDetails && (
            <button className="btn btn-secondary btn-xs"
              onClick={() => setExpanded(v => !v)}>
              {expanded ? 'Hide Steps' : 'Show Steps'}
            </button>
          )}
        </td>
      </tr>
      {expanded && result.stepDetails && (
        <tr className="steps-row">
          <td colSpan={7}>
            {result.errorMessage && (
              <div className="alert alert-error mb-2">Error: {result.errorMessage}</div>
            )}
            <StepDetails stepDetailsJson={result.stepDetails} />
          </td>
        </tr>
      )}
    </>
  );
}

// ── Page ───────────────────────────────────────────────────────────────────

export function ResultsDetail() {
  const { testRunId } = useParams<{ testRunId: string }>();
  const [searchParams] = useSearchParams();
  const homeServerId = searchParams.get('homeServerId') ? Number(searchParams.get('homeServerId')) : undefined;
  const hostServerId = searchParams.get('hostServerId') ? Number(searchParams.get('hostServerId')) : undefined;
  const runId = Number(testRunId);

  const [run, setRun] = useState<TestRun | null>(null);
  const [results, setResults] = useState<TestResultDto[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [filterActual, setFilterActual] = useState<string>('ALL');
  const [showTimeline, setShowTimeline] = useState(false);

  useEffect(() => {
    async function load() {
      try {
        const [runRes, detailRes] = await Promise.all([
          testRunsApi.get(runId),
          testRunsApi.detail(runId, homeServerId, hostServerId),
        ]);
        setRun(runRes.data);
        setResults(detailRes.data);
      } catch (err) {
        setError(extractErrorMessage(err));
      } finally {
        setLoading(false);
      }
    }
    load();
  }, [runId, homeServerId, hostServerId]);

  const filtered = results.filter(r => filterActual === 'ALL' || r.actualResult === filterActual);

  const successCount = results.filter(r => r.actualResult === 'SUCCESS').length;
  const deniedCount  = results.filter(r => r.actualResult === 'DENIED').length;
  const errorCount   = results.filter(r => r.actualResult === 'ERROR').length;
  const skippedCount = results.filter(r => r.actualResult === 'SKIPPED').length;

  if (loading) return <div className="page"><div className="loading">Loading results…</div></div>;

  const contextLabel = homeServerId && hostServerId
    ? `Home #${homeServerId} × Host #${hostServerId}`
    : 'All combinations';

  const runStart = run ? new Date(run.startedAt) : new Date();

  return (
    <div className="page">
      <div className="page-header">
        <div>
          <Link to="/results" className="breadcrumb">Results Matrix</Link>
          <span className="breadcrumb-sep">/</span>
          <h1 className="page-title inline">Run #{runId} — {contextLabel}</h1>
        </div>
        <div style={{ display: 'flex', gap: '8px' }}>
          <button className="btn btn-secondary btn-sm"
            onClick={() => setShowTimeline(v => !v)}>
            {showTimeline ? 'Hide Timeline' : 'Show Timeline'}
          </button>
          <button className="btn btn-secondary btn-sm"
            onClick={() => exportCSV(results, runId)}
            disabled={results.length === 0}>
            Export CSV
          </button>
        </div>
      </div>

      {error && <div className="alert alert-error">{error}</div>}

      {run && (
        <div className="card mb-4">
          <div className="card-body">
            <div className="run-meta">
              <div className="run-meta-item">
                <span className="run-meta-label">Status</span>
                <span>{statusLabel(run.status)}</span>
              </div>
              <div className="run-meta-item">
                <span className="run-meta-label">Started</span>
                <span>{new Date(run.startedAt).toLocaleString()}</span>
              </div>
              {run.completedAt && (
                <div className="run-meta-item">
                  <span className="run-meta-label">Completed</span>
                  <span>{new Date(run.completedAt).toLocaleString()}</span>
                </div>
              )}
              <div className="run-meta-item">
                <span className="run-meta-label">By</span>
                <span>{run.startedBy}</span>
              </div>
            </div>
            {run.statusMessage && (
              <div className="card-body" style={{ padding: '12px 0 0', borderTop: '1px solid #f1f5f9' }}>
                <span style={{ fontSize: '0.78rem', color: '#64748b' }}>{run.statusMessage}</span>
              </div>
            )}
          </div>
        </div>
      )}

      {showTimeline && <Timeline results={results} runStart={runStart} />}

      <div className="detail-summary mb-4">
        <div className="summary-item summary-total">
          <span className="summary-count">{results.length}</span>
          <span className="summary-label">Total</span>
        </div>
        <div className="summary-item summary-pass">
          <span className="summary-count">{successCount}</span>
          <span className="summary-label">Success</span>
        </div>
        <div className="summary-item summary-fail">
          <span className="summary-count">{deniedCount}</span>
          <span className="summary-label">Denied</span>
        </div>
        <div className="summary-item summary-skip">
          <span className="summary-count">{errorCount}</span>
          <span className="summary-label">Error</span>
        </div>
        <div className="summary-item">
          <span className="summary-count">{skippedCount}</span>
          <span className="summary-label">Skipped</span>
        </div>
      </div>

      <div className="filter-bar mb-4">
        <label className="filter-label">Actual:</label>
        <select className="form-input filter-select" value={filterActual}
          onChange={e => setFilterActual(e.target.value)}>
          <option value="ALL">All</option>
          <option value="SUCCESS">SUCCESS</option>
          <option value="DENIED">DENIED</option>
          <option value="ERROR">ERROR</option>
          <option value="SKIPPED">SKIPPED</option>
        </select>
        <span className="filter-count">{filtered.length} shown</span>
      </div>

      <div className="card">
        <div className="card-body p-0">
          <table className="table table-detail">
            <thead>
              <tr>
                <th>Test User</th>
                <th>Home Server</th>
                <th>Offering</th>
                <th>Host Server</th>
                <th>Result</th>
                <th>Duration</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              {filtered.length === 0 && (
                <tr><td colSpan={7} className="empty-cell">No results match the current filter.</td></tr>
              )}
              {filtered.map(r => <ResultRow key={r.id} result={r} />)}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
}
