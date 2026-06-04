import React, { useState } from 'react';
import { NavLink, Outlet } from 'react-router-dom';
import { useAuth } from '../contexts/AuthContext';

function NavItem({
  to,
  children,
}: {
  to: string;
  children: React.ReactNode;
}) {
  return (
    <NavLink
      to={to}
      className={({ isActive }) =>
        isActive ? 'nav-link nav-link-active' : 'nav-link'
      }
    >
      {children}
    </NavLink>
  );
}

export function Layout() {
  const { user, logout } = useAuth();
  const [sidebarOpen, setSidebarOpen] = useState(false);

  const isAdmin = user?.role === 'ADMIN';

  return (
    <div className="app-shell">
      {/* Top header */}
      <header className="app-header">
        <div className="header-left">
          <button
            className="sidebar-toggle"
            onClick={() => setSidebarOpen((v) => !v)}
            aria-label="Toggle sidebar"
          >
            ☰
          </button>
          <span className="app-name">EuroTeQ AutoTest</span>
        </div>
        <div className="header-right">
          <span className="header-username">{user?.username}</span>
          <span className="header-role">{user?.role}</span>
          <button className="btn btn-secondary btn-sm" onClick={logout}>
            Logout
          </button>
        </div>
      </header>

      <div className="app-body">
        {/* Sidebar */}
        <nav className={`app-sidebar ${sidebarOpen ? 'sidebar-open' : ''}`}>
          <div className="sidebar-section">
            <span className="sidebar-label">Navigation</span>
            <NavItem to="/dashboard">Dashboard</NavItem>
            {isAdmin && <NavItem to="/users">Users</NavItem>}
            <NavItem to="/home-servers">Home Servers</NavItem>
            <NavItem to="/host-servers">Host Servers</NavItem>
            <NavItem to="/results">Results Matrix</NavItem>
          </div>
        </nav>

        {/* Overlay for mobile */}
        {sidebarOpen && (
          <div
            className="sidebar-overlay"
            onClick={() => setSidebarOpen(false)}
          />
        )}

        {/* Main content */}
        <main className="app-main" onClick={() => setSidebarOpen(false)}>
          <Outlet />
        </main>
      </div>
    </div>
  );
}
