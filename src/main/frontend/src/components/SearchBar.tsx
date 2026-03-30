import { useState, useCallback, useRef, useEffect } from 'react';
import { Search, X } from 'lucide-react';
import { api } from '@/lib/api';
import type { SearchResult } from '@/types/api';
import { useNavigate } from 'react-router-dom';

export default function SearchBar() {
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<SearchResult[]>([]);
  const [open, setOpen] = useState(false);
  const [loading, setLoading] = useState(false);
  const timerRef = useRef<ReturnType<typeof setTimeout>>();
  const wrapRef = useRef<HTMLDivElement>(null);
  const navigate = useNavigate();

  const doSearch = useCallback(async (q: string) => {
    if (q.length < 2) {
      setResults([]);
      return;
    }
    setLoading(true);
    try {
      const data = await api.search(q, 20);
      setResults(data);
      setOpen(true);
    } catch {
      setResults([]);
    } finally {
      setLoading(false);
    }
  }, []);

  const onChange = (val: string) => {
    setQuery(val);
    clearTimeout(timerRef.current);
    timerRef.current = setTimeout(() => doSearch(val), 300);
  };

  useEffect(() => {
    const handler = (e: MouseEvent) => {
      if (wrapRef.current && !wrapRef.current.contains(e.target as Node)) {
        setOpen(false);
      }
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, []);

  const selectResult = (r: SearchResult) => {
    setOpen(false);
    setQuery('');
    navigate(`/explorer/${r.kind}`);
  };

  return (
    <div ref={wrapRef} className="relative w-full max-w-md">
      <div className="relative">
        <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-surface-500" />
        <input
          type="text"
          value={query}
          onChange={e => onChange(e.target.value)}
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
            onClick={() => { setQuery(''); setResults([]); setOpen(false); }}
            className="absolute right-2 top-1/2 -translate-y-1/2 text-surface-500 hover:text-surface-300"
          >
            <X className="w-4 h-4" />
          </button>
        )}
      </div>

      {open && results.length > 0 && (
        <div className="absolute top-full mt-1 w-full z-50 glass-card max-h-80 overflow-y-auto">
          {results.map((r, i) => (
            <button
              key={r.id || i}
              onClick={() => selectResult(r)}
              className="w-full flex items-center gap-3 px-4 py-2.5 text-left
                         hover:bg-surface-800/50 transition-colors text-sm border-b border-surface-800/30 last:border-0"
            >
              <span className="inline-flex items-center px-2 py-0.5 rounded text-xs font-medium
                               bg-brand-500/10 text-brand-400 border border-brand-500/20">
                {r.kind}
              </span>
              <span className="text-surface-200 truncate flex-1">{r.label}</span>
              {r.file_path && (
                <span className="text-surface-500 text-xs truncate max-w-[200px]">{r.file_path}</span>
              )}
            </button>
          ))}
        </div>
      )}

      {open && loading && (
        <div className="absolute top-full mt-1 w-full z-50 glass-card p-4 text-center text-surface-400 text-sm">
          Searching...
        </div>
      )}
    </div>
  );
}
