import { useEffect, useState } from 'react';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import {
  ActualResult,
  TestResultDto,
  TestRun,
  extractErrorMessage,
  testRunsApi,
} from '../api/client';
import { StepDetails } from '../components/StepDetails';

function resultBadgeClass(result: ActualResult): string {
  switch (result) {
    case 'SUCCESS': return 'badge badge-success';
    case 'DENIED':  return 'badge badge-warning';
    case 'ERROR':   return 'badge badge-danger';
    case 'SKIPPED': return 'badge badge-secondary';
    default:        return 'badge badge-secondary';
  }
}

function formatDuration(start: string, end: string | null): string {
  if (!end) return '—';
  const ms = new Date(end).getTime() - new Date(start).getTime();
  if (ms < 1000) return `${ms}ms`;
  return `${(ms / 1000).toFixed(1)}s`;
}

function ResultRow({ result }: { result: TestResultDto }) {
  const [expanded, setExpanded] = useState(false);

  return (
    <>
      <tr>
        <td>{result.testUserName}</td>
        <td>{result.homeServerName}</td>
        <td>{result.offeringName}</td>
        <td>{result.hostServerName}</td>
        <td>
          <span className={resultBadgeClass(result.actualResult)}>
            {result.actualResult}
          </span>
        </td>
        <td>{formatDuration(result.startedAt, result.completedAt)}</td>
        <td className="actions-cell">
          {result.stepDetails && (
            <button
              className="btn btn-secondary btn-xs"
              onClick={() => setExpanded((v) => !v)}
            >
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

  const filtered = results.filter((r) => filterActual === 'ALL' || r.actualResult === filterActual);

  const successCount = results.filter((r) => r.actualResult === 'SUCCESS').length;
  const deniedCount  = results.filter((r) => r.actualResult === 'DENIED').length;
  const errorCount   = results.filter((r) => r.actualResult === 'ERROR').length;
  const skippedCount = results.filter((r) => r.actualResult === 'SKIPPED').length;

  if (loading) return <div className="page"><div className="loading">Loading results…</div></div>;

  const contextLabel = homeServerId && hostServerId
    ? `Home #${homeServerId} × Host #${hostServerId}`
    : 'All combinations';

  return (
    <div className="page">
      <div className="page-header">
        <div>
          <Link to="/results" className="breadcrumb">Results Matrix</Link>
          <span className="breadcrumb-sep">/</span>
          <h1 className="page-title inline">Run #{runId} — {contextLabel}</h1>
        </div>
      </div>

      {error && <div className="alert alert-error">{error}</div>}

      {run && (
        <div className="card mb-4">
          <div className="card-body">
            <div className="run-meta">
              <div className="run-meta-item">
                <span className="run-meta-label">Status</span>
                <span>{run.status}</span>
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
          </div>
        </div>
      )}

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
        <select
          className="form-input filter-select"
          value={filterActual}
          onChange={(e) => setFilterActual(e.target.value)}
        >
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
                <tr>
                  <td colSpan={7} className="empty-cell">No results match the current filter.</td>
                </tr>
              )}
              {filtered.map((r) => <ResultRow key={r.id} result={r} />)}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
}
