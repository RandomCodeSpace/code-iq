import { useState } from 'react';
import { Card, Col, Row, Statistic, Tag, Spin, Alert, Typography, Modal, Table } from 'antd';
import {
  NodeIndexOutlined,
  BranchesOutlined,
  FileOutlined,
  CodeOutlined,
  ApiOutlined,
  SafetyOutlined,
  AppstoreOutlined,
  BuildOutlined,
} from '@ant-design/icons';
import { useApi } from '@/hooks/useApi';
import { api } from '@/lib/api';
import type { StatsResponse } from '@/types/api';

const TAG_COLORS = [
  'magenta', 'red', 'volcano', 'orange', 'gold',
  'lime', 'green', 'cyan', 'blue', 'geekblue', 'purple',
];

function hashCode(str: string): number {
  let hash = 0;
  for (let i = 0; i < str.length; i++) {
    hash = ((hash << 5) - hash) + str.charCodeAt(i);
    hash |= 0;
  }
  return hash;
}

/** Flatten a value into a Record<string, number>. Handles nested objects, numbers, arrays. */
function flattenToRecord(val: unknown): Record<string, number> {
  if (!val || typeof val !== 'object') return {};
  const result: Record<string, number> = {};
  for (const [k, v] of Object.entries(val as Record<string, unknown>)) {
    if (typeof v === 'number') {
      result[k] = v;
    } else if (typeof v === 'object' && v !== null && !Array.isArray(v)) {
      // Nested object — flatten one level
      for (const [k2, v2] of Object.entries(v as Record<string, unknown>)) {
        if (typeof v2 === 'number') {
          result[`${k}/${k2}`] = v2;
        }
      }
    }
  }
  return result;
}

function sumValues(rec: Record<string, number>): number {
  return Object.values(rec).reduce((a, b) => a + b, 0);
}

interface StatCardProps {
  title: string;
  value: number | string;
  icon: React.ReactNode;
  detail?: Record<string, number>;
  detailTitle?: string;
}

function StatCard({ title, value, icon, detail, detailTitle }: StatCardProps) {
  const [open, setOpen] = useState(false);

  // Only allow click if detail has entries and total > 0
  const hasDetail = detail && Object.keys(detail).length > 0 && sumValues(detail) > 0;

  const tableData = hasDetail
    ? Object.entries(detail!)
        .sort((a, b) => b[1] - a[1])
        .map(([name, count]) => ({ key: name, name, count }))
    : [];

  return (
    <>
      <Card
        hoverable={!!hasDetail}
        onClick={() => hasDetail && setOpen(true)}
        style={{ cursor: hasDetail ? 'pointer' : 'default', height: '100%' }}
      >
        <Statistic title={title} value={value} prefix={icon} />
        <div style={{ height: 20, marginTop: 4 }}>
          {hasDetail && (
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              Click for breakdown
            </Typography.Text>
          )}
        </div>
      </Card>
      <Modal
        title={detailTitle ?? title}
        open={open}
        onCancel={() => setOpen(false)}
        footer={null}
        width={600}
      >
        <Table
          dataSource={tableData}
          pagination={tableData.length > 15 ? { pageSize: 15 } : false}
          size="small"
          columns={[
            { title: 'Name', dataIndex: 'name', key: 'name' },
            {
              title: 'Count',
              dataIndex: 'count',
              key: 'count',
              align: 'right' as const,
              render: (v: number) => v.toLocaleString(),
            },
          ]}
        />
      </Modal>
    </>
  );
}

function isComputedStats(s: StatsResponse): s is StatsResponse & {
  graph: { nodes: number; edges: number; files: number };
  languages: Record<string, number>;
  frameworks: Record<string, number>;
  connections?: unknown;
  auth?: unknown;
  architecture?: unknown;
} {
  return 'graph' in s;
}

export default function Dashboard() {
  const { data: stats, loading, error } = useApi(() => api.getStats(), []);
  const { data: kinds } = useApi(() => api.getKinds(), []);
  const { data: detailed } = useApi(() => api.getDetailedStats('all'), []);

  const computed = stats && isComputedStats(stats) ? stats : null;
  const queryStats = stats && !isComputedStats(stats)
    ? stats as { node_count: number; edge_count: number; nodes_by_kind: Record<string, number> }
    : null;

  const nodeCount = computed?.graph?.nodes ?? queryStats?.node_count ?? 0;
  const edgeCount = computed?.graph?.edges ?? queryStats?.edge_count ?? 0;
  const fileCount = computed?.graph?.files ?? 0;
  const languages = flattenToRecord(computed?.languages);
  const langCount = Object.keys(languages).length;
  const frameworks = flattenToRecord(computed?.frameworks);
  const connections = flattenToRecord(computed?.connections);
  const auth = flattenToRecord(computed?.auth);
  const architecture = flattenToRecord(computed?.architecture);

  const nodeKindBreakdown: Record<string, number> = {};
  if (kinds?.kinds) {
    for (const k of kinds.kinds) {
      nodeKindBreakdown[k.kind] = k.count;
    }
  }

  // Edge kind breakdown — try multiple sources from stats API
  const edgeKindBreakdown: Record<string, number> = {};
  if (computed && typeof computed === 'object') {
    // Check for edges_by_kind in graph section
    const graph = (computed as unknown as Record<string, unknown>).graph as Record<string, unknown> | undefined;
    if (graph?.edges_by_kind && typeof graph.edges_by_kind === 'object') {
      Object.assign(edgeKindBreakdown, flattenToRecord(graph.edges_by_kind));
    }
  }
  if (detailed && typeof detailed === 'object' && Object.keys(edgeKindBreakdown).length === 0) {
    const d = detailed as Record<string, unknown>;
    const graph = d.graph as Record<string, unknown> | undefined;
    if (graph?.edges_by_kind && typeof graph.edges_by_kind === 'object') {
      Object.assign(edgeKindBreakdown, flattenToRecord(graph.edges_by_kind));
    }
  }
  // If still empty, the API doesn't provide edge breakdown — Edges card won't be clickable

  if (loading) {
    return <div style={{ textAlign: 'center', padding: 80 }}><Spin size="large" /></div>;
  }

  if (error) {
    return <Alert type="error" message="Failed to load stats" description={error} showIcon style={{ margin: 24 }} />;
  }

  return (
    <div>
      <Typography.Title level={3} style={{ marginBottom: 24 }}>Dashboard</Typography.Title>

      <Row gutter={[16, 16]}>
        <Col xs={12} sm={8} md={6}>
          <StatCard
            title="Node Kinds"
            value={nodeCount.toLocaleString()}
            icon={<NodeIndexOutlined />}
            detail={nodeKindBreakdown}
            detailTitle="Node Kind Breakdown"
          />
        </Col>
        <Col xs={12} sm={8} md={6}>
          <StatCard
            title="Edges"
            value={edgeCount.toLocaleString()}
            icon={<BranchesOutlined />}
            detail={edgeKindBreakdown}
            detailTitle="Edge Kind Breakdown"
          />
        </Col>
        <Col xs={12} sm={8} md={6}>
          <StatCard
            title="Files"
            value={fileCount.toLocaleString()}
            icon={<FileOutlined />}
          />
        </Col>
        <Col xs={12} sm={8} md={6}>
          <StatCard
            title="Languages"
            value={langCount}
            icon={<CodeOutlined />}
            detail={languages}
            detailTitle="Language Distribution"
          />
        </Col>
        <Col xs={12} sm={8} md={6}>
          <StatCard
            title="Frameworks"
            value={Object.keys(frameworks).length}
            icon={<BuildOutlined />}
            detail={frameworks}
            detailTitle="Detected Frameworks"
          />
        </Col>
        <Col xs={12} sm={8} md={6}>
          <StatCard
            title="REST Endpoints"
            value={sumValues(connections)}
            icon={<ApiOutlined />}
            detail={connections}
            detailTitle="REST Endpoints by Method"
          />
        </Col>
        <Col xs={12} sm={8} md={6}>
          <StatCard
            title="Auth Guards"
            value={sumValues(auth)}
            icon={<SafetyOutlined />}
            detail={auth}
            detailTitle="Auth Patterns"
          />
        </Col>
        <Col xs={12} sm={8} md={6}>
          <StatCard
            title="Architecture Layers"
            value={Object.keys(architecture).length}
            icon={<AppstoreOutlined />}
            detail={architecture}
            detailTitle="Architecture Layer Distribution"
          />
        </Col>
      </Row>

      {Object.keys(frameworks).length > 0 && (
        <Card title="Detected Frameworks" style={{ marginTop: 16 }} size="small">
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
            {Object.entries(frameworks)
              .sort((a, b) => b[1] - a[1])
              .map(([name, count]) => (
                <Tag key={name} color={TAG_COLORS[Math.abs(hashCode(name)) % TAG_COLORS.length]}>{name} ({count})</Tag>
              ))}
          </div>
        </Card>
      )}
    </div>
  );
}
