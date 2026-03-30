import { useState } from 'react';

export default function SwaggerView() {
  const [loaded, setLoaded] = useState(false);

  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-2xl font-bold gradient-text">API Documentation</h1>
        <p className="text-sm text-surface-400 mt-1">Interactive OpenAPI / Swagger UI</p>
      </div>

      <div className="glass-card overflow-hidden" style={{ height: 'calc(100vh - 180px)' }}>
        {!loaded && (
          <div className="absolute inset-0 flex items-center justify-center">
            <div className="w-8 h-8 border-2 border-brand-500 border-t-transparent rounded-full animate-spin" />
          </div>
        )}
        <iframe
          src="/swagger-ui/index.html"
          className="w-full h-full border-0"
          title="Swagger UI"
          onLoad={() => setLoaded(true)}
        />
      </div>
    </div>
  );
}
