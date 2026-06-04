import { FormEvent, useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import {
  HomeServer,
  TestUser,
  extractErrorMessage,
  homeServersApi,
} from '../api/client';
import { ConfirmDialog } from '../components/ConfirmDialog';
import { Modal } from '../components/Modal';

interface TestUserFormData {
  name: string;
  username: string;
  claims: string;
  academicLevel: string;
  alwaysDenied: boolean;
}

function TestUserFormModal({
  initial,
  homeServerId,
  onSave,
  onClose,
}: {
  initial?: TestUser;
  homeServerId: number;
  onSave: () => void;
  onClose: () => void;
}) {
  const isEdit = !!initial;
  const [form, setForm] = useState<TestUserFormData>({
    name: initial?.name ?? '',
    username: initial?.username ?? '',
    claims: initial?.claims ?? '',
    academicLevel: initial?.academicLevel ?? '',
    alwaysDenied: initial?.alwaysDenied ?? false,
  });
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  function validateClaims(v: string): boolean {
    if (!v.trim()) return true;
    try {
      JSON.parse(v);
      return true;
    } catch {
      return false;
    }
  }

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    setError('');

    if (!validateClaims(form.claims)) {
      setError('Claims must be valid JSON or empty.');
      return;
    }

    setLoading(true);
    try {
      const payload = {
        name: form.name,
        username: form.username,
        claims: form.claims.trim() || null,
        academicLevel: form.academicLevel.trim() || null,
        alwaysDenied: form.alwaysDenied,
      };

      if (isEdit && initial) {
        await homeServersApi.updateTestUser(homeServerId, initial.id, payload);
      } else {
        await homeServersApi.createTestUser(homeServerId, payload);
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
    <Modal
      title={isEdit ? 'Edit Test User' : 'Add Test User'}
      onClose={onClose}
      size="lg"
    >
      {error && <div className="alert alert-error">{error}</div>}
      <form onSubmit={handleSubmit}>
        <div className="form-row">
          <div className="form-group">
            <label className="form-label">Display Name</label>
            <input
              className="form-input"
              value={form.name}
              onChange={(e) => setForm({ ...form, name: e.target.value })}
              required
            />
          </div>
          <div className="form-group">
            <label className="form-label">Username (sub)</label>
            <input
              className="form-input"
              value={form.username}
              onChange={(e) => setForm({ ...form, username: e.target.value })}
              required
            />
          </div>
        </div>

        <div className="form-group">
          <label className="form-label">Academic Level</label>
          <input
            className="form-input"
            value={form.academicLevel}
            onChange={(e) =>
              setForm({ ...form, academicLevel: e.target.value })
            }
            placeholder="e.g. bachelor, master, phd"
          />
        </div>

        <div className="form-group">
          <label className="form-label">Claims (JSON)</label>
          <textarea
            className="form-input form-textarea"
            value={form.claims}
            onChange={(e) => setForm({ ...form, claims: e.target.value })}
            rows={5}
            placeholder='{"schac_home_org": "dtu.dk", "email": "user@dtu.dk"}'
          />
          <span className="form-hint">
            Optional JSON object with additional claims for this test user.
          </span>
        </div>

        <div className="form-group">
          <label className="checkbox-label">
            <input
              type="checkbox"
              checked={form.alwaysDenied}
              onChange={(e) =>
                setForm({ ...form, alwaysDenied: e.target.checked })
              }
            />
            Always expected to be denied (negative test case)
          </label>
        </div>

        <div className="form-actions">
          <button type="button" className="btn btn-secondary" onClick={onClose}>
            Cancel
          </button>
          <button type="submit" className="btn btn-primary" disabled={loading}>
            {loading ? 'Saving…' : isEdit ? 'Save Changes' : 'Add Test User'}
          </button>
        </div>
      </form>
    </Modal>
  );
}

function claimsPreview(claims: string | null): string {
  if (!claims) return '—';
  try {
    const obj = JSON.parse(claims);
    const keys = Object.keys(obj).slice(0, 3);
    return keys.map((k) => `${k}: ${obj[k]}`).join(', ') + (Object.keys(obj).length > 3 ? '…' : '');
  } catch {
    return claims.slice(0, 60);
  }
}

export function HomeServerDetail() {
  const { id } = useParams<{ id: string }>();
  const serverId = Number(id);

  const [server, setServer] = useState<HomeServer | null>(null);
  const [testUsers, setTestUsers] = useState<TestUser[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  const [showAdd, setShowAdd] = useState(false);
  const [editUser, setEditUser] = useState<TestUser | null>(null);
  const [deleteUser, setDeleteUser] = useState<TestUser | null>(null);

  async function load() {
    try {
      const [serverRes, usersRes] = await Promise.all([
        homeServersApi.get(serverId),
        homeServersApi.listTestUsers(serverId),
      ]);
      setServer(serverRes.data);
      setTestUsers(usersRes.data);
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

  async function handleDelete() {
    if (!deleteUser) return;
    try {
      await homeServersApi.deleteTestUser(serverId, deleteUser.id);
      setDeleteUser(null);
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
          <Link to="/home-servers" className="breadcrumb">
            Home Servers
          </Link>
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
            <span className="info-label">Name</span>
            <span>{server.name}</span>
            <span className="info-label">URL</span>
            <a href={server.url} className="link" target="_blank" rel="noreferrer">{server.url}</a>
            <span className="info-label">Owner</span>
            <span>{server.ownerUsername}</span>
          </div>
        </div>
      </div>

      {/* Test users */}
      <div className="card">
        <div className="card-header">
          <h2 className="card-title">Test Users</h2>
          <button className="btn btn-primary btn-sm" onClick={() => setShowAdd(true)}>
            Add Test User
          </button>
        </div>
        <div className="card-body p-0">
          <table className="table">
            <thead>
              <tr>
                <th>Name</th>
                <th>Username</th>
                <th>Academic Level</th>
                <th>Always Denied</th>
                <th>Claims</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {testUsers.length === 0 && (
                <tr>
                  <td colSpan={6} className="empty-cell">
                    No test users. Add one to get started.
                  </td>
                </tr>
              )}
              {testUsers.map((u) => (
                <tr key={u.id}>
                  <td>{u.name}</td>
                  <td className="mono">{u.username}</td>
                  <td>{u.academicLevel || '—'}</td>
                  <td>
                    {u.alwaysDenied ? (
                      <span className="badge badge-warning">Yes</span>
                    ) : (
                      <span className="text-muted">No</span>
                    )}
                  </td>
                  <td className="claims-preview">{claimsPreview(u.claims)}</td>
                  <td className="actions-cell">
                    <button
                      className="btn btn-secondary btn-xs"
                      onClick={() => setEditUser(u)}
                    >
                      Edit
                    </button>
                    <button
                      className="btn btn-danger btn-xs"
                      onClick={() => setDeleteUser(u)}
                    >
                      Delete
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      {showAdd && (
        <TestUserFormModal
          homeServerId={serverId}
          onSave={load}
          onClose={() => setShowAdd(false)}
        />
      )}

      {editUser && (
        <TestUserFormModal
          initial={editUser}
          homeServerId={serverId}
          onSave={load}
          onClose={() => setEditUser(null)}
        />
      )}

      {deleteUser && (
        <ConfirmDialog
          message={`Delete test user "${deleteUser.name}"?`}
          confirmLabel="Delete"
          danger
          onConfirm={handleDelete}
          onCancel={() => setDeleteUser(null)}
        />
      )}
    </div>
  );
}
