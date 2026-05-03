import { Outlet } from 'react-router-dom';
import { useState } from 'react';
import { AppShell, IconButton } from '@ossrandom/design-system';
import { useTheme } from '@/context/ThemeContext';

function SunIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <circle cx="12" cy="12" r="4" />
      <path d="M12 2v2M12 20v2M4.93 4.93l1.41 1.41M17.66 17.66l1.41 1.41M2 12h2M20 12h2M4.93 19.07l1.41-1.41M17.66 6.34l1.41-1.41" />
    </svg>
  );
}

function MoonIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z" />
    </svg>
  );
}

function CopyIcon({ ok }: { ok: boolean }) {
  if (ok) {
    return (
      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
        <path d="M20 6L9 17l-5-5" />
      </svg>
    );
  }
  return (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <rect x="9" y="9" width="13" height="13" rx="2" ry="2" />
      <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1" />
    </svg>
  );
}

function McpUrlPill() {
  const [copied, setCopied] = useState(false);
  const url = (typeof window !== 'undefined' ? window.location.origin : '') + '/mcp';
  const onCopy = async () => {
    try {
      await navigator.clipboard.writeText(url);
      setCopied(true);
      setTimeout(() => setCopied(false), 1200);
    } catch {
      /* clipboard blocked — silent */
    }
  };
  return (
    <div className="codeiq-mcp-url" title={url}>
      <span>MCP&nbsp;·&nbsp;{url}</span>
      <button onClick={onCopy} aria-label="Copy MCP URL" type="button">
        <CopyIcon ok={copied} />
      </button>
    </div>
  );
}

function Header() {
  const { isDark, toggle } = useTheme();
  return (
    <div className="codeiq-header">
      <div className="codeiq-brand">Code IQ</div>
      <div className="codeiq-header-actions">
        <McpUrlPill />
        <IconButton
          icon={isDark ? <SunIcon /> : <MoonIcon />}
          aria-label={isDark ? 'Switch to light mode' : 'Switch to dark mode'}
          variant="ghost"
          size="sm"
          onClick={toggle}
        />
      </div>
    </div>
  );
}

export default function AppLayout() {
  return (
    <AppShell header={<Header />}>
      <div className="codeiq-content">
        <Outlet />
      </div>
    </AppShell>
  );
}
