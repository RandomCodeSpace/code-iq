import { useState } from 'react';
import { Outlet, NavLink, useLocation } from 'react-router-dom';
import {
  LayoutDashboard,
  Network,
  FolderSearch,
  Workflow,
  Terminal,
  BookOpen,
  Hexagon,
  Menu,
  X,
} from 'lucide-react';
import ThemeToggle from './ThemeToggle';
import SearchBar from './SearchBar';

const navItems = [
  { path: '/', label: 'Dashboard', icon: LayoutDashboard },
  { path: '/topology', label: 'Topology', icon: Network },
  { path: '/explorer', label: 'Explorer', icon: FolderSearch },
  { path: '/flow', label: 'Flow', icon: Workflow },
  { path: '/console', label: 'Console', icon: Terminal },
  { path: '/api-docs', label: 'API Docs', icon: BookOpen },
];

export default function Layout() {
  const [sidebarOpen, setSidebarOpen] = useState(false);
  const location = useLocation();

  return (
    <div className="flex h-screen overflow-hidden bg-surface-950 dark:bg-surface-950">
      {/* Sidebar */}
      <aside
        className={`
          fixed inset-y-0 left-0 z-40 w-60 flex flex-col
          bg-surface-900/80 backdrop-blur-xl border-r border-surface-800/50
          transform transition-transform duration-300 ease-in-out
          lg:relative lg:translate-x-0
          ${sidebarOpen ? 'translate-x-0' : '-translate-x-full'}
        `}
      >
        {/* Logo */}
        <div className="flex items-center gap-3 px-5 py-5 border-b border-surface-800/50">
          <div className="relative">
            <Hexagon className="w-8 h-8 text-brand-500" strokeWidth={1.5} />
            <div className="absolute inset-0 flex items-center justify-center">
              <span className="text-[10px] font-bold text-brand-400">IQ</span>
            </div>
          </div>
          <div>
            <h1 className="text-sm font-semibold text-surface-100">OSSCodeIQ</h1>
            <p className="text-[10px] text-surface-500 font-mono">Knowledge Graph</p>
          </div>
          <button
            onClick={() => setSidebarOpen(false)}
            className="ml-auto lg:hidden text-surface-400 hover:text-surface-200"
          >
            <X className="w-5 h-5" />
          </button>
        </div>

        {/* Nav */}
        <nav className="flex-1 py-4 px-3 space-y-1 overflow-y-auto">
          {navItems.map(item => {
            const Icon = item.icon;
            const isActive = item.path === '/'
              ? location.pathname === '/'
              : location.pathname.startsWith(item.path);
            return (
              <NavLink
                key={item.path}
                to={item.path}
                onClick={() => setSidebarOpen(false)}
                className={`
                  flex items-center gap-3 px-3 py-2.5 rounded-lg text-sm font-medium
                  transition-all duration-200
                  ${isActive
                    ? 'bg-brand-500/10 text-brand-400 border border-brand-500/20 glow-brand'
                    : 'text-surface-400 hover:text-surface-200 hover:bg-surface-800/50'
                  }
                `}
              >
                <Icon className="w-4 h-4 flex-shrink-0" />
                <span>{item.label}</span>
              </NavLink>
            );
          })}
        </nav>

        {/* Theme toggle at bottom */}
        <div className="p-3 border-t border-surface-800/50">
          <ThemeToggle />
        </div>
      </aside>

      {/* Mobile overlay */}
      {sidebarOpen && (
        <div
          className="fixed inset-0 z-30 bg-black/60 backdrop-blur-sm lg:hidden"
          onClick={() => setSidebarOpen(false)}
        />
      )}

      {/* Main content */}
      <div className="flex-1 flex flex-col min-w-0 overflow-hidden">
        {/* Header */}
        <header className="flex items-center gap-4 px-4 lg:px-6 py-3 border-b border-surface-800/50 bg-surface-900/40 backdrop-blur-md">
          <button
            onClick={() => setSidebarOpen(true)}
            className="lg:hidden text-surface-400 hover:text-surface-200"
          >
            <Menu className="w-5 h-5" />
          </button>
          <SearchBar />
        </header>

        {/* Page content */}
        <main className="flex-1 overflow-y-auto p-4 lg:p-6">
          <Outlet />
        </main>
      </div>
    </div>
  );
}
