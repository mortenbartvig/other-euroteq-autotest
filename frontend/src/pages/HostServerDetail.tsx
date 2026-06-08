import { FormEvent, useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import {
  AcademicLevel,
  HostServer,
  Offering,
  Result,
  extractErrorMessage,
  hostServersApi,
} from '../api/client';

import { ConfirmDialog } from '../components/ConfirmDialog';
import { Modal } from '../components/Modal';

// ---------------------------------------------------------------------------
// Result form modal
// ---------------------------------------------------------------------------

interface ResultFormData {
  name: string;
  state: string;
  pass: string;
  comment: string;
  score: string;
  resultDate: string;
  ext: string;
  studyLoad: string;
}

function ResultFormModal({
  initial,
  hostServerId,
  onSave,
  onClose,
}: {
  initial?: Result;
  hostServerId: number;
  onSave: () => void;
  onClose: () => void;
}) {
  const isEdit = !!initial;
  const [form, setForm] = useState<ResultFormData>({
    name: initial?.name ?? '',
    state: initial?.state ?? '',
    pass: initial?.pass ?? '',
    comment: initial?.comment ?? '',
    score: initial?.score ?? '',
    resultDate: initial?.resultDate ?? '',
    ext: initial?.ext ?? '',
    studyLoad: initial?.studyLoad ?? '',
  });
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  function validateJson(v: string): boolean {
    if (!v.trim()) return true;
    try { JSON.parse(v); return true; }
    catch { return false; }
  }

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    setError('');
    if (!validateJson(form.ext)) { setError('ext must be valid JSON or empty.'); return; }
    if (!validateJson(form.studyLoad)) { setError('studyLoad must be valid JSON or empty.'); return; }
    setLoading(true);
    try {
      const payload = {
        name: form.name,
        state: form.state.trim() || undefined,
        pass: form.pass.trim() || undefined,
        comment: form.comment.trim() || undefined,
        score: form.score.trim() || undefined,
        resultDate: form.resultDate.trim() || undefined,
        ext: form.ext.trim() || undefined,
        studyLoad: form.studyLoad.trim() || undefined,
      };
      if (isEdit && initial) {
        await hostServersApi.updateResult(hostServerId, initial.id, payload);
      } else {
        await hostServersApi.createResult(hostServerId, payload);
      }
      onSave();
      onClose();
    } catch (err) {
      setError(extractErrorMessage(err));
    } finally {
      setLoading(false);
    }
  }

  return (
    <Modal title={isEdit ? 'Edit Result' : 'Add Result'} onClose={onClose} size="lg">
      {error && <div className="alert alert-error">{error}</div>}
      <form onSubmit={handleSubmit}>
        <div className="form-group">
          <label className="form-label">Name *</label>
          <input
            className="form-input"
            value={form.name}
            onChange={(e) => setForm({ ...form, name: e.target.value })}
            required
            placeholder="e.g. Passed with grade 7"
          />
        </div>

        <div className="form-row">
          <div className="form-group">
            <label className="form-label">state</label>
            <input
              className="form-input"
              value={form.state}
              onChange={(e) => setForm({ ...form, state: e.target.value })}
              placeholder="passed"
            />
          </div>
          <div className="form-group">
            <label className="form-label">pass</label>
            <input
              className="form-input"
              value={form.pass}
              onChange={(e) => setForm({ ...form, pass: e.target.value })}
              placeholder="true"
            />
          </div>
        </div>

        <div className="form-row">
          <div className="form-group">
            <label className="form-label">score</label>
            <input
              className="form-input"
              value={form.score}
              onChange={(e) => setForm({ ...form, score: e.target.value })}
              placeholder="7.0"
            />
          </div>
          <div className="form-group">
            <label className="form-label">resultDate</label>
            <input
              className="form-input"
              value={form.resultDate}
              onChange={(e) => setForm({ ...form, resultDate: e.target.value })}
              placeholder="2024-06-01"
            />
          </div>
        </div>

        <div className="form-group">
          <label className="form-label">comment</label>
          <input
            className="form-input"
            value={form.comment}
            onChange={(e) => setForm({ ...form, comment: e.target.value })}
            placeholder="Optional comment"
          />
        </div>

        <div className="form-group">
          <label className="form-label">studyLoad (JSON)</label>
          <input
            className="form-input"
            value={form.studyLoad}
            onChange={(e) => setForm({ ...form, studyLoad: e.target.value })}
            placeholder='{"studyLoadUnit":"ects","value":5}'
          />
          <span className="form-hint">Optional. Must be valid JSON if provided.</span>
        </div>

        <div className="form-group">
          <label className="form-label">ext (JSON)</label>
          <textarea
            className="form-input form-textarea"
            value={form.ext}
            onChange={(e) => setForm({ ...form, ext: e.target.value })}
            rows={3}
            placeholder='{"customField": "value"}'
          />
          <span className="form-hint">Optional. Must be valid JSON if provided.</span>
        </div>

        <div className="form-actions">
          <button type="button" className="btn btn-secondary" onClick={onClose}>Cancel</button>
          <button type="submit" className="btn btn-primary" disabled={loading}>
            {loading ? 'Saving…' : isEdit ? 'Save Changes' : 'Add Result'}
          </button>
        </div>
      </form>
    </Modal>
  );
}

// ---------------------------------------------------------------------------
// Offering form modal
// ---------------------------------------------------------------------------

interface OfferingFormData {
  name: string;
  offeringId: string;
  offeringData: string;
  courseLevel: AcademicLevel | '';
}

function OfferingFormModal({
  initial,
  hostServerId,
  onSave,
  onClose,
}: {
  initial?: Offering;
  hostServerId: number;
  onSave: () => void;
  onClose: () => void;
}) {
  const isEdit = !!initial;
  const [form, setForm] = useState<OfferingFormData>({
    name: initial?.name ?? '',
    offeringId: initial?.offeringId ?? '',
    offeringData: initial?.offeringData ?? '',
    courseLevel: (initial?.courseLevel as AcademicLevel | null) ?? '',
  });
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  function validateJson(v: string): boolean {
    if (!v.trim()) return true;
    try { JSON.parse(v); return true; }
    catch { return false; }
  }

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    setError('');
    if (!validateJson(form.offeringData)) {
      setError('Offering Data must be valid JSON or empty.');
      return;
    }
    setLoading(true);
    try {
      const payload = {
        name: form.name,
        offeringId: form.offeringId,
        offeringData: form.offeringData.trim() || undefined,
        courseLevel: form.courseLevel || undefined,
      };
      if (isEdit && initial) {
        await hostServersApi.updateOffering(hostServerId, initial.id, payload);
      } else {
        await hostServersApi.createOffering(hostServerId, payload);
      }
      onSave();
      onClose();
    } catch (err) {
      setError(extractErrorMessage(err));
    } finally {
      setLoading(false);
    }
  }

  return (
    <Modal title={isEdit ? 'Edit Offering' : 'Add Offering'} onClose={onClose} size="lg">
      {error && <div className="alert alert-error">{error}</div>}
      <form onSubmit={handleSubmit}>
        <div className="form-row">
          <div className="form-group">
            <label className="form-label">Name *</label>
            <input
              className="form-input"
              value={form.name}
              onChange={(e) => setForm({ ...form, name: e.target.value })}
              required
            />
          </div>
          <div className="form-group">
            <label className="form-label">Offering ID *</label>
            <input
              className="form-input"
              value={form.offeringId}
              onChange={(e) => setForm({ ...form, offeringId: e.target.value })}
              required
            />
          </div>
        </div>

        <div className="form-group">
          <label className="form-label">Offering Data (JSON)</label>
          <textarea
            className="form-input form-textarea"
            value={form.offeringData}
            onChange={(e) => setForm({ ...form, offeringData: e.target.value })}
            rows={4}
            placeholder='{"courseId": "01234", "title": "Advanced Topics"}'
          />
          <span className="form-hint">Optional JSON payload sent as offering metadata.</span>
        </div>

        <div className="form-group">
          <label className="form-label">Course Level</label>
          <select
            className="form-input"
            value={form.courseLevel || ''}
            onChange={(e) =>
              setForm({ ...form, courseLevel: (e.target.value || '') as AcademicLevel | '' })
            }
          >
            <option value="">None (not set)</option>
            <option value="bachelor">Bachelor</option>
            <option value="master">Master</option>
            <option value="doctoral">Doctoral (PhD)</option>
          </select>
          <span className="form-hint">
            Minimum academic level required. The test verifies the user's level meets this requirement.
          </span>
        </div>

        <div className="form-actions">
          <button type="button" className="btn btn-secondary" onClick={onClose}>Cancel</button>
          <button type="submit" className="btn btn-primary" disabled={loading}>
            {loading ? 'Saving…' : isEdit ? 'Save Changes' : 'Add Offering'}
          </button>
        </div>
      </form>
    </Modal>
  );
}

// ---------------------------------------------------------------------------
// Page
// ---------------------------------------------------------------------------

export function HostServerDetail() {
  const { id } = useParams<{ id: string }>();
  const serverId = Number(id);

  const [server, setServer] = useState<HostServer | null>(null);
  const [offerings, setOfferings] = useState<Offering[]>([]);
  const [results, setResults] = useState<Result[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  const [showAddOffering, setShowAddOffering] = useState(false);
  const [editOffering, setEditOffering] = useState<Offering | null>(null);
  const [deleteOffering, setDeleteOffering] = useState<Offering | null>(null);

  const [showAddResult, setShowAddResult] = useState(false);
  const [editResult, setEditResult] = useState<Result | null>(null);
  const [deleteResult, setDeleteResult] = useState<Result | null>(null);

  async function load() {
    try {
      const [serverRes, offeringsRes, resultsRes] = await Promise.all([
        hostServersApi.get(serverId),
        hostServersApi.listOfferings(serverId),
        hostServersApi.listResults(serverId),
      ]);
      setServer(serverRes.data);
      setOfferings(offeringsRes.data);
      setResults(resultsRes.data);
    } catch (err) {
      setError(extractErrorMessage(err));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [serverId]);

  async function handleDeleteOffering() {
    if (!deleteOffering) return;
    try {
      await hostServersApi.deleteOffering(serverId, deleteOffering.id);
      setDeleteOffering(null);
      await load();
    } catch (err) {
      setError(extractErrorMessage(err));
    }
  }

  async function handleDeleteResult() {
    if (!deleteResult) return;
    try {
      await hostServersApi.deleteResult(serverId, deleteResult.id);
      setDeleteResult(null);
      await load();
    } catch (err) {
      setError(extractErrorMessage(err));
    }
  }

  if (loading) return <div className="page"><div className="loading">Loading…</div></div>;
  if (!server) return <div className="page"><div className="alert alert-error">{error || 'Server not found.'}</div></div>;

  return (
    <div className="page">
      <div className="page-header">
        <div>
          <Link to="/host-servers" className="breadcrumb">Host Servers</Link>
          <span className="breadcrumb-sep">/</span>
          <h1 className="page-title inline">{server.name}</h1>
        </div>
      </div>

      {error && <div className="alert alert-error">{error}</div>}

      {/* Server info */}
      <div className="card mb-4">
        <div className="card-header">
          <h2 className="card-title">Server Info</h2>
        </div>
        <div className="card-body">
          <div className="info-grid">
            <span className="info-label">Name</span><span>{server.name}</span>
            <span className="info-label">URL</span>
            <a href={server.url} className="link" target="_blank" rel="noreferrer">{server.url}</a>
            <span className="info-label">Enrollment Path</span>
            <span className="mono">{server.enrollmentPath || '—'}</span>
            <span className="info-label">Owner</span>
            <span>{server.ownerUsername}</span>
          </div>
        </div>
      </div>

      {/* Results */}
      <div className="card mb-4">
        <div className="card-header">
          <h2 className="card-title">Results</h2>
          <button className="btn btn-primary btn-sm" onClick={() => setShowAddResult(true)}>
            Add Result
          </button>
        </div>
        <div className="card-body p-0">
          <table className="table">
            <thead>
              <tr>
                <th>Name</th>
                <th>state</th>
                <th>pass</th>
                <th>score</th>
                <th>resultDate</th>
                <th>comment</th>
                <th>studyLoad</th>
                <th>ext</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {results.length === 0 && (
                <tr>
                  <td colSpan={9} className="empty-cell">No results. Add one to enable result verification.</td>
                </tr>
              )}
              {results.map((r) => (
                <tr key={r.id}>
                  <td><strong>{r.name}</strong></td>
                  <td>{r.state || '—'}</td>
                  <td>{r.pass || '—'}</td>
                  <td>{r.score || '—'}</td>
                  <td>{r.resultDate || '—'}</td>
                  <td>{r.comment || '—'}</td>
                  <td className="mono" style={{ maxWidth: 120, overflow: 'hidden', textOverflow: 'ellipsis' }}>
                    {r.studyLoad || '—'}
                  </td>
                  <td className="mono" style={{ maxWidth: 120, overflow: 'hidden', textOverflow: 'ellipsis' }}>
                    {r.ext || '—'}
                  </td>
                  <td className="actions-cell">
                    <button className="btn btn-secondary btn-xs" onClick={() => setEditResult(r)}>Edit</button>
                    <button className="btn btn-danger btn-xs" onClick={() => setDeleteResult(r)}>Delete</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      {/* Offerings */}
      <div className="card">
        <div className="card-header">
          <h2 className="card-title">Offerings</h2>
          <button className="btn btn-primary btn-sm" onClick={() => setShowAddOffering(true)}>
            Add Offering
          </button>
        </div>
        <div className="card-body p-0">
          <table className="table">
<thead>
                <tr>
                  <th>Name</th>
                  <th>Offering ID</th>
                  <th>Course Level</th>
                  <th>Actions</th>
                </tr>
              </thead>
              <tbody>
                {offerings.length === 0 && (
                  <tr>
                    <td colSpan={4} className="empty-cell">No offerings. Add one to get started.</td>
                  </tr>
                )}
                {offerings.map((o) => (
                  <tr key={o.id}>
                    <td>{o.name}</td>
                    <td className="mono">{o.offeringId}</td>
                    <td>{o.courseLevel || '—'}</td>
                    <td className="actions-cell">
                      <button className="btn btn-secondary btn-xs" onClick={() => setEditOffering(o)}>Edit</button>
                      <button className="btn btn-danger btn-xs" onClick={() => setDeleteOffering(o)}>Delete</button>
                    </td>
                  </tr>
                ))}
            </tbody>
          </table>
        </div>
      </div>

      {/* Result modals */}
      {showAddResult && (
        <ResultFormModal hostServerId={serverId} onSave={load} onClose={() => setShowAddResult(false)} />
      )}
      {editResult && (
        <ResultFormModal initial={editResult} hostServerId={serverId} onSave={load} onClose={() => setEditResult(null)} />
      )}
      {deleteResult && (
        <ConfirmDialog
          message={`Delete result "${deleteResult.name}"? Any offerings using it will have their result cleared.`}
          confirmLabel="Delete"
          danger
          onConfirm={handleDeleteResult}
          onCancel={() => setDeleteResult(null)}
        />
      )}

      {/* Offering modals */}
      {showAddOffering && (
        <OfferingFormModal hostServerId={serverId} onSave={load} onClose={() => setShowAddOffering(false)} />
      )}
      {editOffering && (
        <OfferingFormModal initial={editOffering} hostServerId={serverId} onSave={load} onClose={() => setEditOffering(null)} />
      )}
      {deleteOffering && (
        <ConfirmDialog
          message={`Delete offering "${deleteOffering.name}"?`}
          confirmLabel="Delete"
          danger
          onConfirm={handleDeleteOffering}
          onCancel={() => setDeleteOffering(null)}
        />
      )}
    </div>
  );
}
