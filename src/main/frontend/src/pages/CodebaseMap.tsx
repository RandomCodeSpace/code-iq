import { useState, useMemo } from 'react';
import { Card, Select, Typography, Space, Spin, Alert } from 'antd';
import ReactECharts from 'echarts-for-react';
import { useApi } from '@/hooks/useApi';
import { api } from '@/lib/api';
import { useTheme } from '@/context/ThemeContext';
import type { NodesListResponse } from '@/types/api';

const LANG_COLORS: Record<string, string> = {
  java: '#b07219', python: '#3572A5', typescript: '#3178c6', javascript: '#f1e05a',
  go: '#00ADD8', csharp: '#178600', rust: '#dea584', kotlin: '#A97BFF',
  yaml: '#cb171e', json: '#292929', ruby: '#701516', scala: '#c22d40',
  cpp: '#f34b7d', shell: '#89e051', markdown: '#083fa1', html: '#e34c26',
  css: '#563d7c', sql: '#e38c00', proto: '#60a0b0', dockerfile: '#384d54',
};

interface TreeNode {
  name: string;
  path: string;
  value: number;
  children?: TreeNode[];
  itemStyle?: { color: string };
}

// Aggregate by top-level directories for treemap visualization
function buildSimpleTreemap(nodes: Array<{ file_path?: string; label: string; properties?: Record<string, unknown> }>): TreeNode[] {
  const dirMap: Record<string, { count: number; langs: Record<string, number> }> = {};

  for (const node of nodes) {
    const fp = node.file_path;
    if (!fp) continue;

    const parts = fp.split('/');
    // Use first 2 directory levels as group
    const dir = parts.length > 2 ? parts.slice(0, 2).join('/') : parts[0] ?? 'root';

    if (!dirMap[dir]) dirMap[dir] = { count: 0, langs: {} };
    dirMap[dir].count++;

    const ext = fp.split('.').pop()?.toLowerCase() ?? '';
    const langMap: Record<string, string> = {
      java: 'java', py: 'python', ts: 'typescript', tsx: 'typescript',
      js: 'javascript', jsx: 'javascript', go: 'go', cs: 'csharp',
      rs: 'rust', kt: 'kotlin', yml: 'yaml', yaml: 'yaml',
      json: 'json', rb: 'ruby', scala: 'scala', cpp: 'cpp',
      h: 'cpp', sh: 'shell', md: 'markdown', html: 'html',
      css: 'css', sql: 'sql', proto: 'proto',
    };
    const lang = langMap[ext] ?? 'other';
    dirMap[dir].langs[lang] = (dirMap[dir].langs[lang] ?? 0) + 1;
  }

  return Object.entries(dirMap)
    .sort((a, b) => b[1].count - a[1].count)
    .map(([name, data]) => {
      // Dominant language determines color
      const dominantLang = Object.entries(data.langs).sort((a, b) => b[1] - a[1])[0]?.[0] ?? '';
      return {
        name,
        path: name,
        value: data.count,
        itemStyle: { color: LANG_COLORS[dominantLang] || '#1677ff' },
      };
    });
}

export default function CodebaseMap() {
  const { isDark } = useTheme();
  const [langFilter, setLangFilter] = useState<string | undefined>(undefined);

  // Fetch module nodes to get file paths
  const { data: moduleData, loading: modLoading, error: modError } = useApi<NodesListResponse>(
    () => api.getNodes('module', undefined, 10000, 0), []
  );

  // Also fetch all nodes for a broader view if modules are sparse
  const { data: allNodesData, loading: allLoading } = useApi<NodesListResponse>(
    () => api.getNodes(undefined, undefined, 10000, 0), []
  );

  const nodes = useMemo(() => {
    const mods = moduleData?.nodes ?? [];
    const all = allNodesData?.nodes ?? [];
    // Prefer whichever has more file paths
    const modsWithPaths = mods.filter(n => n.file_path);
    const allWithPaths = all.filter(n => n.file_path);
    return allWithPaths.length > modsWithPaths.length ? allWithPaths : modsWithPaths;
  }, [moduleData, allNodesData]);

  // Extract unique languages
  const uniqueLangs = useMemo(() => {
    const langs = new Set<string>();
    for (const n of nodes) {
      const ext = n.file_path?.split('.').pop()?.toLowerCase() ?? '';
      const langMap: Record<string, string> = {
        java: 'Java', py: 'Python', ts: 'TypeScript', tsx: 'TypeScript',
        js: 'JavaScript', jsx: 'JavaScript', go: 'Go', cs: 'C#',
        rs: 'Rust', kt: 'Kotlin', yml: 'YAML', yaml: 'YAML',
        json: 'JSON', rb: 'Ruby', scala: 'Scala', cpp: 'C++',
        sh: 'Shell', md: 'Markdown', html: 'HTML', css: 'CSS',
      };
      if (langMap[ext]) langs.add(langMap[ext]);
    }
    return Array.from(langs).sort();
  }, [nodes]);

  // Filter nodes
  const filteredNodes = useMemo(() => {
    if (!langFilter) return nodes;
    const extMap: Record<string, string[]> = {
      Java: ['java'], Python: ['py'], TypeScript: ['ts', 'tsx'], JavaScript: ['js', 'jsx'],
      Go: ['go'], 'C#': ['cs'], Rust: ['rs'], Kotlin: ['kt'],
      YAML: ['yml', 'yaml'], JSON: ['json'], Ruby: ['rb'], Scala: ['scala'],
      'C++': ['cpp', 'h'], Shell: ['sh'], Markdown: ['md'], HTML: ['html'], CSS: ['css'],
    };
    const exts = extMap[langFilter] ?? [];
    return nodes.filter(n => {
      const ext = n.file_path?.split('.').pop()?.toLowerCase() ?? '';
      return exts.includes(ext);
    });
  }, [nodes, langFilter]);

  const treemapData = useMemo(() => buildSimpleTreemap(filteredNodes), [filteredNodes]);

  const chartOption = useMemo(() => ({
    tooltip: {
      formatter: (info: { name: string; value: number }) =>
        `<b>${info.name}</b><br/>Nodes: ${info.value}`,
    },
    series: [{
      type: 'treemap',
      data: treemapData,
      roam: false,
      leafDepth: 1,
      breadcrumb: { show: true },
      levels: [
        {
          itemStyle: { borderColor: isDark ? '#333' : '#ddd', borderWidth: 2, gapWidth: 2 },
          upperLabel: { show: true, height: 30, color: isDark ? '#eee' : '#333' },
        },
        {
          itemStyle: { borderColor: isDark ? '#555' : '#ccc', borderWidth: 1, gapWidth: 1 },
          upperLabel: { show: true, height: 20 },
        },
      ],
      label: {
        show: true,
        formatter: '{b}',
        fontSize: 12,
      },
    }],
  }), [treemapData, isDark]);

  const loading = modLoading || allLoading;

  if (loading) {
    return <div style={{ textAlign: 'center', padding: 80 }}><Spin size="large" /></div>;
  }

  if (modError) {
    return <Alert type="error" message="Failed to load codebase data" description={modError} showIcon style={{ margin: 24 }} />;
  }

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
        <div>
          <Typography.Title level={3} style={{ margin: 0 }}>Codebase Map</Typography.Title>
          <Typography.Text type="secondary">
            {filteredNodes.length.toLocaleString()} files across {uniqueLangs.length} languages
          </Typography.Text>
        </div>
        <Space>
          <Select
            allowClear
            placeholder="Filter by language"
            style={{ width: 180 }}
            value={langFilter}
            onChange={setLangFilter}
            options={uniqueLangs.map(l => ({ label: l, value: l }))}
          />
        </Space>
      </div>

      <Card>
        {treemapData.length > 0 ? (
          <ReactECharts
            option={chartOption}
            style={{ height: 'calc(100vh - 260px)', minHeight: 400 }}
            theme={isDark ? 'dark' : undefined}
          />
        ) : (
          <div style={{ textAlign: 'center', padding: 60 }}>
            <Typography.Text type="secondary">No file data available. Run index + enrich first.</Typography.Text>
          </div>
        )}
      </Card>
    </div>
  );
}
