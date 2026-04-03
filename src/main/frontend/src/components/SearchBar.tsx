import { useState, useCallback, useRef, useEffect } from 'react';
import { Search, X, Loader2 } from 'lucide-react';
import { api } from '@/lib/api';
import type { SearchResult } from '@/types/api';
import { useNavigate } from 'react-router-dom';

export default function SearchBar() {
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<SearchResult[]>([]);
  const [open, setOpen] = useState(false);
  const [loading, setLoading] = useState(false);
  const [activeIndex, setActiveIndex] = useState(-1);
  const activeIndexRef = useRef(-1);
  const timerRef = useRef<ReturnType<typeof setTimeout>>();
  const wrapRef = useRef<HTMLDivElement>(null);
  const navigate = useNavigate();

  const doSearch = useCallback(async (q: string) => {
    if (q.length < 2) {
      setResults([]);
      setOpen(false);
      setLoading(false);
      return;
    }
    setLoading(true);
    try {
      const data = await api.search(q, 20);
      setResults(data);
    } catch {
      setResults([]);
    } finally {
      setLoading(false);
    }
  }, []);

  const onChange = (val: string) => {
    setQuery(val);
    activeIndexRef.current = -1;
    setActiveIndex(-1);
    clearTimeout(timerRef.current);
    if (val.length >= 2) {
      setLoading(true);
      setOpen(true);
    } else {
      setOpen(false);
      setLoading(false);
      setResults([]);
    }
    timerRef.current = setTimeout(() => doSearch(val), 300);
  };

  // Cancel pending debounce timer on unmount to prevent state updates after unmount
  useEffect(() => () => clearTimeout(timerRef.current), []);

  useEffect(() => {
    const handler = (e: MouseEvent) => {
      if (wrapRef.current && !wrapRef.current.contains(e.target as Node)) {
        setOpen(false);
      }
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, []);

  const selectResult = (_r: SearchResult) => {
    setOpen(false);
    setQuery('');
    setResults([]);
    navigate('/explorer');
  };

  const onKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Escape') {
      setOpen(false);
      return;
    }
    if (!open || results.length === 0) return;
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      const next = Math.min(activeIndexRef.current + 1, results.length - 1);
      activeIndexRef.current = next;
      setActiveIndex(next);
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      const prev = Math.max(activeIndexRef.current - 1, 0);
      activeIndexRef.current = prev;
      setActiveIndex(prev);
    } else if (e.key === 'Enter') {
      e.preventDefault();
      const idx = activeIndexRef.current;
      const target = idx >= 0 ? results[idx] : results[0];
      if (target) selectResult(target);
    }
  };

  return (
    <div ref={wrapRef} className="relative w-full max-w-md">
      <div className="relative">
        <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-surface-500" />
        <input
          type="search"
          role="searchbox"
          value={query}
          onChange={e => onChange(e.target.value)}
          onKeyDown={onKeyDown}
          onFocus={() => results.length > 0 && setOpen(true)}
          placeholder="Search nodes, kinds, files..."
          className="w-full pl-10 pr-8 py-2 text-sm rounded-lg
                     bg-surface-900/80 border border-surface-700/50
                     text-surface-200 placeholder:text-surface-500
                     focus:outline-none focus:border-brand-500/50 focus:ring-1 focus:ring-brand-500/20
                     transition-all"
        />
        {query && (
          <button
            onClick={() => { setQuery(''); setResults([]); setOpen(false); setLoading(false); }}
            className="absolute right-2 top-1/2 -translate-y-1/2 text-surface-500 hover:text-surface-300"
            aria-label="Clear search"
          >
            <X className="w-4 h-4" />
          </button>
        )}
      </div>

      {open && loading && (
        <div
          data-testid="search-spinner"
          className="absolute top-full mt-1 w-full z-50 glass-card flex items-center justify-center gap-2 p-4 text-surface-400 text-sm"
        >
          <Loader2 className="w-4 h-4 animate-spin" />
          Searching...
        </div>
      )}

      {open && !loading && (
        <div
          data-testid="search-dropdown"
          id="search-listbox"
          role="listbox"
          className="absolute top-full mt-1 w-full z-50 glass-card max-h-80 overflow-y-auto"
        >
          {results.length === 0 && (
            <div className="p-4 text-center text-surface-400 text-sm">No results found</div>
          )}

          {results.map((r, i) => (
            <button
              key={r.id}
              role="option"
              aria-selected={i === activeIndex}
              onClick={() => selectResult(r)}
              className={[
                'w-full flex items-center gap-3 px-4 py-2.5 text-left',
                'hover:bg-surface-800/50 transition-colors text-sm border-b border-surface-800/30 last:border-0',
                i === activeIndex ? 'bg-surface-800/50' : '',
              ].join(' ')}
            >
              <span className="inline-flex items-center px-2 py-0.5 rounded text-xs font-medium
                               bg-brand-500/10 text-brand-400 border border-brand-500/20">
                {r.kind}
              </span>
              <span className="text-surface-200 truncate flex-1">{r.name ?? r.label}</span>
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
