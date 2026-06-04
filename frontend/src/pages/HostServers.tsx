import { FormEvent, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import {
  AppUser,
  EnrollmentMode,
  HostServer,
  extractErrorMessage,
  hostServersApi,
  usersApi,
} from '../api/client';
import { ConfirmDialog } from '../components/ConfirmDialog';
import { Modal } from '../components/Modal';
import { useAuth } from '../contexts/AuthContext';

interface FormData {
  name: string;
  url: string;
  enrollmentPath: string;
  enrollmentMode: EnrollmentMode;
  brokerScope: string;
  ownerId: string;
  basicAuthUsername: string;
  basicAuthPassword: string;
}

function HostServerFormModal({
  initial,
  users,
  isAdmin,
  currentUserId,
  onSave,
  onClose,
}: {
  initial?: HostServer;
  users: AppUser[];
  isAdmin: boolean;
  currentUserId: number;
  onSave: (data: {
    name: string;
    url: string;
    enrollmentPath?: string;
    enrollmentMode?: EnrollmentMode;
    brokerScope?: string;
    ownerId?: number;
    basicAuthUsername?: string;
    basicAuthPassword?: string;
  }) => Promise<void>;
  onClose: () => void;
}) {
  const isEdit = !!initial;
  const [form, setForm] = useState<FormData>({
    name: initial?.name ?? '',
    url: initial?.url ?? '',
    enrollmentPath: initial?.enrollmentPath ?? '',
    enrollmentMode: initial?.enrollmentMode ?? 'DIRECT',
    brokerScope: initial?.brokerScope ?? 'offline_access',
    ownerId: initial?.ownerId?.toString() ?? currentUserId.toString(),
    basicAuthUsername: initial?.basicAuthUsername ?? '',
    basicAuthPassword: '',
  });
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    setError('');
    setLoading(true);
    try {
      await onSave({
        name: form.name,
        url: form.url,
        enrollmentPath: form.enrollmentPath.trim() || undefined,
        enrollmentMode: form.enrollmentMode,
        brokerScope: form.brokerScope.trim() || undefined,
        ownerId: isAdmin ? Number(form.ownerId) : undefined,
        basicAuthUsername: form.basicAuthUsername.trim() || undefined,
        basicAuthPassword: form.basicAuthPassword.trim() || undefined,
      });
      onClose();
    } catch (err) {
      setError(extractErrorMessage(err));
    } finally {
      setLoading(false);
    }
  }

  return (
    <Modal
      title={isEdit ? 'Edit Host Server' : 'Add Host Server'}
      onClose={onClose}
    >
      {error && <div className="alert alert-error">{error}</div>}
      <form onSubmit={handleSubmit}>
        <div className="form-group">
          <label className="form-label">Name</label>
          <input
            className="form-input"
            value={form.name}
            onChange={(e) => setForm({ ...form, name: e.target.value })}
            required
          />
        </div>
        <div className="form-group">
          <label className="form-label">URL</label>
          <input
            className="form-input"
            type="url"
            value={form.url}
            onChange={(e) => setForm({ ...form, url: e.target.value })}
            required
            placeholder="https://example.edu"
          />
        </div>
        <div className="form-group">
          <label className="form-label">Enrollment Path</label>
          <input
            className="form-input"
            value={form.enrollmentPath}
            onChange={(e) =>
              setForm({ ...form, enrollmentPath: e.target.value })
            }
            placeholder="/api/enrollment (optional)"
          />
        </div>
        <div className="form-group">
          <label className="form-label">Enrollment Mode</label>
          <select
            className="form-input"
            value={form.enrollmentMode}
            onChange={(e) =>
              setForm({ ...form, enrollmentMode: e.target.value as EnrollmentMode })
            }
          >
            <option value="DIRECT">DIRECT — POST directly to enrollment endpoint</option>
            <option value="BROKER">BROKER — Full inteken-ontvanger OAuth redirect flow</option>
          </select>
        </div>
        {form.enrollmentMode === 'BROKER' && (
          <div className="form-group">
            <label className="form-label">Broker OAuth Scope</label>
            <input
              className="form-input"
              value={form.brokerScope}
              onChange={(e) => setForm({ ...form, brokerScope: e.target.value })}
              placeholder="offline_access"
            />
            <small className="form-hint">
              Extra OAuth scopes for the enrollment request (space-separated). Do not include
              <code> openid</code> — the inteken-ontvanger always prepends it. Example:{' '}
              <code>offline_access email dtu.dk/persons</code>
            </small>
          </div>
        )}
        <div className="form-group">
          <label className="form-label">Basic Auth Username</label>
          <input
            className="form-input"
            value={form.basicAuthUsername}
            onChange={(e) => setForm({ ...form, basicAuthUsername: e.target.value })}
            placeholder="Leave blank if not required"
            autoComplete="off"
          />
        </div>
        <div className="form-group">
          <label className="form-label">
            Basic Auth Password
            {isEdit && initial?.hasBasicAuth && ' (leave blank to keep current)'}
          </label>
          <input
            className="form-input"
            type="password"
            value={form.basicAuthPassword}
            onChange={(e) => setForm({ ...form, basicAuthPassword: e.target.value })}
            placeholder={isEdit && initial?.hasBasicAuth ? '••••••••' : 'Leave blank if not required'}
            autoComplete="new-password"
          />
        </div>
        {isAdmin && (
          <div className="form-group">
            <label className="form-label">Owner</label>
            <select
              className="form-input"
              value={form.ownerId}
              onChange={(e) => setForm({ ...form, ownerId: e.target.value })}
            >
              {users.map((u) => (
                <option key={u.id} value={u.id}>
                  {u.username}
                </option>
              ))}
            </select>
          </div>
        )}
        <div className="form-actions">
          <button type="button" className="btn btn-secondary" onClick={onClose}>
            Cancel
          </button>
          <button type="submit" className="btn btn-primary" disabled={loading}>
            {loading ? 'Saving…' : isEdit ? 'Save Changes' : 'Add Server'}
          </button>
        </div>
      </form>
    </Modal>
  );
}

export function HostServers() {
  const { user } = useAuth();
  const isAdmin = user?.role === 'ADMIN';

  const [servers, setServers] = useState<HostServer[]>([]);
  const [users, setUsers] = useState<AppUser[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  const [showAdd, setShowAdd] = useState(false);
  const [editServer, setEditServer] = useState<HostServer | null>(null);
  const [deleteServer, setDeleteServer] = useState<HostServer | null>(null);

  async function load() {
    try {
      const [serversRes, usersRes] = await Promise.all([
        hostServersApi.list(),
        isAdmin ? usersApi.list() : Promise.resolve({ data: [] as AppUser[] }),
      ]);
      setServers(serversRes.data);
      setUsers(usersRes.data);
    } catch (err) {
      setError(extractErrorMessage(err));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  async function handleCreate(data: {
    name: string;
    url: string;
    enrollmentPath?: string;
    enrollmentMode?: EnrollmentMode;
    brokerScope?: string;
    ownerId?: number;
    basicAuthUsername?: string;
    basicAuthPassword?: string;
  }) {
    await hostServersApi.create(data);
    await load();
  }

  async function handleEdit(data: {
    name: string;
    url: string;
    enrollmentPath?: string;
    enrollmentMode?: EnrollmentMode;
    brokerScope?: string;
    ownerId?: number;
    basicAuthUsername?: string;
    basicAuthPassword?: string;
  }) {
    if (!editServer) return;
    await hostServersApi.update(editServer.id, data);
    await load();
  }

  async function handleDelete() {
    if (!deleteServer) return;
    try {
      await hostServersApi.delete(deleteServer.id);
      setDeleteServer(null);
      await load();
    } catch (err) {
      setError(extractErrorMessage(err));
    }
  }

  if (loading) return <div className="page"><div className="loading">Loading host servers…</div></div>;

  return (
    <div className="page">
      <div className="page-header">
        <h1 className="page-title">Host Servers</h1>
        <button className="btn btn-primary" onClick={() => setShowAdd(true)}>
          Add Host Server
        </button>
      </div>

      {error && <div className="alert alert-error">{error}</div>}

      <div className="card">
        <div className="card-body p-0">
          <table className="table">
            <thead>
              <tr>
                <th>Name</th>
                <th>URL</th>
                <th>Mode</th>
                <th>Enrollment Path</th>
                <th>Basic Auth</th>
                {isAdmin && <th>Owner</th>}
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {servers.length === 0 && (
                <tr>
                  <td colSpan={isAdmin ? 7 : 6} className="empty-cell">
                    No host servers found.
                  </td>
                </tr>
              )}
              {servers.map((s) => (
                <tr key={s.id}>
                  <td>
                    <Link to={`/host-servers/${s.id}`} className="link">
                      {s.name}
                    </Link>
                  </td>
                  <td className="url-cell">{s.url}</td>
                  <td>{s.enrollmentMode ?? 'DIRECT'}</td>
                  <td className="mono">{s.enrollmentPath || '—'}</td>
                  <td>{s.hasBasicAuth ? s.basicAuthUsername || '✓' : '—'}</td>
                  {isAdmin && <td>{s.ownerUsername}</td>}
                  <td className="actions-cell">
                    <Link
                      to={`/host-servers/${s.id}`}
                      className="btn btn-secondary btn-xs"
                    >
                      Details
                    </Link>
                    <button
                      className="btn btn-secondary btn-xs"
                      onClick={() => setEditServer(s)}
                    >
                      Edit
                    </button>
                    <button
                      className="btn btn-danger btn-xs"
                      onClick={() => setDeleteServer(s)}
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
        <HostServerFormModal
          users={users}
          isAdmin={isAdmin}
          currentUserId={user!.id}
          onSave={handleCreate}
          onClose={() => setShowAdd(false)}
        />
      )}

      {editServer && (
        <HostServerFormModal
          initial={editServer}
          users={users}
          isAdmin={isAdmin}
          currentUserId={user!.id}
          onSave={handleEdit}
          onClose={() => setEditServer(null)}
        />
      )}

      {deleteServer && (
        <ConfirmDialog
          message={`Delete host server "${deleteServer.name}"? This will also delete all associated offerings.`}
          confirmLabel="Delete"
          danger
          onConfirm={handleDelete}
          onCancel={() => setDeleteServer(null)}
        />
      )}
    </div>
  );
}
