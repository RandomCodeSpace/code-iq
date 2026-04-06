import { Outlet, useNavigate, useLocation } from 'react-router-dom';
import { Layout, Menu, Switch, Typography, Space } from 'antd';
import {
  DashboardOutlined,
  AppstoreOutlined,
  SearchOutlined,
  CodeOutlined,
  SunOutlined,
  MoonOutlined,
} from '@ant-design/icons';
import { useTheme } from '@/context/ThemeContext';

const { Header, Content } = Layout;

const menuItems = [
  { key: '/', icon: <DashboardOutlined />, label: 'Dashboard' },
  { key: '/map', icon: <AppstoreOutlined />, label: 'Codebase Map' },
  { key: '/explorer', icon: <SearchOutlined />, label: 'Explorer' },
  { key: '/console', icon: <CodeOutlined />, label: 'MCP Console' },
];

export default function AppLayout() {
  const navigate = useNavigate();
  const location = useLocation();
  const { isDark, toggle } = useTheme();

  const selectedKey = menuItems.find(
    item => item.key !== '/' && location.pathname.startsWith(item.key)
  )?.key ?? '/';

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Header
        style={{
          padding: '0 24px',
          display: 'flex',
          alignItems: 'center',
          gap: 0,
          position: 'sticky',
          top: 0,
          zIndex: 100,
          borderBottom: isDark ? '1px solid #303030' : '1px solid #e8e8e8',
        }}
      >
        <Typography.Title
          level={4}
          style={{ color: '#2563eb', margin: '0 24px 0 0', whiteSpace: 'nowrap', lineHeight: '64px' }}
        >
          Code IQ
        </Typography.Title>
        <Menu
          mode="horizontal"
          selectedKeys={[selectedKey]}
          items={menuItems}
          onClick={({ key }) => navigate(key)}
          style={{ flex: 1, border: 'none', background: 'transparent' }}
        />
        <Space>
          <Switch
            checked={isDark}
            onChange={toggle}
            checkedChildren={<MoonOutlined />}
            unCheckedChildren={<SunOutlined />}
          />
        </Space>
      </Header>
      <Content style={{ padding: '16px 24px', overflow: 'auto' }}>
        <Outlet />
      </Content>
    </Layout>
  );
}
