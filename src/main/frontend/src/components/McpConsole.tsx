import { useState, useCallback, useRef, useEffect } from 'react';
import { Play, Trash2, Clock, ChevronDown } from 'lucide-react';
import Editor, { type OnMount } from '@monaco-editor/react';

const TOOLS = [
  { name: 'GET /api/stats', url: '/api/stats', method: 'GET', desc: 'Graph statistics' },
  { name: 'GET /api/stats/detailed', url: '/api/stats/detailed?category=all', method: 'GET', desc: 'Detailed stats by category' },
  { name: 'GET /api/kinds', url: '/api/kinds', method: 'GET', desc: 'List all node kinds' },
  { name: 'GET /api/kinds/{kind}', url: '/api/kinds/', method: 'GET', desc: 'Nodes of a specific kind', param: 'kind' },
  { name: 'GET /api/nodes', url: '/api/nodes?limit=20', method: 'GET', desc: 'List nodes (paginated)' },
  { name: 'GET /api/nodes/find', url: '/api/nodes/find?q=', method: 'GET', desc: 'Find nodes by name', param: 'q' },
  { name: 'GET /api/edges', url: '/api/edges?limit=20', method: 'GET', desc: 'List edges (paginated)' },
  { name: 'GET /api/topology', url: '/api/topology', method: 'GET', desc: 'Service topology' },
  { name: 'GET /api/topology/bottlenecks', url: '/api/topology/bottlenecks', method: 'GET', desc: 'Find bottleneck services' },
  { name: 'GET /api/topology/circular', url: '/api/topology/circular', method: 'GET', desc: 'Find circular dependencies' },
  { name: 'GET /api/topology/dead', url: '/api/topology/dead', method: 'GET', desc: 'Find dead services' },
  { name: 'GET /api/flow', url: '/api/flow', method: 'GET', desc: 'All flow diagrams' },
  { name: 'GET /api/flow/{view}', url: '/api/flow/overview?format=json', method: 'GET', desc: 'Specific flow view' },
  { name: 'GET /api/search', url: '/api/search?q=', method: 'GET', desc: 'Search graph', param: 'q' },
  { name: 'GET /api/query/cycles', url: '/api/query/cycles', method: 'GET', desc: 'Find cycles' },
  { name: 'GET /api/triage/component', url: '/api/triage/component?file=', method: 'GET', desc: 'Find component by file', param: 'file' },
  { name: 'POST /api/analyze', url: '/api/analyze', method: 'POST', desc: 'Trigger analysis' },
];

interface HistoryEntry {
  url: string;
  method: string;
  timestamp: number;
  status: number;
  duration: number;
}

export default function McpConsole() {
  const [url, setUrl] = useState('/api/stats');
  const [method, setMethod] = useState('GET');
  const [body, setBody] = useState('');
  const [response, setResponse] = useState<string>('// Select an API endpoint and click Execute');
  const [status, setStatus] = useState<number | null>(null);
  const [duration, setDuration] = useState<number | null>(null);
  const [executing, setExecuting] = useState(false);
  const [showToolList, setShowToolList] = useState(false);
  const [history, setHistory] = useState<HistoryEntry[]>(() => {
    try {
      return JSON.parse(localStorage.getItem('codeiq-console-history') || '[]');
    } catch { return []; }
  });
  const editorRef = useRef<Parameters<OnMount>[0] | null>(null);

  const execute = useCallback(async () => {
    setExecuting(true);
    const start = performance.now();
    try {
      const opts: RequestInit = { method };
      if (method === 'POST' && body.trim()) {
        opts.headers = { 'Content-Type': 'application/json' };
        opts.body = body;
      }

      const res = await fetch(url, opts);
      const elapsed = Math.round(performance.now() - start);
      setStatus(res.status);
      setDuration(elapsed);

      const contentType = res.headers.get('content-type') || '';
      let text: string;
      if (contentType.includes('json')) {
        const json = await res.json();
        text = JSON.stringify(json, null, 2);
      } else {
        text = await res.text();
      }
      setResponse(text);

      const entry: HistoryEntry = { url, method, timestamp: Date.now(), status: res.status, duration: elapsed };
      const newHistory = [entry, ...history.slice(0, 49)];
      setHistory(newHistory);
      localStorage.setItem('codeiq-console-history', JSON.stringify(newHistory));
    } catch (err) {
      const elapsed = Math.round(performance.now() - start);
      setStatus(0);
      setDuration(elapsed);
      setResponse(`// Error: ${err instanceof Error ? err.message : String(err)}`);
    } finally {
      setExecuting(false);
    }
  }, [url, method, body, history]);

  const selectTool = (tool: typeof TOOLS[0]) => {
    setUrl(tool.url);
    setMethod(tool.method);
    setShowToolList(false);
    if (tool.method === 'POST') {
      setBody('{}');
    }
  };

  const handleEditorMount: OnMount = (editor) => {
    editorRef.current = editor;
  };

  return (
    <div className="space-y-4 max-w-7xl mx-auto">
      <div>
        <h1 className="text-2xl font-bold gradient-text">API Console</h1>
        <p className="text-sm text-surface-400 mt-1">Test REST API endpoints interactively</p>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-4 gap-4" style={{ height: 'calc(100vh - 200px)' }}>
        {/* Left: Tool list */}
        <div className="lg:col-span-1 glass-card overflow-hidden flex flex-col">
          <div className="px-4 py-3 border-b border-surface-800/50">
            <h3 className="text-xs font-medium text-surface-400 uppercase tracking-wider">API Endpoints</h3>
          </div>
          <div className="flex-1 overflow-y-auto">
            {TOOLS.map((tool, i) => (
              <button
                key={i}
                onClick={() => selectTool(tool)}
                className={`w-full text-left px-4 py-2.5 border-b border-surface-800/20 hover:bg-surface-800/50 transition-colors ${
                  url === tool.url ? 'bg-brand-500/10 border-l-2 border-l-brand-500' : ''
                }`}
              >
                <div className="flex items-center gap-2">
                  <span className={`text-[10px] font-mono font-bold px-1.5 py-0.5 rounded ${
                    tool.method === 'GET' ? 'bg-emerald-500/10 text-emerald-400' : 'bg-amber-500/10 text-amber-400'
                  }`}>
                    {tool.method}
                  </span>
                  <span className="text-xs text-surface-300 truncate">{tool.name.replace(`${tool.method} `, '')}</span>
                </div>
                <p className="text-[10px] text-surface-500 mt-0.5 pl-8">{tool.desc}</p>
              </button>
            ))}
          </div>
        </div>

        {/* Right: Request + Response */}
        <div className="lg:col-span-3 flex flex-col gap-4">
          {/* URL bar */}
          <div className="glass-card p-3">
            <div className="flex gap-2">
              <select
                value={method}
                onChange={e => setMethod(e.target.value)}
                className="px-3 py-2 rounded-lg bg-surface-800 border border-surface-700/50 text-sm font-mono text-surface-200 focus:outline-none focus:border-brand-500/50"
              >
                <option value="GET">GET</option>
                <option value="POST">POST</option>
              </select>
              <input
                type="text"
                value={url}
                onChange={e => setUrl(e.target.value)}
                onKeyDown={e => e.key === 'Enter' && execute()}
                className="flex-1 px-4 py-2 rounded-lg bg-surface-800 border border-surface-700/50 text-sm font-mono text-surface-200 focus:outline-none focus:border-brand-500/50 focus:ring-1 focus:ring-brand-500/20"
                placeholder="/api/..."
              />
              <button
                onClick={execute}
                disabled={executing}
                className="px-4 py-2 rounded-lg bg-brand-600 hover:bg-brand-500 text-white text-sm font-medium flex items-center gap-2 transition-colors disabled:opacity-50"
              >
                {executing ? (
                  <div className="w-4 h-4 border-2 border-white border-t-transparent rounded-full animate-spin" />
                ) : (
                  <Play className="w-4 h-4" />
                )}
                Execute
              </button>
            </div>
          </div>

          {/* Request body (for POST) */}
          {method === 'POST' && (
            <div className="glass-card overflow-hidden" style={{ height: '120px' }}>
              <div className="px-4 py-2 border-b border-surface-800/50 flex items-center justify-between">
                <span className="text-xs font-medium text-surface-400">Request Body</span>
              </div>
              <Editor
                height="90px"
                defaultLanguage="json"
                value={body}
                onChange={v => setBody(v || '')}
                theme="vs-dark"
                options={{
                  minimap: { enabled: false },
                  lineNumbers: 'off',
                  scrollBeyondLastLine: false,
                  fontSize: 12,
                  fontFamily: 'JetBrains Mono, monospace',
                  padding: { top: 8 },
                  renderLineHighlight: 'none',
                }}
              />
            </div>
          )}

          {/* Response */}
          <div className="glass-card overflow-hidden flex-1 flex flex-col">
            <div className="px-4 py-2 border-b border-surface-800/50 flex items-center justify-between">
              <div className="flex items-center gap-3">
                <span className="text-xs font-medium text-surface-400">Response</span>
                {status !== null && (
                  <span className={`text-xs font-mono px-2 py-0.5 rounded ${
                    status >= 200 && status < 300
                      ? 'bg-emerald-500/10 text-emerald-400'
                      : status >= 400
                        ? 'bg-red-500/10 text-red-400'
                        : 'bg-amber-500/10 text-amber-400'
                  }`}>
                    {status}
                  </span>
                )}
                {duration !== null && (
                  <span className="text-xs text-surface-500 font-mono flex items-center gap-1">
                    <Clock className="w-3 h-3" /> {duration}ms
                  </span>
                )}
              </div>
            </div>
            <div className="flex-1 min-h-0">
              <Editor
                height="100%"
                defaultLanguage="json"
                value={response}
                onMount={handleEditorMount}
                theme="vs-dark"
                options={{
                  readOnly: true,
                  minimap: { enabled: false },
                  lineNumbers: 'on',
                  scrollBeyondLastLine: false,
                  fontSize: 12,
                  fontFamily: 'JetBrains Mono, monospace',
                  padding: { top: 8 },
                  wordWrap: 'on',
                  renderLineHighlight: 'none',
                }}
              />
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
