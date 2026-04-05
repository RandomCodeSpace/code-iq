import { useMemo } from 'react';
import { Card, Col, Row, Statistic, Tag, Spin, Alert, Typography } from 'antd';
import {
  NodeIndexOutlined,
  BranchesOutlined,
  FileOutlined,
  CodeOutlined,
} from '@ant-design/icons';
import ReactECharts from 'echarts-for-react';
import { useApi } from '@/hooks/useApi';
import { api } from '@/lib/api';
import { useTheme } from '@/context/ThemeContext';
import type { StatsResponse } from '@/types/api';

const LANG_COLORS: Record<string, string> = {
  java: '#b07219', python: '#3572A5', typescript: '#3178c6', javascript: '#f1e05a',
  go: '#00ADD8', csharp: '#178600', rust: '#dea584', kotlin: '#A97BFF',
  yaml: '#cb171e', json: '#292929', ruby: '#701516', scala: '#c22d40',
  cpp: '#f34b7d', shell: '#89e051', markdown: '#083fa1', html: '#e34c26',
  css: '#563d7c', sql: '#e38c00', proto: '#60a0b0', dockerfile: '#384d54',
};

function isComputedStats(s: StatsResponse): s is StatsResponse & { graph: { nodes: number; edges: number; files: number }; languages: Record<string, number>; frameworks: Record<string, number> } {
  return 'graph' in s;
}

export default function Dashboard() {
  const { isDark } = useTheme();
  const { data: stats, loading: statsLoading, error: statsError } = useApi(() => api.getStats(), []);
  const { data: kinds, loading: kindsLoading } = useApi(() => api.getKinds(), []);
  const { data: detailed } = useApi(() => api.getDetailedStats('all'), []);

  const computed = stats && isComputedStats(stats) ? stats : null;
  const queryStats = stats && !isComputedStats(stats) ? stats as { node_count: number; edge_count: number; nodes_by_kind: Record<string, number> } : null;

  const nodeCount = computed?.graph?.nodes ?? queryStats?.node_count ?? 0;
  const edgeCount = computed?.graph?.edges ?? queryStats?.edge_count ?? 0;
  const fileCount = computed?.graph?.files ?? 0;
  const languages = computed?.languages ?? {};
  const langCount = Object.keys(languages).length;
  const frameworks = computed?.frameworks ?? {};

  // Language bar chart
  const langChartOption = useMemo(() => {
    const entries = Object.entries(languages).sort((a, b) => b[1] - a[1]);
    return {
      tooltip: { trigger: 'axis' as const },
      xAxis: { type: 'category' as const, data: entries.map(([k]) => k), axisLabel: { rotate: 30 } },
      yAxis: { type: 'value' as const },
      series: [{
        type: 'bar',
        data: entries.map(([k, v]) => ({
          value: v,
          itemStyle: { color: LANG_COLORS[k.toLowerCase()] || '#1677ff' },
        })),
      }],
      grid: { left: 50, right: 20, top: 20, bottom: 60 },
    };
  }, [languages]);

  // Node kind bar chart
  const kindChartOption = useMemo(() => {
    if (!kinds) return null;
    const sorted = [...kinds.kinds].sort((a, b) => b.count - a.count).slice(0, 15);
    return {
      tooltip: { trigger: 'axis' as const },
      xAxis: { type: 'value' as const },
      yAxis: { type: 'category' as const, data: sorted.map(k => k.kind).reverse(), axisLabel: { width: 100 } },
      series: [{
        type: 'bar',
        data: sorted.map(k => k.count).reverse(),
        itemStyle: { color: '#1677ff' },
      }],
      grid: { left: 120, right: 20, top: 10, bottom: 20 },
    };
  }, [kinds]);

  // File type pie chart from detailed stats
  const fileTypePieOption = useMemo(() => {
    if (!detailed) return null;
    const arch = detailed as Record<string, unknown>;
    const fileTypes = (arch.file_types ?? arch.architecture) as Record<string, number> | undefined;
    if (!fileTypes || typeof fileTypes !== 'object') return null;
    const data = Object.entries(fileTypes)
      .filter(([, v]) => typeof v === 'number' && v > 0)
      .map(([name, value]) => ({ name, value }));
    if (data.length === 0) return null;
    return {
      tooltip: { trigger: 'item' as const },
      series: [{
        type: 'pie',
        radius: ['40%', '70%'],
        data,
        label: { show: true },
        emphasis: { itemStyle: { shadowBlur: 10, shadowOffsetX: 0, shadowColor: 'rgba(0,0,0,0.5)' } },
      }],
    };
  }, [detailed]);

  if (statsLoading || kindsLoading) {
    return <div style={{ textAlign: 'center', padding: 80 }}><Spin size="large" /></div>;
  }

  if (statsError) {
    return <Alert type="error" message="Failed to load stats" description={statsError} showIcon style={{ margin: 24 }} />;
  }

  return (
    <div>
      <Typography.Title level={3} style={{ marginBottom: 24 }}>Dashboard</Typography.Title>

      {/* Top stats row */}
      <Row gutter={[16, 16]}>
        <Col xs={12} sm={6}>
          <Card>
            <Statistic title="Total Nodes" value={nodeCount} prefix={<NodeIndexOutlined />} />
          </Card>
        </Col>
        <Col xs={12} sm={6}>
          <Card>
            <Statistic title="Total Edges" value={edgeCount} prefix={<BranchesOutlined />} />
          </Card>
        </Col>
        <Col xs={12} sm={6}>
          <Card>
            <Statistic title="Files Analyzed" value={fileCount} prefix={<FileOutlined />} />
          </Card>
        </Col>
        <Col xs={12} sm={6}>
          <Card>
            <Statistic title="Languages" value={langCount} prefix={<CodeOutlined />} />
          </Card>
        </Col>
      </Row>

      {/* Language distribution */}
      {Object.keys(languages).length > 0 && (
        <Card title="Language Distribution" style={{ marginTop: 16 }}>
          <ReactECharts option={langChartOption} style={{ height: 280 }} theme={isDark ? 'dark' : undefined} />
        </Card>
      )}

      {/* Node kind breakdown */}
      {kindChartOption && (
        <Card title="Node Kind Breakdown" style={{ marginTop: 16 }}>
          <ReactECharts option={kindChartOption} style={{ height: Math.max(300, (kinds?.kinds?.length ?? 10) * 22) }} theme={isDark ? 'dark' : undefined} />
        </Card>
      )}

      {/* Frameworks */}
      {Object.keys(frameworks).length > 0 && (
        <Card title="Detected Frameworks" style={{ marginTop: 16 }}>
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
            {Object.entries(frameworks)
              .sort((a, b) => b[1] - a[1])
              .map(([name, count]) => (
                <Tag key={name} color="blue">{name} ({count})</Tag>
              ))}
          </div>
        </Card>
      )}

      {/* File type pie */}
      {fileTypePieOption && (
        <Card title="File Type Distribution" style={{ marginTop: 16 }}>
          <ReactECharts option={fileTypePieOption} style={{ height: 300 }} theme={isDark ? 'dark' : undefined} />
        </Card>
      )}
    </div>
  );
}
