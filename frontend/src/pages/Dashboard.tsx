import { useCallback, useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { ConfigStatus, ConnectivityResult, TestRun, extractErrorMessage, testRunsApi } from '../api/client';
import { useAuth } from '../contexts/AuthContext';

function statusClass(status: TestRun['status']): string {
  switch (status) {
    case 'COMPLETED': return 'badge badge-success';
    case 'RUNNING':   return 'badge badge-info';
    case 'PENDING':   return 'badge badge-warning';
    case 'FAILED':    return 'badge badge-danger';
    default:          return 'badge badge-secondary';
  }
}

function formatDate(iso: string | null): string {
  if (!iso) return '—';
  return new Date(iso).toLocaleString();
}

function duration(start: string, end: string | null): string {
  if (!end) return '—';
  const ms = new Date(end).getTime() - new Date(start).getTime();
  const s = Math.floor(ms / 1000);
  if (s < 60) return `${s}s`;
  return `${Math.floor(s / 60)}m ${s % 60}s`;
}

function ConnectivityPanel({ results, loading, onRefresh }: {
  results: ConnectivityResult[];
  loading: boolean;
  onRefresh: () => void;
}) {
  const typeLabel: Record<string, string> = {
    HOME_SERVER: 'Home Server',
    HOST_SERVER: 'Host Server',
    MOCK_OAUTH:  'Mock OAuth',
  };
  return (
    <div className="card">
      <div className="card-header">
        <h2 className="card-title">Server Connectivity</h2>
        <button className="btn btn-secondary btn-sm" onClick={onRefresh} disabled={loading}>
          {loading ? 'Checking…' : 'Re-check'}
        </button>
      </div>
      <div className="card-body p-0">
        {loading && results.length === 0 ? (
          <div className="loading-row">Checking…</div>
        ) : (
          <table className="table table-sm">
            <thead>
              <tr>
                <th>Server</th>
                <th>Type</th>
                <th>Status</th>
                <th>Response</th>
              </tr>
            </thead>
            <tbody>
              {results.map((r, i) => (
                <tr key={i}>
                  <td style={{ fontSize: '0.82rem' }}>
                    <span style={{ fontWeight: 600 }}>{r.name}</span>
                    <span style={{ fontSize: '0.72rem', color: '#94a3b8', display: 'block',
                                   fontFamily: 'monospace', wordBreak: 'break-all' }}>{r.url}</span>
                  </td>
                  <td style={{ fontSize: '0.78rem', color: '#64748b' }}>
                    {typeLabel[r.type] ?? r.type}
                  </td>
                  <td>
                    {r.reachable
                      ? <span className="badge badge-success">Online</span>
                      : <span className="badge badge-danger">Offline</span>}
                  </td>
                  <td style={{ fontSize: '0.76rem', color: '#64748b' }}>
                    {r.reachable ? `${r.durationMs}ms` : r.error ?? '—'}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
}

function ConfigStatusPanel({ config }: { config: ConfigStatus }) {
  const rows = [
    { label: 'Home servers', count: config.homeServerCount, link: '/home-servers', hint: 'Add a home server' },
    { label: 'Test users', count: config.testUserCount, link: '/home-servers', hint: 'Add test users inside a home server' },
    { label: 'Host servers', count: config.hostServerCount, link: '/host-servers', hint: 'Add a host server' },
    { label: 'Offerings', count: config.offeringCount, link: '/host-servers', hint: 'Add offerings inside a host server' },
  ];

  return (
    <div className={`card ${config.ready ? '' : 'card-warning'}`}>
      <div className="card-header">
        <h2 className="card-title">Configuration Status</h2>
        {config.ready
          ? <span className="badge badge-success">Ready — {config.totalTestCases} test case(s)</span>
          : <span className="badge badge-warning">Not ready</span>}
      </div>
      <div className="card-body">
        <table className="table table-sm">
          <tbody>
            {rows.map(row => (
              <tr key={row.label}>
                <td>{row.label}</td>
                <td>
                  {row.count > 0
                    ? <span className="badge badge-success">{row.count}</span>
                    : <span className="badge badge-danger">0</span>}
                </td>
                <td>
                  {row.count === 0 && (
                    <Link to={row.link} className="text-muted" style={{ fontSize: '0.8rem' }}>
                      → {row.hint}
                    </Link>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        {config.issues.length > 0 && (
          <ul className="issue-list">
            {config.issues.map((issue, i) => (
              <li key={i} className="issue-item">{issue}</li>
            ))}
          </ul>
        )}
      </div>
    </div>
  );
}

export function Dashboard() {
  const { user } = useAuth();
  const isAdmin = user?.role === 'ADMIN';

  const [runs, setRuns] = useState<TestRun[]>([]);
  const [config, setConfig] = useState<ConfigStatus | null>(null);
  const [connectivity, setConnectivity] = useState<ConnectivityResult[]>([]);
  const [loadingConnectivity, setLoadingConnectivity] = useState(false);
  const [loadingRuns, setLoadingRuns] = useState(true);
  const [starting, setStarting] = useState(false);
  const [error, setError] = useState('');
  const pollRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const fetchRuns = useCallback(async () => {
    try {
      const res = await testRunsApi.list();
      setRuns(res.data);
    } catch (err) {
      setError(extractErrorMessage(err));
    } finally {
      setLoadingRuns(false);
    }
  }, []);

  const fetchConfig = useCallback(async () => {
    try {
      const res = await testRunsApi.configStatus();
      setConfig(res.data);
    } catch {
      // non-critical, ignore
    }
  }, []);

  const fetchConnectivity = useCallback(async () => {
    setLoadingConnectivity(true);
    try {
      const res = await testRunsApi.connectivity();
      setConnectivity(res.data);
    } catch {
      // non-critical, ignore
    } finally {
      setLoadingConnectivity(false);
    }
  }, []);

  useEffect(() => {
    fetchRuns();
    if (isAdmin) { fetchConfig(); fetchConnectivity(); }
  }, [fetchRuns, fetchConfig, fetchConnectivity, isAdmin]);

  useEffect(() => {
    const hasActive = runs.some(r => r.status === 'RUNNING' || r.status === 'PENDING');
    if (hasActive && !pollRef.current) {
      pollRef.current = setInterval(fetchRuns, 5000);
    } else if (!hasActive && pollRef.current) {
      clearInterval(pollRef.current);
      pollRef.current = null;
    }
    return () => {
      if (pollRef.current) { clearInterval(pollRef.current); pollRef.current = null; }
    };
  }, [runs, fetchRuns]);

  const latestRun = runs[0] ?? null;
  const isActive = latestRun?.status === 'RUNNING' || latestRun?.status === 'PENDING';

  async function startRun() {
    setError('');
    setStarting(true);
    try {
      await testRunsApi.start();
      await Promise.all([fetchRuns(), isAdmin ? fetchConfig() : Promise.resolve()]);
    } catch (err) {
      setError(extractErrorMessage(err));
    } finally {
      setStarting(false);
    }
  }

  return (
    <div className="page">
      <div className="page-header">
        <h1 className="page-title">Dashboard</h1>
      </div>

      {error && <div className="alert alert-error">{error}</div>}

      {isAdmin && (
        <div className="dashboard-grid">
          {/* Run Tests card */}
          <div className="card">
            <div className="card-header">
              <h2 className="card-title">Test Execution</h2>
            </div>
            <div className="card-body">
              {latestRun && (
                <div className="latest-run-info">
                  <div className="latest-run-row">
                    <span className="latest-run-label">Latest run:</span>
                    <span className={statusClass(latestRun.status)}>{latestRun.status}</span>
                  </div>
                  <div className="latest-run-row">
                    <span className="latest-run-label">Started:</span>
                    <span>{formatDate(latestRun.startedAt)}</span>
                  </div>
                  <div className="latest-run-row">
                    <span className="latest-run-label">By:</span>
                    <span>{latestRun.startedBy}</span>
                  </div>
                  <div className="latest-run-row">
                    <span className="latest-run-label">Test cases:</span>
                    <span>{latestRun.totalResults}</span>
                  </div>
                  {latestRun.statusMessage && (
                    <div className={`run-status-message ${latestRun.totalResults === 0 ? 'run-status-message--warn' : 'run-status-message--info'}`}>
                      {latestRun.statusMessage}
                    </div>
                  )}
                  {(latestRun.status === 'COMPLETED' || latestRun.status === 'FAILED') && latestRun.totalResults > 0 && (
                    <Link to="/results" className="btn btn-secondary btn-sm mt-2">
                      View Results Matrix
                    </Link>
                  )}
                </div>
              )}
              {!latestRun && !loadingRuns && (
                <p className="text-muted">No test runs yet.</p>
              )}
              <button
                className="btn btn-primary mt-2"
                onClick={startRun}
                disabled={starting || isActive || (config !== null && !config.ready)}
                title={config && !config.ready ? 'Fix configuration issues before running' : undefined}
              >
                {starting ? 'Starting…' : isActive ? 'Running…' : 'Run Tests'}
              </button>
              {config && !config.ready && (
                <p className="text-muted" style={{ fontSize: '0.8rem', marginTop: '0.5rem' }}>
                  Fix configuration issues below before running.
                </p>
              )}
            </div>
          </div>

          {/* Quick links */}
          <div className="card">
            <div className="card-header">
              <h2 className="card-title">Quick Links</h2>
            </div>
            <div className="card-body quick-links">
              <Link to="/users" className="quick-link">Manage Users</Link>
              <Link to="/home-servers" className="quick-link">Home Servers</Link>
              <Link to="/host-servers" className="quick-link">Host Servers</Link>
              <Link to="/results" className="quick-link">Results Matrix</Link>
            </div>
          </div>

          {/* Config status — full width */}
          {config && (
            <div style={{ gridColumn: '1 / -1' }}>
              <ConfigStatusPanel config={config} />
            </div>
          )}

          {/* Connectivity panel — full width */}
          <div style={{ gridColumn: '1 / -1' }}>
            <ConnectivityPanel
              results={connectivity}
              loading={loadingConnectivity}
              onRefresh={fetchConnectivity}
            />
          </div>
        </div>
      )}

      {!isAdmin && (
        <div className="card">
          <div className="card-header">
            <h2 className="card-title">Quick Links</h2>
          </div>
          <div className="card-body quick-links">
            <Link to="/home-servers" className="quick-link">My Home Servers</Link>
            <Link to="/host-servers" className="quick-link">Host Servers</Link>
          </div>
        </div>
      )}

      {/* Recent test runs */}
      <div className="card mt-4">
        <div className="card-header">
          <h2 className="card-title">Recent Test Runs</h2>
        </div>
        <div className="card-body p-0">
          {loadingRuns ? (
            <div className="loading-row">Loading…</div>
          ) : runs.length === 0 ? (
            <div className="empty-row">No test runs yet.</div>
          ) : (
            <table className="table">
              <thead>
                <tr>
                  <th>ID</th>
                  <th>Status</th>
                  <th>Started</th>
                  <th>Duration</th>
                  <th>By</th>
                  <th>Cases</th>
                  <th>Message</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                {runs.slice(0, 10).map(run => (
                  <tr key={run.id}>
                    <td>#{run.id}</td>
                    <td><span className={statusClass(run.status)}>{run.status}</span></td>
                    <td>{formatDate(run.startedAt)}</td>
                    <td>{duration(run.startedAt, run.completedAt)}</td>
                    <td>{run.startedBy}</td>
                    <td>{run.totalResults}</td>
                    <td style={{ fontSize: '0.8rem', color: run.totalResults === 0 ? '#b45309' : '#374151', maxWidth: '240px' }}>
                      {run.statusMessage ?? '—'}
                    </td>
                    <td>
                      {run.totalResults > 0 && (
                        <Link to={`/results/${run.id}/detail`} className="btn btn-secondary btn-xs">
                          Details
                        </Link>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      </div>
    </div>
  );
}
