import { useState, useCallback } from 'react';
import { useParams, useNavigate, Link } from 'react-router-dom';
import { useApi } from '@/hooks/useApi';
import { api } from '@/lib/api';
import type { KindEntry, NodeResponse } from '@/types/api';
import { ChevronRight, Home, Eye, ArrowLeft, ArrowRight } from 'lucide-react';
import NodeDetailModal from './NodeDetailModal';

const kindColors: Record<string, string> = {
  class: 'from-brand-500 to-purple-500',
  interface: 'from-cyan-500 to-blue-500',
  method: 'from-emerald-500 to-green-500',
  endpoint: 'from-amber-500 to-orange-500',
  entity: 'from-rose-500 to-pink-500',
  module: 'from-violet-500 to-purple-500',
  function: 'from-teal-500 to-cyan-500',
  database: 'from-yellow-500 to-amber-500',
  config: 'from-slate-400 to-slate-500',
  test: 'from-green-500 to-emerald-500',
  guard: 'from-red-500 to-rose-500',
  middleware: 'from-orange-500 to-red-500',
};

export default function ExplorerView() {
  const { kind } = useParams<{ kind?: string }>();
  const navigate = useNavigate();
  const [selectedNode, setSelectedNode] = useState<string | null>(null);
  const [page, setPage] = useState(0);
  const pageSize = 50;

  if (kind) {
    return (
      <NodesList
        kind={kind}
        page={page}
        pageSize={pageSize}
        onPageChange={setPage}
        onNodeSelect={setSelectedNode}
        selectedNode={selectedNode}
        onCloseDetail={() => setSelectedNode(null)}
      />
    );
  }

  return <KindsGrid />;
}

function KindsGrid() {
  const { data, loading } = useApi(() => api.getKinds(), []);

  if (loading) {
    return (
      <div className="flex items-center justify-center h-96">
        <div className="w-8 h-8 border-2 border-brand-500 border-t-transparent rounded-full animate-spin" />
      </div>
    );
  }

  const kinds: KindEntry[] = data?.kinds || [];

  return (
    <div className="space-y-6 max-w-7xl mx-auto">
      <div>
        <h1 className="text-2xl font-bold gradient-text">Explorer</h1>
        <p className="text-sm text-surface-400 mt-1">Browse nodes by kind</p>
      </div>

      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-4">
        {kinds.map((k, i) => {
          const gradient = kindColors[k.kind] || 'from-brand-500 to-purple-500';
          return (
            <Link
              key={k.kind}
              to={`/explorer/${k.kind}`}
              className={`glass-card-hover p-5 animate-slide-up opacity-0`}
              style={{ animationDelay: `${i * 30}ms`, animationFillMode: 'forwards' }}
            >
              <div className="flex items-start justify-between">
                <div>
                  <h3 className={`text-2xl font-bold bg-gradient-to-r ${gradient} bg-clip-text text-transparent`}>
                    {k.count.toLocaleString()}
                  </h3>
                  <p className="text-sm text-surface-300 mt-1 capitalize">{k.kind}</p>
                </div>
                <ChevronRight className="w-5 h-5 text-surface-600" />
              </div>
            </Link>
          );
        })}
      </div>
    </div>
  );
}

function NodesList({
  kind,
  page,
  pageSize,
  onPageChange,
  onNodeSelect,
  selectedNode,
  onCloseDetail,
}: {
  kind: string;
  page: number;
  pageSize: number;
  onPageChange: (p: number) => void;
  onNodeSelect: (id: string) => void;
  selectedNode: string | null;
  onCloseDetail: () => void;
}) {
  const { data, loading } = useApi(
    () => api.getNodesByKind(kind, pageSize, page * pageSize),
    [kind, page]
  );
  const [filter, setFilter] = useState('');

  const nodes: NodeResponse[] = data?.nodes || [];
  const total = data?.total || 0;
  const totalPages = Math.ceil(total / pageSize);

  const filtered = filter
    ? nodes.filter(n =>
        n.label.toLowerCase().includes(filter.toLowerCase()) ||
        (n.fqn && n.fqn.toLowerCase().includes(filter.toLowerCase())) ||
        (n.file_path && n.file_path.toLowerCase().includes(filter.toLowerCase()))
      )
    : nodes;

  return (
    <div className="space-y-4 max-w-7xl mx-auto">
      {/* Breadcrumb */}
      <nav className="flex items-center gap-2 text-sm">
        <Link to="/explorer" className="text-surface-400 hover:text-surface-200 transition-colors flex items-center gap-1">
          <Home className="w-3.5 h-3.5" /> Explorer
        </Link>
        <ChevronRight className="w-3.5 h-3.5 text-surface-600" />
        <span className="text-surface-200 capitalize">{kind}</span>
        <span className="text-surface-500 ml-1">({total.toLocaleString()})</span>
      </nav>

      {/* Filter */}
      <input
        type="text"
        value={filter}
        onChange={e => setFilter(e.target.value)}
        placeholder="Filter nodes..."
        className="w-full max-w-md px-4 py-2 text-sm rounded-lg
                   bg-surface-900/80 border border-surface-700/50
                   text-surface-200 placeholder:text-surface-500
                   focus:outline-none focus:border-brand-500/50 focus:ring-1 focus:ring-brand-500/20
                   transition-all"
      />

      {loading ? (
        <div className="flex items-center justify-center h-48">
          <div className="w-6 h-6 border-2 border-brand-500 border-t-transparent rounded-full animate-spin" />
        </div>
      ) : (
        <>
          {/* Node cards */}
          <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-3">
            {filtered.map(node => (
              <div key={node.id} className="glass-card-hover p-4">
                <div className="flex items-start justify-between gap-2">
                  <div className="min-w-0 flex-1">
                    <h4 className="text-sm font-medium text-surface-200 truncate">{node.label}</h4>
                    {node.file_path && (
                      <p className="text-xs text-surface-500 font-mono truncate mt-0.5">{node.file_path}</p>
                    )}
                    <div className="flex flex-wrap gap-1.5 mt-2">
                      {node.layer && (
                        <span className="text-[10px] px-2 py-0.5 rounded-full bg-emerald-500/10 text-emerald-400 border border-emerald-500/20">
                          {node.layer}
                        </span>
                      )}
                      {node.annotations?.slice(0, 3).map(a => (
                        <span key={a} className="text-[10px] px-2 py-0.5 rounded-full bg-amber-500/10 text-amber-400 border border-amber-500/20 font-mono">
                          @{a}
                        </span>
                      ))}
                    </div>
                  </div>
                  <button
                    onClick={() => onNodeSelect(node.id)}
                    className="p-1.5 rounded-lg text-surface-400 hover:text-brand-400 hover:bg-brand-500/10 transition-colors flex-shrink-0"
                    title="View details"
                  >
                    <Eye className="w-4 h-4" />
                  </button>
                </div>
              </div>
            ))}
          </div>

          {/* Pagination */}
          {totalPages > 1 && (
            <div className="flex items-center justify-center gap-2 py-4">
              <button
                onClick={() => onPageChange(page - 1)}
                disabled={page === 0}
                className="p-2 rounded-lg text-surface-400 hover:text-surface-200 hover:bg-surface-800/50 disabled:opacity-30 disabled:cursor-not-allowed transition-colors"
              >
                <ArrowLeft className="w-4 h-4" />
              </button>
              <span className="text-sm text-surface-400">
                Page {page + 1} of {totalPages}
              </span>
              <button
                onClick={() => onPageChange(page + 1)}
                disabled={page >= totalPages - 1}
                className="p-2 rounded-lg text-surface-400 hover:text-surface-200 hover:bg-surface-800/50 disabled:opacity-30 disabled:cursor-not-allowed transition-colors"
              >
                <ArrowRight className="w-4 h-4" />
              </button>
            </div>
          )}
        </>
      )}

      <NodeDetailModal nodeId={selectedNode} onClose={onCloseDetail} />
    </div>
  );
}
