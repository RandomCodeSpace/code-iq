import {
  useState,
  useCallback,
  useRef,
  useEffect,
  useMemo,
  KeyboardEvent,
} from 'react';
import {
  ChevronRight,
  ChevronDown,
  Folder,
  FolderOpen,
  File,
  FileCode,
  FileJson,
  FileText,
  Loader2,
  AlertCircle,
  Search,
  X,
} from 'lucide-react';
import { cn } from '@/lib/utils';
import { api } from '@/lib/api';
import { useApi } from '@/hooks/useApi';
import type { FileTreeNode, FileTreeResponse } from '@/types/api';
import { useFileSelection } from '@/contexts/FileSelectionContext';
import { useNavigate } from 'react-router-dom';

/* ------------------------------------------------------------------ */
/* File extension → icon mapping                                        */
/* ------------------------------------------------------------------ */

const CODE_EXTS = new Set([
  'ts', 'tsx', 'js', 'jsx', 'java', 'kt', 'py', 'go', 'rs', 'cs',
  'cpp', 'c', 'h', 'hpp', 'rb', 'php', 'swift', 'scala', 'clj',
  'ex', 'exs', 'lua', 'r', 'sh', 'bash', 'ps1',
]);

const JSON_EXTS = new Set(['json', 'jsonc', 'json5']);
const DOC_EXTS  = new Set(['md', 'mdx', 'txt', 'rst', 'adoc']);

function FileIcon({ name, className }: { name: string; className?: string }) {
  const ext = name.split('.').pop()?.toLowerCase() ?? '';
  if (CODE_EXTS.has(ext)) return <FileCode className={className} />;
  if (JSON_EXTS.has(ext))  return <FileJson className={className} />;
  if (DOC_EXTS.has(ext))   return <FileText className={className} />;
  return <File className={className} />;
}

/* ------------------------------------------------------------------ */
/* Tree filtering helpers                                               */
/* ------------------------------------------------------------------ */

function filterTree(node: FileTreeNode, query: string): FileTreeNode | null {
  if (!query) return node;
  const q = query.toLowerCase();

  if (node.type === 'file') {
    return node.name.toLowerCase().includes(q) ? node : null;
  }

  // directory: keep if name matches OR any child matches
  if (node.name.toLowerCase().includes(q)) return node;

  const filteredChildren = (node.children ?? [])
    .map(child => filterTree(child, query))
    .filter((c): c is FileTreeNode => c !== null);

  if (filteredChildren.length === 0) return null;

  return { ...node, children: filteredChildren };
}

/* ------------------------------------------------------------------ */
/* Collect all visible paths for keyboard navigation                    */
/* ------------------------------------------------------------------ */

function collectVisible(
  node: FileTreeNode,
  expanded: Set<string>,
  result: string[] = [],
): string[] {
  result.push(node.path);
  if (node.type === 'directory' && expanded.has(node.path)) {
    for (const child of node.children ?? []) {
      collectVisible(child, expanded, result);
    }
  }
  return result;
}

/* ------------------------------------------------------------------ */
/* Single tree node row                                                  */
/* ------------------------------------------------------------------ */

interface TreeNodeRowProps {
  node: FileTreeNode;
  depth: number;
  isExpanded: boolean;
  isSelected: boolean;
  isFocused: boolean;
  onToggle: (path: string) => void;
  onSelect: (node: FileTreeNode) => void;
  onFocus: (path: string) => void;
}

function TreeNodeRow({
  node,
  depth,
  isExpanded,
  isSelected,
  isFocused,
  onToggle,
  onSelect,
  onFocus,
}: TreeNodeRowProps) {
  const isDir = node.type === 'directory';
  const rowRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (isFocused) rowRef.current?.focus();
  }, [isFocused]);

  const handleClick = () => {
    onFocus(node.path);
    if (isDir) onToggle(node.path);
    onSelect(node);
  };

  const handleKeyDown = (e: KeyboardEvent) => {
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      handleClick();
    }
  };

  return (
    <div
      ref={rowRef}
      role="treeitem"
      aria-expanded={isDir ? isExpanded : undefined}
      aria-selected={isSelected}
      tabIndex={isFocused ? 0 : -1}
      data-testid={`tree-node-${node.path}`}
      onClick={handleClick}
      onKeyDown={handleKeyDown}
      className={cn(
        'group flex items-center gap-1 py-0.5 pr-2 rounded-sm cursor-pointer',
        'text-xs select-none outline-none',
        'transition-colors duration-100',
        isSelected
          ? 'bg-sidebar-accent text-sidebar-accent-foreground'
          : 'text-sidebar-foreground/70 hover:bg-sidebar-accent/50 hover:text-sidebar-foreground',
        isFocused && !isSelected && 'ring-1 ring-inset ring-primary/40',
      )}
      style={{ paddingLeft: `${(depth * 12) + 4}px` }}
    >
      {/* Expand/collapse chevron (dirs only) */}
      <span className="shrink-0 w-3 h-3 flex items-center justify-center">
        {isDir && (
          isExpanded
            ? <ChevronDown  className="w-3 h-3" />
            : <ChevronRight className="w-3 h-3" />
        )}
      </span>

      {/* Icon */}
      <span className="shrink-0 text-muted-foreground group-hover:text-foreground transition-colors">
        {isDir ? (
          isExpanded
            ? <FolderOpen className="w-3.5 h-3.5 text-amber-400" />
            : <Folder     className="w-3.5 h-3.5 text-amber-400/80" />
        ) : (
          <FileIcon name={node.name} className="w-3.5 h-3.5" />
        )}
      </span>

      {/* Label */}
      <span className="flex-1 truncate min-w-0">{node.name}</span>

      {/* Node count badge */}
      {node.nodeCount > 0 && (
        <span
          className={cn(
            'shrink-0 text-[9px] font-mono px-1 py-px rounded',
            isSelected
              ? 'bg-sidebar-accent-foreground/20 text-sidebar-accent-foreground'
              : 'bg-muted text-muted-foreground',
          )}
          title={`${node.nodeCount} graph node${node.nodeCount !== 1 ? 's' : ''}`}
        >
          {node.nodeCount >= 1000
            ? `${(node.nodeCount / 1000).toFixed(1)}k`
            : node.nodeCount}
        </span>
      )}
    </div>
  );
}

/* ------------------------------------------------------------------ */
/* Recursive tree renderer                                              */
/* ------------------------------------------------------------------ */

interface TreeProps {
  node: FileTreeNode;
  depth?: number;
  expanded: Set<string>;
  selectedPath: string | null;
  focusedPath: string | null;
  onToggle: (path: string) => void;
  onSelect: (node: FileTreeNode) => void;
  onFocus: (path: string) => void;
}

function Tree({
  node,
  depth = 0,
  expanded,
  selectedPath,
  focusedPath,
  onToggle,
  onSelect,
  onFocus,
}: TreeProps) {
  return (
    <>
      <TreeNodeRow
        node={node}
        depth={depth}
        isExpanded={expanded.has(node.path)}
        isSelected={selectedPath === node.path}
        isFocused={focusedPath === node.path}
        onToggle={onToggle}
        onSelect={onSelect}
        onFocus={onFocus}
      />
      {node.type === 'directory' && expanded.has(node.path) && (
        <>
          {(node.children ?? []).map(child => (
            <Tree
              key={child.path}
              node={child}
              depth={depth + 1}
              expanded={expanded}
              selectedPath={selectedPath}
              focusedPath={focusedPath}
              onToggle={onToggle}
              onSelect={onSelect}
              onFocus={onFocus}
            />
          ))}
        </>
      )}
    </>
  );
}

/* ------------------------------------------------------------------ */
/* Density bar (visual indicator for node counts)                       */
/* ------------------------------------------------------------------ */

function DensityBar({ value, max }: { value: number; max: number }) {
  if (max === 0 || value === 0) return null;
  const pct = Math.max(4, Math.round((value / max) * 100));
  return (
    <div className="h-0.5 w-full bg-muted rounded-full overflow-hidden mt-0.5">
      <div
        className="h-full bg-primary/40 rounded-full transition-all"
        style={{ width: `${pct}%` }}
      />
    </div>
  );
}

/* ------------------------------------------------------------------ */
/* Main ProjectFileTree                                                  */
/* ------------------------------------------------------------------ */

export default function ProjectFileTree() {
  const { data: treeResponse, loading, error } = useApi<FileTreeResponse>(() => api.getFileTree(), []);

  const root = useMemo<FileTreeNode | null>(() => {
    if (!treeResponse) return null;
    return {
      name: 'Project',
      path: '/',
      type: 'directory',
      nodeCount: treeResponse.total_files,
      children: treeResponse.tree,
    };
  }, [treeResponse]);
  const { selectedPath, setSelection } = useFileSelection();
  const navigate = useNavigate();

  const [expanded, setExpanded]     = useState<Set<string>>(new Set());
  const [focusedPath, setFocusedPath] = useState<string | null>(null);
  const [search, setSearch]         = useState('');
  const treeRef = useRef<HTMLDivElement>(null);

  // Auto-expand root on first load
  useEffect(() => {
    if (root && root.type === 'directory') {
      setExpanded(new Set([root.path]));
    }
  }, [root]);

  const filteredRoot = useMemo(
    () => (root ? filterTree(root, search) : null),
    [root, search],
  );

  const handleToggle = useCallback((path: string) => {
    setExpanded(prev => {
      const next = new Set(prev);
      next.has(path) ? next.delete(path) : next.add(path);
      return next;
    });
  }, []);

  const handleSelect = useCallback((node: FileTreeNode) => {
    setSelection(node.path, node.type);
    navigate('/graph');
  }, [setSelection, navigate]);

  const handleFocus = useCallback((path: string) => {
    setFocusedPath(path);
  }, []);

  /* Keyboard navigation for the entire tree container */
  const handleTreeKeyDown = useCallback((e: KeyboardEvent<HTMLDivElement>) => {
    if (!filteredRoot) return;
    const visible = collectVisible(filteredRoot, expanded);
    const idx = focusedPath ? visible.indexOf(focusedPath) : -1;

    if (e.key === 'ArrowDown') {
      e.preventDefault();
      const next = visible[Math.min(idx + 1, visible.length - 1)];
      if (next) setFocusedPath(next);
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      const prev = visible[Math.max(idx - 1, 0)];
      if (prev) setFocusedPath(prev);
    } else if (e.key === 'ArrowRight' && focusedPath) {
      e.preventDefault();
      setExpanded(prev => { const n = new Set(prev); n.add(focusedPath); return n; });
    } else if (e.key === 'ArrowLeft' && focusedPath) {
      e.preventDefault();
      setExpanded(prev => { const n = new Set(prev); n.delete(focusedPath); return n; });
    }
  }, [filteredRoot, expanded, focusedPath]);

  /* ── Render states ── */

  if (loading) {
    return (
      <div className="flex items-center justify-center py-4 gap-2 text-muted-foreground text-xs">
        <Loader2 className="w-3 h-3 animate-spin" />
        <span>Loading files…</span>
      </div>
    );
  }

  if (error) {
    return (
      <div className="flex items-center gap-2 px-2 py-2 text-xs text-destructive/80">
        <AlertCircle className="w-3 h-3 shrink-0" />
        <span className="truncate">Failed to load file tree</span>
      </div>
    );
  }

  if (!root) return null;

  return (
    <div className="flex flex-col gap-1 min-h-0">
      {/* Section header + stats */}
      <div className="px-3 pt-3 pb-1">
        <div className="flex items-center justify-between mb-1">
          <p className="text-[10px] font-semibold text-muted-foreground uppercase tracking-wider">
            Project Files
          </p>
          <span className="text-[10px] text-muted-foreground/60 font-mono">
            {(treeResponse!.total_files ?? 0).toLocaleString()} files
          </span>
        </div>
        <DensityBar value={treeResponse!.total_files ?? 0} max={treeResponse!.total_files ?? 0} />
        {treeResponse!.truncated && (
          <p className="text-[9px] text-amber-500/80 mt-0.5">
            Tree truncated — showing partial results
          </p>
        )}
      </div>

      {/* Search filter */}
      <div className="px-2 pb-1">
        <div className="relative">
          <Search className="absolute left-2 top-1/2 -translate-y-1/2 w-3 h-3 text-muted-foreground/50" />
          <input
            type="text"
            value={search}
            onChange={e => setSearch(e.target.value)}
            placeholder="Filter files…"
            aria-label="Filter files"
            className={cn(
              'w-full pl-6 pr-6 py-1 text-xs rounded',
              'bg-muted/50 border border-border/50',
              'text-foreground placeholder:text-muted-foreground/50',
              'focus:outline-none focus:ring-1 focus:ring-primary/30',
            )}
          />
          {search && (
            <button
              onClick={() => setSearch('')}
              aria-label="Clear filter"
              className="absolute right-1.5 top-1/2 -translate-y-1/2 text-muted-foreground/50 hover:text-muted-foreground"
            >
              <X className="w-3 h-3" />
            </button>
          )}
        </div>
      </div>

      {/* Tree */}
      <div
        ref={treeRef}
        role="tree"
        aria-label="Project file tree"
        onKeyDown={handleTreeKeyDown}
        className="overflow-y-auto px-1 pb-2 flex-1 min-h-0"
        style={{ maxHeight: '320px' }}
      >
        {filteredRoot ? (
          <Tree
            node={filteredRoot}
            expanded={expanded}
            selectedPath={selectedPath}
            focusedPath={focusedPath}
            onToggle={handleToggle}
            onSelect={handleSelect}
            onFocus={handleFocus}
          />
        ) : (
          <p className="text-xs text-muted-foreground/50 px-2 py-2 italic">
            No files match "{search}"
          </p>
        )}
      </div>

      {/* Selected file indicator */}
      {selectedPath && (
        <div className="px-3 pb-2">
          <p className="text-[9px] text-muted-foreground/60 truncate" title={selectedPath}>
            <span className="font-medium text-primary/70">Selected:</span> {selectedPath}
          </p>
        </div>
      )}
    </div>
  );
}
