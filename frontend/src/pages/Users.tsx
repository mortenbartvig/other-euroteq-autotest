import { FormEvent, useEffect, useState } from 'react';
import { AppUser, Role, extractErrorMessage, usersApi } from '../api/client';
import { ConfirmDialog } from '../components/ConfirmDialog';
import { Modal } from '../components/Modal';

interface UserFormData {
  username: string;
  password: string;
  role: Role;
}

interface ResetPasswordFormData {
  newPassword: string;
  confirmPassword: string;
}

function UserFormModal({
  initial,
  onSave,
  onClose,
}: {
  initial?: AppUser;
  onSave: (data: { username: string; password: string; role: Role }) => Promise<void>;
  onClose: () => void;
}) {
  const isEdit = !!initial;
  const [form, setForm] = useState<UserFormData>({
    username: initial?.username ?? '',
    password: '',
    role: initial?.role ?? 'USER',
  });
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    setError('');
    if (!isEdit && !form.password) {
      setError('Password is required when creating a user.');
      return;
    }
    setLoading(true);
    try {
      await onSave(form);
      onClose();
    } catch (err) {
      setError(extractErrorMessage(err));
    } finally {
      setLoading(false);
    }
  }

  return (
    <Modal title={isEdit ? 'Edit User' : 'Add User'} onClose={onClose}>
      {error && <div className="alert alert-error">{error}</div>}
      <form onSubmit={handleSubmit}>
        <div className="form-group">
          <label className="form-label">Username</label>
          <input
            className="form-input"
            value={form.username}
            onChange={(e) => setForm({ ...form, username: e.target.value })}
            required
          />
        </div>
        {!isEdit && (
          <div className="form-group">
            <label className="form-label">Password</label>
            <input
              className="form-input"
              type="password"
              value={form.password}
              onChange={(e) => setForm({ ...form, password: e.target.value })}
              required
              minLength={8}
            />
          </div>
        )}
        <div className="form-group">
          <label className="form-label">Role</label>
          <select
            className="form-input"
            value={form.role}
            onChange={(e) => setForm({ ...form, role: e.target.value as Role })}
          >
            <option value="USER">USER</option>
            <option value="ADMIN">ADMIN</option>
          </select>
        </div>
        <div className="form-actions">
          <button
            type="button"
            className="btn btn-secondary"
            onClick={onClose}
          >
            Cancel
          </button>
          <button type="submit" className="btn btn-primary" disabled={loading}>
            {loading ? 'Saving…' : isEdit ? 'Save Changes' : 'Create User'}
          </button>
        </div>
      </form>
    </Modal>
  );
}

function ResetPasswordModal({
  user,
  onSave,
  onClose,
}: {
  user: AppUser;
  onSave: (newPassword: string) => Promise<void>;
  onClose: () => void;
}) {
  const [form, setForm] = useState<ResetPasswordFormData>({
    newPassword: '',
    confirmPassword: '',
  });
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    setError('');
    if (form.newPassword !== form.confirmPassword) {
      setError('Passwords do not match.');
      return;
    }
    if (form.newPassword.length < 8) {
      setError('Password must be at least 8 characters.');
      return;
    }
    setLoading(true);
    try {
      await onSave(form.newPassword);
      onClose();
    } catch (err) {
      setError(extractErrorMessage(err));
    } finally {
      setLoading(false);
    }
  }

  return (
    <Modal title={`Reset Password — ${user.username}`} onClose={onClose}>
      {error && <div className="alert alert-error">{error}</div>}
      <form onSubmit={handleSubmit}>
        <div className="form-group">
          <label className="form-label">New Password</label>
          <input
            className="form-input"
            type="password"
            value={form.newPassword}
            onChange={(e) => setForm({ ...form, newPassword: e.target.value })}
            required
            minLength={8}
          />
        </div>
        <div className="form-group">
          <label className="form-label">Confirm New Password</label>
          <input
            className="form-input"
            type="password"
            value={form.confirmPassword}
            onChange={(e) =>
              setForm({ ...form, confirmPassword: e.target.value })
            }
            required
          />
        </div>
        <div className="form-actions">
          <button
            type="button"
            className="btn btn-secondary"
            onClick={onClose}
          >
            Cancel
          </button>
          <button type="submit" className="btn btn-primary" disabled={loading}>
            {loading ? 'Saving…' : 'Reset Password'}
          </button>
        </div>
      </form>
    </Modal>
  );
}

export function Users() {
  const [users, setUsers] = useState<AppUser[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  const [showAdd, setShowAdd] = useState(false);
  const [editUser, setEditUser] = useState<AppUser | null>(null);
  const [resetUser, setResetUser] = useState<AppUser | null>(null);
  const [deleteUser, setDeleteUser] = useState<AppUser | null>(null);

  async function load() {
    try {
      const res = await usersApi.list();
      setUsers(res.data);
    } catch (err) {
      setError(extractErrorMessage(err));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    load();
  }, []);

  async function handleCreate(data: {
    username: string;
    password: string;
    role: Role;
  }) {
    await usersApi.create(data);
    await load();
  }

  async function handleEdit(data: {
    username: string;
    password: string;
    role: Role;
  }) {
    if (!editUser) return;
    await usersApi.update(editUser.id, {
      username: data.username,
      role: data.role,
    });
    await load();
  }

  async function handleDelete() {
    if (!deleteUser) return;
    try {
      await usersApi.delete(deleteUser.id);
      setDeleteUser(null);
      await load();
    } catch (err) {
      setError(extractErrorMessage(err));
    }
  }

  async function handleResetPassword(newPassword: string) {
    if (!resetUser) return;
    await usersApi.resetPassword(resetUser.id, newPassword);
  }

  if (loading) return <div className="page"><div className="loading">Loading users…</div></div>;

  return (
    <div className="page">
      <div className="page-header">
        <h1 className="page-title">Users</h1>
        <button className="btn btn-primary" onClick={() => setShowAdd(true)}>
          Add User
        </button>
      </div>

      {error && <div className="alert alert-error">{error}</div>}

      <div className="card">
        <div className="card-body p-0">
          <table className="table">
            <thead>
              <tr>
                <th>Username</th>
                <th>Role</th>
                <th>Must Change Password</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {users.length === 0 && (
                <tr>
                  <td colSpan={4} className="empty-cell">
                    No users found.
                  </td>
                </tr>
              )}
              {users.map((u) => (
                <tr key={u.id}>
                  <td>{u.username}</td>
                  <td>
                    <span
                      className={
                        u.role === 'ADMIN'
                          ? 'badge badge-info'
                          : 'badge badge-secondary'
                      }
                    >
                      {u.role}
                    </span>
                  </td>
                  <td>
                    {u.mustChangePassword ? (
                      <span className="badge badge-warning">Yes</span>
                    ) : (
                      <span className="text-muted">No</span>
                    )}
                  </td>
                  <td className="actions-cell">
                    <button
                      className="btn btn-secondary btn-xs"
                      onClick={() => setEditUser(u)}
                    >
                      Edit
                    </button>
                    <button
                      className="btn btn-secondary btn-xs"
                      onClick={() => setResetUser(u)}
                    >
                      Reset Password
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
        <UserFormModal
          onSave={handleCreate}
          onClose={() => setShowAdd(false)}
        />
      )}

      {editUser && (
        <UserFormModal
          initial={editUser}
          onSave={handleEdit}
          onClose={() => setEditUser(null)}
        />
      )}

      {resetUser && (
        <ResetPasswordModal
          user={resetUser}
          onSave={handleResetPassword}
          onClose={() => setResetUser(null)}
        />
      )}

      {deleteUser && (
        <ConfirmDialog
          message={`Delete user "${deleteUser.username}"? This cannot be undone.`}
          confirmLabel="Delete"
          danger
          onConfirm={handleDelete}
          onCancel={() => setDeleteUser(null)}
        />
      )}
    </div>
  );
}
