import { useState, createContext, useContext } from 'react';
import { Outlet, NavLink, useLocation } from 'react-router-dom';
import {
  Group as PanelGroup,
  Panel as ResizablePanel,
  Separator as ResizeHandle,
} from 'react-resizable-panels';
import {
  LayoutDashboard,
  FolderSearch,
  Terminal,
  BookOpen,
  Hexagon,
  Menu,
  X,
  GitGraph,
  ChevronLeft,
  ChevronRight,
  Sun,
  Moon,
  Monitor,
  User,
} from 'lucide-react';
import { cn } from '@/lib/utils';
import { Button } from '@/components/ui/button';
import { Separator } from '@/components/ui/separator';
import SearchBar from './SearchBar';
import { useTheme } from '@/hooks/useTheme';

/* ------------------------------------------------------------------ */
/* Right-panel context — lets child views open the details panel       */
/* ------------------------------------------------------------------ */
interface RightPanelCtx {
  openPanel: (content: React.ReactNode) => void;
  closePanel: () => void;
  isOpen: boolean;
}

export const RightPanelContext = createContext<RightPanelCtx>({
  openPanel: () => {},
  closePanel: () => {},
  isOpen: false,
});

export function useRightPanel() {
  return useContext(RightPanelContext);
}

/* ------------------------------------------------------------------ */
/* Nav items                                                            */
/* ------------------------------------------------------------------ */
const navItems = [
  { path: '/',           label: 'Dashboard',  icon: LayoutDashboard },
  { path: '/graph',      label: 'Code Graph', icon: GitGraph },
  { path: '/explorer',   label: 'Explorer',   icon: FolderSearch },
  { path: '/console',    label: 'Console',    icon: Terminal },
  { path: '/api-docs',   label: 'API Docs',   icon: BookOpen },
];

/* ------------------------------------------------------------------ */
/* Theme toggle (migrated to shadcn/ui styling)                        */
/* ------------------------------------------------------------------ */
function ThemeToggle() {
  const { theme, setTheme } = useTheme();
  const cycle = () => {
    const next = theme === 'dark' ? 'light' : theme === 'light' ? 'system' : 'dark';
    setTheme(next);
  };
  return (
    <Button
      variant="ghost"
      size="icon"
      onClick={cycle}
      title={`Theme: ${theme}`}
      className="text-muted-foreground hover:text-foreground"
    >
      {theme === 'dark'   && <Moon   className="w-4 h-4" />}
      {theme === 'light'  && <Sun    className="w-4 h-4" />}
      {theme === 'system' && <Monitor className="w-4 h-4" />}
      <span className="sr-only">Toggle theme</span>
    </Button>
  );
}

/* ------------------------------------------------------------------ */
/* Left Sidebar                                                         */
/* ------------------------------------------------------------------ */
interface SidebarProps {
  collapsed: boolean;
  mobileOpen: boolean;
  onCollapse: () => void;
  onMobileClose: () => void;
}

function Sidebar({ collapsed, mobileOpen, onCollapse, onMobileClose }: SidebarProps) {
  const location = useLocation();

  return (
    <>
      {/* Mobile overlay */}
      {mobileOpen && (
        <div
          className="fixed inset-0 z-30 bg-black/60 backdrop-blur-sm lg:hidden"
          onClick={onMobileClose}
        />
      )}

      {/* Sidebar panel */}
      <aside
        className={cn(
          'fixed inset-y-0 left-0 z-40 flex flex-col',
          'bg-sidebar border-r border-sidebar-border',
          'transform transition-all duration-300 ease-in-out',
          // Desktop: always visible, width collapses
          'lg:relative lg:translate-x-0',
          collapsed ? 'lg:w-14' : 'lg:w-60',
          // Mobile: slide in/out, always full width
          mobileOpen ? 'translate-x-0 w-60' : '-translate-x-full w-60',
        )}
        aria-label="Main navigation"
      >
        {/* Logo / header */}
        <div className={cn(
          'flex items-center border-b border-sidebar-border shrink-0',
          collapsed ? 'px-2 py-4 justify-center' : 'px-4 py-4 gap-3',
        )}>
          <div className="relative shrink-0">
            <Hexagon className="w-8 h-8 text-primary" strokeWidth={1.5} />
            <div className="absolute inset-0 flex items-center justify-center">
              <span className="text-[9px] font-bold text-primary">IQ</span>
            </div>
          </div>

          {!collapsed && (
            <div className="flex-1 min-w-0">
              <h1 className="text-sm font-semibold text-sidebar-foreground truncate">Code IQ</h1>
              <p className="text-[10px] text-muted-foreground font-mono truncate">Knowledge Graph</p>
            </div>
          )}

          {/* Mobile close */}
          <Button
            variant="ghost"
            size="icon"
            onClick={onMobileClose}
            className="lg:hidden ml-auto text-muted-foreground shrink-0"
            aria-label="Close sidebar"
          >
            <X className="w-4 h-4" />
          </Button>

          {/* Desktop collapse toggle */}
          <Button
            variant="ghost"
            size="icon"
            onClick={onCollapse}
            className="hidden lg:flex ml-auto text-muted-foreground hover:text-foreground shrink-0"
            aria-label={collapsed ? 'Expand sidebar' : 'Collapse sidebar'}
          >
            {collapsed
              ? <ChevronRight className="w-4 h-4" />
              : <ChevronLeft  className="w-4 h-4" />
            }
          </Button>
        </div>

        {/* Navigation */}
        <nav className="flex-1 overflow-y-auto py-3 px-2 space-y-0.5" role="navigation">
          {navItems.map(item => {
            const Icon = item.icon;
            const isActive = item.path === '/'
              ? location.pathname === '/'
              : location.pathname.startsWith(item.path);

            return (
              <NavLink
                key={item.path}
                to={item.path}
                onClick={onMobileClose}
                title={collapsed ? item.label : undefined}
                className={cn(
                  'flex items-center rounded-md text-sm font-medium transition-colors duration-150',
                  collapsed ? 'justify-center px-2 py-2.5' : 'gap-3 px-3 py-2.5',
                  isActive
                    ? 'bg-sidebar-accent text-sidebar-accent-foreground'
                    : 'text-sidebar-foreground/70 hover:bg-sidebar-accent/60 hover:text-sidebar-foreground',
                )}
                aria-current={isActive ? 'page' : undefined}
              >
                <Icon className="w-4 h-4 shrink-0" />
                {!collapsed && <span className="truncate">{item.label}</span>}
              </NavLink>
            );
          })}
        </nav>

        {/* File tree placeholder */}
        {!collapsed && (
          <>
            <Separator />
            <div className="px-3 py-3">
              <p className="text-[10px] font-semibold text-muted-foreground uppercase tracking-wider mb-2">
                Project Files
              </p>
              <div className="text-xs text-muted-foreground/60 italic px-1">
                File tree coming in Phase 2
              </div>
            </div>
          </>
        )}

        {/* Theme toggle at bottom */}
        <Separator />
        <div className={cn(
          'p-2 flex',
          collapsed ? 'justify-center' : 'justify-start',
        )}>
          <ThemeToggle />
          {!collapsed && (
            <span className="ml-2 text-xs text-muted-foreground self-center capitalize">
              theme
            </span>
          )}
        </div>
      </aside>
    </>
  );
}

/* ------------------------------------------------------------------ */
/* Right Details Panel                                                  */
/* ------------------------------------------------------------------ */
interface RightPanelProps {
  isOpen: boolean;
  content: React.ReactNode;
  onClose: () => void;
}

function RightDetailsPanel({ isOpen, content, onClose }: RightPanelProps) {
  if (!isOpen) return null;

  return (
    <aside
      className={cn(
        'flex flex-col w-80 shrink-0',
        'bg-card border-l border-border',
        'animate-in slide-in-from-right duration-250',
      )}
      aria-label="Details panel"
    >
      {/* Panel header */}
      <div className="flex items-center justify-between px-4 py-3 border-b border-border shrink-0">
        <span className="text-sm font-semibold text-foreground">Details</span>
        <Button
          variant="ghost"
          size="icon"
          onClick={onClose}
          className="text-muted-foreground hover:text-foreground -mr-1"
          aria-label="Close details panel"
        >
          <X className="w-4 h-4" />
        </Button>
      </div>

      {/* Panel content */}
      <div className="flex-1 overflow-y-auto p-4">
        {content ?? (
          <p className="text-sm text-muted-foreground">Select a node to view details.</p>
        )}
      </div>
    </aside>
  );
}

/* ------------------------------------------------------------------ */
/* Header                                                               */
/* ------------------------------------------------------------------ */
interface HeaderProps {
  onMobileMenuOpen: () => void;
  rightPanelOpen: boolean;
  onToggleRightPanel: () => void;
}

function Header({ onMobileMenuOpen }: HeaderProps) {
  return (
    <header
      className="flex items-center gap-3 px-4 py-2.5 border-b border-border bg-background/80 backdrop-blur-md shrink-0"
      role="banner"
    >
      {/* Mobile hamburger */}
      <Button
        variant="ghost"
        size="icon"
        onClick={onMobileMenuOpen}
        className="lg:hidden text-muted-foreground"
        aria-label="Open navigation menu"
      >
        <Menu className="w-5 h-5" />
      </Button>

      {/* Global search */}
      <div className="flex-1 max-w-xl">
        <SearchBar />
      </div>

      {/* Right header actions */}
      <div className="flex items-center gap-1 ml-auto">
        <ThemeToggle />

        {/* Profile placeholder */}
        <Button
          variant="ghost"
          size="icon"
          className="text-muted-foreground hover:text-foreground"
          aria-label="User profile"
          title="User profile"
        >
          <User className="w-4 h-4" />
        </Button>
      </div>
    </header>
  );
}

/* ------------------------------------------------------------------ */
/* Root Layout                                                          */
/* ------------------------------------------------------------------ */
export default function Layout() {
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false);
  const [mobileOpen, setMobileOpen] = useState(false);
  const [rightPanelOpen, setRightPanelOpen] = useState(false);
  const [rightPanelContent, setRightPanelContent] = useState<React.ReactNode>(null);

  const openPanel = (content: React.ReactNode) => {
    setRightPanelContent(content);
    setRightPanelOpen(true);
  };

  const closePanel = () => {
    setRightPanelOpen(false);
    setRightPanelContent(null);
  };

  return (
    <RightPanelContext.Provider value={{ openPanel, closePanel, isOpen: rightPanelOpen }}>
      <div className="flex h-screen overflow-hidden bg-background text-foreground">

        {/* Left Sidebar */}
        <Sidebar
          collapsed={sidebarCollapsed}
          mobileOpen={mobileOpen}
          onCollapse={() => setSidebarCollapsed(c => !c)}
          onMobileClose={() => setMobileOpen(false)}
        />

        {/* Main area: header + resizable panels */}
        <div className="flex-1 flex flex-col min-w-0 overflow-hidden">
          <Header
            onMobileMenuOpen={() => setMobileOpen(true)}
            rightPanelOpen={rightPanelOpen}
            onToggleRightPanel={() => setRightPanelOpen(o => !o)}
          />

          {/* Resizable panel group: main content + right panel */}
          <PanelGroup orientation="horizontal" className="flex-1 overflow-hidden">
            {/* Main content panel */}
            <ResizablePanel
              defaultSize={rightPanelOpen ? 72 : 100}
              minSize={40}
              className="overflow-hidden"
            >
              <main
                className="h-full overflow-y-auto p-4 lg:p-6"
                role="main"
                id="main-content"
              >
                <Outlet />
              </main>
            </ResizablePanel>

            {/* Resize handle — only rendered when right panel is open */}
            {rightPanelOpen && (
              <ResizeHandle
                className="w-1.5 bg-border hover:bg-primary/30 transition-colors duration-150 cursor-col-resize"
                aria-label="Resize panels"
              />
            )}

            {/* Right details panel */}
            {rightPanelOpen && (
              <ResizablePanel
                defaultSize={28}
                minSize={20}
                maxSize={50}
                className="overflow-hidden"
              >
                <RightDetailsPanel
                  isOpen={rightPanelOpen}
                  content={rightPanelContent}
                  onClose={closePanel}
                />
              </ResizablePanel>
            )}
          </PanelGroup>
        </div>
      </div>
    </RightPanelContext.Provider>
  );
}
