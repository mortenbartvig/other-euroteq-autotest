import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  simulationApi,
  testRunsApi,
  extractErrorMessage,
  type SimulationConfig,
  type SimulationInstitution,
} from '../api/client';
import { Modal } from '../components/Modal';
import { ConfirmDialog } from '../components/ConfirmDialog';
import { useToast } from '../components/Toast';

export function SimulationPage() {
  const navigate = useNavigate();
  const { addToast } = useToast();
  const [config, setConfig] = useState<SimulationConfig | null>(null);
  const [simulatedRuns, setSimulatedRuns] = useState<any[]>([]);
  const [running, setRunning] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [editInst, setEditInst] = useState<SimulationInstitution | null>(null);
  const [newInstName, setNewInstName] = useState('');
  const [confirmDelete, setConfirmDelete] = useState<SimulationInstitution | null>(null);
  const [runningRunId, setRunningRunId] = useState<number | null>(null);
  const [pollInterval, setPollInterval] = useState<ReturnType<typeof setInterval> | null>(null);

  useEffect(() => {
    loadConfig();
    loadSimulatedRuns();
  }, []);

  useEffect(() => {
    if (runningRunId) {
      const interval = setInterval(async () => {
        try {
          const runResp = await testRunsApi.get(runningRunId);
          if (runResp.data.status === 'COMPLETED' || runResp.data.status === 'FAILED') {
            setRunning(false);
            setRunningRunId(null);
            if (pollInterval) clearInterval(pollInterval);
            navigate(`/results/${runningRunId}/detail`);
          }
        } catch {
          // ignore
        }
      }, 2000);
      setPollInterval(interval);
      return () => clearInterval(interval);
    }
  }, [runningRunId, navigate]);

  async function loadConfig() {
    try {
      const resp = await simulationApi.getConfig();
      setConfig(resp.data);
    } catch {
      setConfig(null);
    }
  }

  async function loadSimulatedRuns() {
    try {
      const resp = await testRunsApi.list();
      setSimulatedRuns(resp.data.filter((r: any) => r.simulated));
    } catch {
      setSimulatedRuns([]);
    }
  }

  async function handleSave() {
    if (!config) return;
    try {
      await simulationApi.saveConfig(config);
      addToast('Configuration saved', 'success');
    } catch (err: any) {
      addToast(extractErrorMessage(err), 'error');
    }
  }

  async function handleRun() {
    try {
      await handleSave();
      setRunning(true);
      await simulationApi.run();
     // Poll for the latest run
      const latest = await testRunsApi.latest();
      if (latest.data.simulated) {
        setRunningRunId(latest.data.id);
        navigate('/results');
      } else {
        setRunning(false);
      }
    } catch (err: any) {
      setRunning(false);
      addToast(extractErrorMessage(err), 'error');
    }
  }

  function updateGlobal<K extends keyof Omit<SimulationConfig, 'institutions' | 'id'>>(
    key: K,
    value: SimulationConfig[K]
  ) {
    if (!config) return;
    setConfig({ ...config, [key]: value });
  }

  function updateInstitution(idx: number, updates: Partial<SimulationInstitution>) {
    if (!config) return;
    const updated = [...config.institutions];
    updated[idx] = { ...updated[idx], ...updates };
    setConfig({ ...config, institutions: updated });
  }

  function openAddInstitution() {
    setEditInst(null);
    setNewInstName('');
    setModalOpen(true);
  }

  function openEditInstitution(idx: number) {
    setEditInst(config?.institutions[idx] || null);
    setNewInstName(config?.institutions[idx]?.name || '');
    setModalOpen(true);
  }

  async function handleSaveInstitution() {
    if (!config || !newInstName.trim()) return;
    if (editInst) {
      // Update existing
      const idx = config.institutions.findIndex(i => i.id === editInst.id);
      if (idx >= 0) {
        updateInstitution(idx, { name: newInstName.trim() });
      }
    } else {
      // Add new
      setConfig({
        ...config,
  institutions: [...config.institutions, { name: newInstName.trim(), homeServerOffline: false, hostServerOffline: false, useGlobalPassRate: true }],
      });
    }
    setModalOpen(false);
    await handleSave();
  }

  async function handleDeleteInstitution(idx: number) {
    if (!config) return;
    const updated = [...config.institutions];
    updated.splice(idx, 1);
    setConfig({ ...config, institutions: updated });
    await handleSave();
  }

  function collapseAll() {
    // Toggle visibility of all institution content panels
    document.querySelectorAll('.institution-content').forEach((el: Element) => {
      (el as HTMLElement).style.display = 'none';
    });
  }

  return (
    <div className="simulation-page">
      <div className="simulation-banner">
        <div className="simulation-banner-content">
          <span className="simulation-banner-icon">⚡</span>
          <span>Simulated Test Run</span>
        </div>
      </div>

      {/* Global Settings */}
      <div className="card mt-4">
        <div className="card-header">
          <h3 className="card-title">Test Parameters</h3>
        </div>
        <div className="card-body">
          <div className="form-row">
            <div className="form-group">
              <label className="form-label">Test users per institution</label>
              <input
                type="number"
                className="form-input"
                value={config?.globalUsersPerInst || 5}
                onChange={e => updateGlobal('globalUsersPerInst', Number(e.target.value))}
                min={1}
              />
            </div>
            <div className="form-group">
              <label className="form-label">Offerings per institution</label>
              <input
                type="number"
                className="form-input"
                value={config?.globalOfferingsPerInst || 3}
                onChange={e => updateGlobal('globalOfferingsPerInst', Number(e.target.value))}
                min={1}
              />
            </div>
            <div className="form-group">
              <label className="form-label">Pass rate (%)</label>
              <input
                type="number"
                className="form-input"
                value={config?.globalPassRate || 80}
                onChange={e => updateGlobal('globalPassRate', Number(e.target.value))}
                min={0}
                max={100}
              />
            </div>
          </div>

          <div className="form-row mt-4">
            <div className="form-group">
              <label className="form-label">Slow test mode</label>
              <div className="radio-group">
                <label className="checkbox-label">
                  <input
                    type="radio"
                    checked={config?.slowAsPercent === true}
                    onChange={() => updateGlobal('slowAsPercent', true)}
                  />
                  <span>% of tests</span>
                </label>
                <label className="checkbox-label">
                  <input
                    type="radio"
                    checked={config?.slowAsPercent === false}
                    onChange={() => updateGlobal('slowAsPercent', false)}
                  />
                  <span>Specific number of tests</span>
                </label>
              </div>
            </div>

            {config?.slowAsPercent && (
              <div className="form-group">
                <label className="form-label">Slow test percentage (%)</label>
                <input
                  type="number"
                  className="form-input"
                  value={config.slowPercent}
                  onChange={e => updateGlobal('slowPercent', Number(e.target.value))}
                  min={0}
                  max={100}
                  step={0.1}
                />
              </div>
            )}

            {!config?.slowAsPercent && (
              <div className="form-group">
                <label className="form-label">Number of slow tests</label>
                <input
                  type="number"
                  className="form-input"
                  value={config?.slowCount || 5}
                  onChange={e => updateGlobal('slowCount', Number(e.target.value))}
                  min={0}
                />
              </div>
            )}
          </div>

          <div className="form-row mt-4">
            <div className="form-group">
              <label className="form-label">Normal duration range (seconds)</label>
              <div className="inline">
                <input
                  type="number"
                  className="form-input form-input-sm"
                  value={config?.normalDurationMin || 1}
                  onChange={e => updateGlobal('normalDurationMin', Number(e.target.value))}
                  min={0.1}
                  step={0.1}
                />
                <span className="mx-2">to</span>
                <input
                  type="number"
                  className="form-input form-input-sm"
                  value={config?.normalDurationMax || 2}
                  onChange={e => updateGlobal('normalDurationMax', Number(e.target.value))}
                  min={0.1}
                  step={0.1}
                />
              </div>
            </div>
            <div className="form-group">
              <label className="form-label">Slow duration range (seconds)</label>
              <div className="inline">
                <input
                  type="number"
                  className="form-input form-input-sm"
                  value={config?.slowDurationMin || 15}
                  onChange={e => updateGlobal('slowDurationMin', Number(e.target.value))}
                  min={1}
                  step={1}
                />
                <span className="mx-2">to</span>
                <input
                  type="number"
                  className="form-input form-input-sm"
                  value={config?.slowDurationMax || 25}
                  onChange={e => updateGlobal('slowDurationMax', Number(e.target.value))}
                  min={1}
                  step={1}
                />
              </div>
            </div>
          </div>
        </div>
      </div>

      {/* Institutions */}
      <div className="card mt-4">
        <div className="card-header">
          <h3 className="card-title">Institutions</h3>
          <div className="card-actions">
            <button className="btn btn-sm btn-secondary" onClick={collapseAll}>
              Collapse All
            </button>
            <button className="btn btn-sm btn-secondary" onClick={openAddInstitution}>
              + Add Institution
            </button>
          </div>
        </div>
        <div className="card-body">
          {config?.institutions.map((inst, idx) => (
            <div key={inst.id || idx} className="institution-card">
              <div className="institution-header" onClick={() => {
                // Toggle expand - using data attribute
                const content = document.getElementById(`inst-content-${idx}`);
                if (content) {
                  content.style.display = content.style.display === 'none' ? 'block' : 'none';
                }
              }}>
                <span className="institution-name">{inst.name}</span>
                <span className="institution-arrow">▼</span>
              </div>
              <div id={`inst-content-${idx}`} className="institution-content">
                <div className="form-row">
                  <div className="form-group">
                    <label className="form-label">Users (override)</label>
                    <input
                      type="number"
                      className="form-input"
                      value={inst.usersOverride || ''}
                      onChange={e => updateInstitution(idx, { usersOverride: e.target.value ? Number(e.target.value) : null })}
                      placeholder="Use global"
                      min={1}
                    />
                  </div>
                  <div className="form-group">
                    <label className="form-label">Offerings (override)</label>
                    <input
                      type="number"
                      className="form-input"
                      value={inst.offeringsOverride || ''}
                      onChange={e => updateInstitution(idx, { offeringsOverride: e.target.value ? Number(e.target.value) : null })}
                      placeholder="Use global"
                      min={1}
                    />
                  </div>
<div className="form-group">
                    <label className="form-label">Pass rate %</label>
                    <div style={{ display: 'flex', gap: '8px', alignItems: 'center' }}>
                      <input
                        type="number"
                        className="form-input"
                        style={{ width: '80px' }}
                        value={inst.useGlobalPassRate ? (config?.globalPassRate ?? '') : (inst.passRateOverride ?? '')}
                        onChange={e => {
                          const val = e.target.value;
                          if (val === '' || val === '-') {
                            return;
                          }
                          const num = Number(val);
                          if (isNaN(num)) return;
                          updateInstitution(idx, { useGlobalPassRate: false, passRateOverride: num });
                        }}
                        min={0}
                        max={100}
                        disabled={inst.useGlobalPassRate}
                        title={inst.useGlobalPassRate ? `Uses global rate (${config?.globalPassRate}%)` : 'Custom pass rate'}
                      />
                      <label className="checkbox-label" style={{ cursor: 'pointer' }}>
                        <input
                          type="checkbox"
                          checked={inst.useGlobalPassRate}
                          onChange={e => {
                            if (e.target.checked) {
                              updateInstitution(idx, { useGlobalPassRate: true, passRateOverride: null });
                            } else {
                              updateInstitution(idx, { useGlobalPassRate: false, passRateOverride: config?.globalPassRate ?? 0 });
                            }
                          }}
                        />
                        <span>Use global</span>
                      </label>
                    </div>
                  </div>
                </div>
                <div className="form-row">
                  <label className="checkbox-label">
                    <input
                      type="checkbox"
                      checked={inst.homeServerOffline}
                      onChange={e => updateInstitution(idx, { homeServerOffline: e.target.checked })}
                    />
                    <span>Home server offline</span>
                  </label>
                  <label className="checkbox-label">
                    <input
                      type="checkbox"
                      checked={inst.hostServerOffline}
                      onChange={e => updateInstitution(idx, { hostServerOffline: e.target.checked })}
                    />
                    <span>Host server offline</span>
                  </label>
                </div>
                <div className="institution-actions">
                  <button className="btn btn-sm btn-secondary" onClick={() => openEditInstitution(idx)}>
                    Edit Name
                  </button>
                  <button className="btn btn-sm btn-danger" onClick={() => setConfirmDelete(inst)}>
                    Delete
                  </button>
                </div>
              </div>
            </div>
          ))}
        </div>
      </div>

    {/* Action buttons */}
      <div className="mt-4 mb-4" style={{ textAlign: 'center', display: 'flex', gap: '12px', justifyContent: 'center', flexWrap: 'wrap' }}>
        <button
          className="btn btn-secondary"
          onClick={handleSave}
          disabled={running || !config}
        >
          Save Configuration
        </button>
        <button
          className="btn btn-primary"
          style={{ fontSize: '1.1rem', padding: '0.75rem 2rem' }}
          onClick={handleRun}
          disabled={running}
        >
          {running ? 'Test Run In Progress...' : 'Simulate Test Run'}
        </button>
      </div>

      {/* Simulation Run History */}
      {simulatedRuns.length > 0 && (
        <div className="card mt-4">
          <div className="card-header">
            <h3 className="card-title">Simulation History</h3>
          </div>
          <div className="card-body">
            <table className="table">
              <thead>
                <tr>
                  <th>Run ID</th>
                  <th>Started</th>
                  <th>Status</th>
                  <th>Results</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                {simulatedRuns.slice(0, 10).map(run => (
                  <tr key={run.id}>
                    <td className="mono">#{run.id}</td>
                    <td>{new Date(run.startedAt).toLocaleString()}</td>
                    <td>
                      <span className={`badge ${run.status === 'COMPLETED' ? 'badge-success' : 'badge-info'}`}>
                        {run.status}
                      </span>
                    </td>
                    <td>{run.totalResults}</td>
                    <td>
                      <button
                        className="btn btn-sm btn-secondary"
                        onClick={() => navigate(`/results/${run.id}/detail`)}
                      >
                        View
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      {/* Add/Edit Institution Modal */}
      {modalOpen && (
        <Modal onClose={() => setModalOpen(false)} title={editInst ? 'Edit Institution' : 'Add Institution'}>
        <div className="form-group">
          <label className="form-label">Institution Name</label>
          <input
            className="form-input"
            value={newInstName}
            onChange={e => setNewInstName(e.target.value)}
            placeholder="e.g. CTU, DTU, TUM..."
            autoFocus
            onKeyDown={e => e.key === 'Enter' && handleSaveInstitution()}
          />
        </div>
        <div className="modal-actions">
          <button className="btn btn-secondary" onClick={() => setModalOpen(false)}>
            Cancel
          </button>
          <button className="btn btn-primary" onClick={handleSaveInstitution} disabled={!newInstName.trim()}>
            {editInst ? 'Save' : 'Add'}
          </button>
        </div>
        </Modal>
      )}

      {/* Confirm Delete */}
      {confirmDelete && (
        <ConfirmDialog
          message={`Are you sure you want to delete "${confirmDelete.name}"?`}
          onConfirm={() => {
            const idx = config?.institutions.findIndex(i => i.id === confirmDelete.id) || 0;
            handleDeleteInstitution(idx);
            setConfirmDelete(null);
          }}
          onCancel={() => setConfirmDelete(null)}
          danger
          confirmLabel="Delete"
        />
      )}
    </div>
  );
}
