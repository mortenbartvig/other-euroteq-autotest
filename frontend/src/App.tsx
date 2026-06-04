import { useEffect } from 'react';
import { Navigate, Route, Routes, useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from './contexts/AuthContext';
import { Layout } from './components/Layout';
import { Login } from './pages/Login';
import { ChangePassword } from './pages/ChangePassword';
import { Dashboard } from './pages/Dashboard';
import { Users } from './pages/Users';
import { HomeServers } from './pages/HomeServers';
import { HomeServerDetail } from './pages/HomeServerDetail';
import { HostServers } from './pages/HostServers';
import { HostServerDetail } from './pages/HostServerDetail';
import { ResultsMatrix } from './pages/ResultsMatrix';
import { ResultsDetail } from './pages/ResultsDetail';

function RequireAuth({ children }: { children: React.ReactNode }) {
  const { user, loading } = useAuth();
  const location = useLocation();
  const navigate = useNavigate();

  useEffect(() => {
    if (!loading && !user) {
      navigate('/login', { state: { from: location }, replace: true });
    }
    if (!loading && user?.mustChangePassword && location.pathname !== '/change-password') {
      navigate('/change-password', { replace: true });
    }
  }, [loading, user, location, navigate]);

  if (loading) {
    return (
      <div className="app-loading">
        <div className="loading">Loading…</div>
      </div>
    );
  }

  if (!user) return null;

  return <>{children}</>;
}

function RequireAdmin({ children }: { children: React.ReactNode }) {
  const { user } = useAuth();
  if (user?.role !== 'ADMIN') {
    return <Navigate to="/dashboard" replace />;
  }
  return <>{children}</>;
}

export function App() {
  return (
    <Routes>
      <Route path="/login" element={<Login />} />
      <Route path="/change-password" element={<ChangePassword />} />

      <Route
        element={
          <RequireAuth>
            <Layout />
          </RequireAuth>
        }
      >
        <Route index element={<Navigate to="/dashboard" replace />} />
        <Route path="/dashboard" element={<Dashboard />} />

        <Route
          path="/users"
          element={
            <RequireAdmin>
              <Users />
            </RequireAdmin>
          }
        />

        <Route path="/home-servers" element={<HomeServers />} />
        <Route path="/home-servers/:id" element={<HomeServerDetail />} />

        <Route path="/host-servers" element={<HostServers />} />
        <Route path="/host-servers/:id" element={<HostServerDetail />} />

        <Route path="/results" element={<ResultsMatrix />} />
        <Route path="/results/:testRunId/detail" element={<ResultsDetail />} />
      </Route>

      <Route path="*" element={<Navigate to="/dashboard" replace />} />
    </Routes>
  );
}
