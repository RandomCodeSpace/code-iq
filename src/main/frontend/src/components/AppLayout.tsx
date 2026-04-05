import { useState } from 'react';
import { Outlet, useNavigate, useLocation } from 'react-router-dom';
import { Layout, Menu, Switch, Typography } from 'antd';
import {
  DashboardOutlined,
  AppstoreOutlined,
  SearchOutlined,
  CodeOutlined,
  SunOutlined,
  MoonOutlined,
} from '@ant-design/icons';
import { useTheme } from '@/context/ThemeContext';

const { Header, Sider, Content } = Layout;

const menuItems = [
  { key: '/', icon: <DashboardOutlined />, label: 'Dashboard' },
  { key: '/map', icon: <AppstoreOutlined />, label: 'Codebase Map' },
  { key: '/explorer', icon: <SearchOutlined />, label: 'Explorer' },
  { key: '/console', icon: <CodeOutlined />, label: 'MCP Console' },
];

export default function AppLayout() {
  const [collapsed, setCollapsed] = useState(false);
  const navigate = useNavigate();
  const location = useLocation();
  const { isDark, toggle } = useTheme();

  const selectedKey = menuItems.find(
    item => item.key !== '/' && location.pathname.startsWith(item.key)
  )?.key ?? '/';

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Sider
        collapsible
        collapsed={collapsed}
        onCollapse={setCollapsed}
        breakpoint="lg"
        style={{ overflow: 'auto', height: '100vh', position: 'fixed', left: 0, top: 0, bottom: 0 }}
      >
        <div style={{ padding: collapsed ? '16px 8px' : '16px', textAlign: 'center' }}>
          <Typography.Title
            level={4}
            style={{ color: '#1677ff', margin: 0, whiteSpace: 'nowrap' }}
          >
            {collapsed ? 'IQ' : 'Code IQ'}
          </Typography.Title>
        </div>
        <Menu
          theme="dark"
          mode="inline"
          selectedKeys={[selectedKey]}
          items={menuItems}
          onClick={({ key }) => navigate(key)}
        />
      </Sider>
      <Layout style={{ marginLeft: collapsed ? 80 : 200, transition: 'margin-left 0.2s' }}>
        <Header
          style={{
            padding: '0 24px',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'flex-end',
            background: 'transparent',
          }}
        >
          <Switch
            checked={isDark}
            onChange={toggle}
            checkedChildren={<MoonOutlined />}
            unCheckedChildren={<SunOutlined />}
          />
        </Header>
        <Content style={{ margin: '0 16px 16px', overflow: 'auto' }}>
          <Outlet />
        </Content>
      </Layout>
    </Layout>
  );
}
