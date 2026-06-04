import { useCallback, useEffect, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import {
  MatrixCell,
  MatrixResponse,
  TestRun,
  extractErrorMessage,
  testRunsApi,
} from '../api/client';

function cellColor(cell: MatrixCell): string {
  switch (cell.status) {
    case 'success':
      return '#16a34a';
    case 'partial':
      if (cell.successRate >= 0.75) return '#ca8a04';
      if (cell.successRate >= 0.5) return '#ea580c';
      return '#dc2626';
    case 'failed':
      return '#dc2626';
    case 'error':
      return '#7c3aed';
    case 'pending':
    default:
      return '#9ca3af';
  }
}

function cellTextColor(cell: MatrixCell): string {
  return cell.status === 'pending' ? '#374151' : '#ffffff';
}

function MatrixCellEl({
  cell,
  testRunId,
}: {
  cell: MatrixCell;
  testRunId: number;
}) {
  const navigate = useNavigate();
  const pct =
    cell.totalTests > 0
      ? `${Math.round(cell.successRate * 100)}%`
      : '—';

  return (
    <div
      className="matrix-cell"
      style={{
        backgroundColor: cellColor(cell),
        color: cellTextColor(cell),
        cursor: cell.totalTests > 0 ? 'pointer' : 'default',
      }}
      title={`Success: ${cell.successCount}, Denied: ${cell.deniedCount}, Error: ${cell.errorCount}, Skipped: ${cell.skippedCount}`}
      onClick={() => {
        if (cell.totalTests === 0) return;
        navigate(
          `/results/${testRunId}/detail?homeServerId=${cell.homeServerId}&hostServerId=${cell.hostServerId}`
        );
      }}
    >
      <div className="matrix-cell-pct">{pct}</div>
      <div className="matrix-cell-counts">
        {cell.totalTests > 0 && `${cell.successCount}/${cell.totalTests}`}
      </div>
    </div>
  );
}

function statusBadgeClass(status: TestRun['status']): string {
  switch (status) {
    case 'COMPLETED':
      return 'badge badge-success';
    case 'RUNNING':
      return 'badge badge-info';
    case 'PENDING':
      return 'badge badge-warning';
    case 'FAILED':
      return 'badge badge-danger';
  }
}

export function ResultsMatrix() {
  const [latestRun, setLatestRun] = useState<TestRun | null>(null);
  const [matrix, setMatrix] = useState<MatrixResponse | null>(null);
  const [loadingRun, setLoadingRun] = useState(true);
  const [loadingMatrix, setLoadingMatrix] = useState(false);
  const [error, setError] = useState('');
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const fetchLatest = useCallback(async () => {
    try {
      const res = await testRunsApi.latest();
      setLatestRun(res.data);
      return res.data;
    } catch (err: unknown) {
      if (
        typeof err === 'object' &&
        err !== null &&
        'response' in err &&
        (err as { response?: { status?: number } }).response?.status === 404
      ) {
        setLatestRun(null);
      } else {
        setError(extractErrorMessage(err));
      }
      return null;
    } finally {
      setLoadingRun(false);
    }
  }, []);

  const fetchMatrix = useCallback(async (runId: number) => {
    setLoadingMatrix(true);
    try {
      const res = await testRunsApi.matrix(runId);
      setMatrix(res.data);
    } catch (err) {
      setError(extractErrorMessage(err));
    } finally {
      setLoadingMatrix(false);
    }
  }, []);

  // Initial load
  useEffect(() => {
    fetchLatest().then((run) => {
      if (run) fetchMatrix(run.id);
    });
  }, [fetchLatest, fetchMatrix]);

  // Poll while running
  useEffect(() => {
    const isActive =
      latestRun?.status === 'RUNNING' || latestRun?.status === 'PENDING';
    if (isActive && !pollRef.current) {
      pollRef.current = setInterval(async () => {
        const run = await fetchLatest();
        if (run) fetchMatrix(run.id);
      }, 5000);
    } else if (!isActive && pollRef.current) {
      clearInterval(pollRef.current);
      pollRef.current = null;
    }
    return () => {
      if (pollRef.current) {
        clearInterval(pollRef.current);
        pollRef.current = null;
      }
    };
  }, [latestRun, fetchLatest, fetchMatrix]);

  if (loadingRun) {
    return (
      <div className="page">
        <div className="loading">Loading results…</div>
      </div>
    );
  }

  if (!latestRun) {
    return (
      <div className="page">
        <div className="page-header">
          <h1 className="page-title">Results Matrix</h1>
        </div>
        <div className="card">
          <div className="card-body">
            <p className="text-muted">No test runs found. Start a test run from the dashboard.</p>
            <Link to="/dashboard" className="btn btn-primary mt-2">
              Go to Dashboard
            </Link>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="page">
      <div className="page-header">
        <h1 className="page-title">Results Matrix</h1>
        <Link
          to={`/results/${latestRun.id}/detail`}
          className="btn btn-secondary"
        >
          Full Detail View
        </Link>
      </div>

      {error && <div className="alert alert-error">{error}</div>}

      {/* Test run metadata */}
      <div className="card mb-4">
        <div className="card-body">
          <div className="run-meta">
            <div className="run-meta-item">
              <span className="run-meta-label">Run ID</span>
              <span>#{latestRun.id}</span>
            </div>
            <div className="run-meta-item">
              <span className="run-meta-label">Status</span>
              <span className={statusBadgeClass(latestRun.status)}>
                {latestRun.status}
                {(latestRun.status === 'RUNNING' ||
                  latestRun.status === 'PENDING') && (
                  <span className="spinner" />
                )}
              </span>
            </div>
            <div className="run-meta-item">
              <span className="run-meta-label">Started</span>
              <span>{new Date(latestRun.startedAt).toLocaleString()}</span>
            </div>
            {latestRun.completedAt && (
              <div className="run-meta-item">
                <span className="run-meta-label">Completed</span>
                <span>
                  {new Date(latestRun.completedAt).toLocaleString()}
                </span>
              </div>
            )}
            <div className="run-meta-item">
              <span className="run-meta-label">By</span>
              <span>{latestRun.startedBy}</span>
            </div>
            <div className="run-meta-item">
              <span className="run-meta-label">Total Results</span>
              <span>{latestRun.totalResults}</span>
            </div>
          </div>
        </div>
      </div>

      {/* Legend */}
      <div className="matrix-legend">
        <div className="legend-item">
          <div className="legend-swatch" style={{ background: '#16a34a' }} />
          <span>All success</span>
        </div>
        <div className="legend-item">
          <div className="legend-swatch" style={{ background: '#ca8a04' }} />
          <span>Mostly success</span>
        </div>
        <div className="legend-item">
          <div className="legend-swatch" style={{ background: '#ea580c' }} />
          <span>Partial</span>
        </div>
        <div className="legend-item">
          <div className="legend-swatch" style={{ background: '#dc2626' }} />
          <span>Failed</span>
        </div>
        <div className="legend-item">
          <div className="legend-swatch" style={{ background: '#7c3aed' }} />
          <span>Errors</span>
        </div>
        <div className="legend-item">
          <div className="legend-swatch" style={{ background: '#9ca3af' }} />
          <span>Pending / No data</span>
        </div>
      </div>

      {/* Matrix */}
      {loadingMatrix ? (
        <div className="loading">Loading matrix…</div>
      ) : matrix && matrix.homeServers.length > 0 ? (
        <div className="matrix-scroll">
          <div
            className="matrix-grid"
            style={{
              gridTemplateColumns: `180px repeat(${matrix.hostServers.length}, 90px)`,
            }}
          >
            {/* Corner */}
            <div className="matrix-corner">Home \ Host</div>

            {/* Host server headers */}
            {matrix.hostServers.map((host) => (
              <div key={host.id} className="matrix-col-header">
                <Link
                  to={`/host-servers/${host.id}`}
                  className="matrix-server-link"
                >
                  {host.name}
                </Link>
              </div>
            ))}

            {/* Rows */}
            {matrix.homeServers.map((home) => (
              <>
                <div key={`row-${home.id}`} className="matrix-row-header">
                  <Link
                    to={`/home-servers/${home.id}`}
                    className="matrix-server-link"
                  >
                    {home.name}
                  </Link>
                </div>
                {matrix.hostServers.map((host) => {
                  const cell = matrix.cells.find(
                    (c) =>
                      c.homeServerId === home.id &&
                      c.hostServerId === host.id
                  );
                  if (!cell) {
                    return (
                      <div
                        key={`empty-${home.id}-${host.id}`}
                        className="matrix-cell"
                        style={{ backgroundColor: '#e5e7eb', color: '#9ca3af' }}
                      >
                        <div className="matrix-cell-pct">—</div>
                      </div>
                    );
                  }
                  return (
                    <MatrixCellEl
                      key={`${home.id}-${host.id}`}
                      cell={cell}
                      testRunId={latestRun.id}
                    />
                  );
                })}
              </>
            ))}
          </div>
        </div>
      ) : (
        <div className="card">
          <div className="card-body">
            <p className="text-muted">No results available for this run yet.</p>
          </div>
        </div>
      )}
    </div>
  );
}
