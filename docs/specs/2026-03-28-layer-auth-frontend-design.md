# Layer Classification, Auth Detection & Frontend Components

**Date:** 2026-03-28
**Status:** Approved

## Problem

The code-intelligence graph lacks three critical dimensions:
1. No way to distinguish frontend vs backend vs infrastructure code
2. No detection of authentication/authorization patterns
3. No frontend component detection (React, Vue, Angular, Svelte)

## Approach

Property-based classification via a post-detection classifier pass, plus new detectors for auth and frontend patterns. All implementations must be 100% deterministic.

## Determinism Requirements

All new code MUST follow these rules:
- Detectors are stateless pure functions — no class-level mutable state
- No set iteration without sorting first
- Layer classifier uses ordered rules with first-match-wins semantics
- No dependency on thread completion order (analyzer already fixed)
- Every new detector gets a consistency test — run twice on same input, assert identical output

---

## 1. Graph Model Changes

### New NodeKind Values (4)

```python
COMPONENT = "component"        # Frontend UI components (React, Vue, Angular, Svelte)
GUARD = "guard"                # Auth guards, security filters, auth middleware
MIDDLEWARE = "middleware"       # Non-auth request middleware
HOOK = "hook"                  # React/Vue hooks and composables
```

### New EdgeKind Values (2)

```python
PROTECTS = "protects"          # Auth guard protecting an endpoint
RENDERS = "renders"            # Component rendering another component
```

### Standard Property: `layer`

Added to every node by the LayerClassifier post-detection pass:
- `"frontend"` — UI components, client-side code, JSX/TSX files
- `"backend"` — Server code, API handlers, data access
- `"infra"` — Infrastructure as code, deployment, CI/CD
- `"shared"` — Config files, shared libraries, cross-cutting
- `"unknown"` — Could not determine

---

## 2. Layer Classifier

**File:** `src/code_intelligence/classifiers/layer_classifier.py`

A new pipeline step called in `analyzer.py` after detector aggregation, before linkers.

### Protocol

```python
class LayerClassifier:
    def classify(self, nodes: list[GraphNode]) -> None:
        """Mutate nodes in-place, setting properties["layer"]."""
```

### Classification Rules (ordered, first match wins)

1. NodeKind is COMPONENT, HOOK → `frontend`
2. NodeKind is GUARD, MIDDLEWARE → `backend`
3. NodeKind is INFRA_RESOURCE, AZURE_RESOURCE, AZURE_FUNCTION → `infra`
4. Language is terraform, bicep, dockerfile → `infra`
5. File extension is .tsx, .jsx → `frontend`
6. File path contains `src/components/`, `components/`, `pages/`, `views/`, `app/ui/`, `public/` → `frontend`
7. File path contains `src/server/`, `server/`, `api/`, `controllers/`, `services/`, `routes/`, `handlers/` → `backend`
8. NodeKind is ENDPOINT, REPOSITORY, DATABASE_CONNECTION, QUERY → `backend`
9. NodeKind is ENTITY and language is java, python, csharp, go → `backend`
10. Node has property `framework` in {react, vue, angular, svelte, nextjs} → `frontend`
11. Node has property `framework` in {express, nestjs, flask, django, fastapi, spring} → `backend`
12. NodeKind is CONFIG_FILE, CONFIG_KEY, CONFIG_DEFINITION → `shared`
13. NodeKind is MODULE and file path suggests frontend/backend per rules 5-7 → inherit
14. Everything else → `unknown`

### Integration Point

In `analyzer.py`, after line ~350 (results aggregation), before `run_linkers()`:

```python
from code_intelligence.classifiers.layer_classifier import LayerClassifier
LayerClassifier().classify(list(builder._store.all_nodes()))
```

---

## 3. Auth/Security Detectors (9 detectors)

All auth detectors produce GUARD nodes and PROTECTS edges. They enrich existing ENDPOINT nodes with `auth_required: true` and `roles: [...]` properties.

### 3a. Spring Security (`detectors/java/spring_security.py`)

- Language: java
- Patterns: `@Secured("ROLE_ADMIN")`, `@PreAuthorize("hasRole('USER')")`, `@RolesAllowed({...})`, `@EnableWebSecurity`, `SecurityFilterChain` bean definitions, `HttpSecurity` configuration chains (.authorizeHttpRequests, .oauth2Login, .csrf)
- Nodes: GUARD for filter chains and security config classes
- Edges: PROTECTS from guard to endpoints matched by URL patterns in security config
- Properties: `auth_type: "spring_security"`, `roles`, `auth_required`

### 3b. Django Auth (`detectors/python/django_auth.py`)

- Language: python
- Patterns: `@login_required`, `@permission_required("app.perm")`, `@user_passes_test(fn)`, `LoginRequiredMixin`, `PermissionRequiredMixin`, `UserPassesTestMixin`, `AUTHENTICATION_BACKENDS` in settings
- Nodes: GUARD for middleware classes referenced in MIDDLEWARE setting
- Properties: `auth_type: "django"`, `permissions`, `auth_required`

### 3c. FastAPI Auth (`detectors/python/fastapi_auth.py`)

- Language: python
- Patterns: `Depends(get_current_user)`, `Security(oauth2_scheme)`, `HTTPBearer()`, `OAuth2PasswordBearer(tokenUrl=...)`, `HTTPBasic()`
- Nodes: GUARD for dependency-injected auth functions
- Edges: PROTECTS from auth dependency to endpoints that use it
- Properties: `auth_type: "fastapi"`, `auth_flow: "oauth2"|"bearer"|"basic"`

### 3d. NestJS Guards (`detectors/typescript/nestjs_guards.py`)

- Language: typescript
- Patterns: `@UseGuards(JwtAuthGuard)`, `@UseGuards(RolesGuard)`, `@Roles('admin', 'user')`, `canActivate()` method implementations, `AuthGuard('jwt')`, `@SetMetadata('roles', [...])`
- Nodes: GUARD for guard classes
- Edges: PROTECTS from guard to controller endpoints
- Properties: `auth_type: "nestjs_guard"`, `roles`

### 3e. Passport/JWT (`detectors/typescript/passport_jwt.py`)

- Language: typescript, javascript
- Patterns: `passport.use(new JwtStrategy(...))`, `passport.use(new LocalStrategy(...))`, `passport.authenticate('jwt')`, `jwt.verify(token, secret)`, `jsonwebtoken`, `express-jwt`
- Nodes: GUARD for passport strategies, MIDDLEWARE for authenticate middleware
- Properties: `auth_type: "passport"|"jwt"`, `strategy`

### 3f. Kubernetes RBAC (`detectors/config/kubernetes_rbac.py`)

- Language: yaml
- Patterns: K8s manifests with `kind` in {Role, ClusterRole, RoleBinding, ClusterRoleBinding, ServiceAccount}
- Nodes: GUARD for roles with `rules` (apiGroups, resources, verbs) as properties
- Edges: PROTECTS from role → service account via binding
- Properties: `auth_type: "k8s_rbac"`, `namespace`, `rules`

### 3g. LDAP Auth (`detectors/auth/ldap_auth.py`)

- Languages: java, python, typescript, csharp
- Patterns:
  - Java: `LdapContextSource`, `LdapTemplate`, `ActiveDirectoryLdapAuthenticationProvider`, `@EnableLdapRepositories`
  - Python: `ldap3.Connection`, `AUTH_LDAP_SERVER_URI`, `AUTH_LDAP_BIND_DN`
  - TypeScript: `ldapjs`, `passport-ldapauth`
  - C#: `System.DirectoryServices`, `LdapConnection`
- Nodes: GUARD with `auth_type: "ldap"`, DATABASE_CONNECTION for LDAP server URIs
- Properties: `server_uri`, `bind_dn`, `search_base`

### 3h. TLS / Certificate / Azure AD Auth (`detectors/auth/certificate_auth.py`)

- Languages: java, python, typescript, csharp, json, yaml
- Patterns grouped by auth_type:
  - **mtls**: `ssl_verify_client on`, `requestCert: true`, `clientAuth="true"`, `X509AuthenticationFilter`, `AddCertificateForwarding`
  - **x509**: `X509AuthenticationFilter`, `CertificateAuthenticationDefaults`, X.509 cert validation
  - **tls_config**: Keystore/truststore setup, `ssl.SSLContext`, `tls.createServer`, cert/key file paths, `javax.net.ssl.keyStore`
  - **azure_ad**: `AzureAd` config sections, `AZURE_TENANT_ID`/`AZURE_CLIENT_ID`, `msal` library, `@azure/msal-browser`, `AddMicrosoftIdentityWebApi`, `ClientCertificateCredential`
- Nodes: GUARD with specific `auth_type` value
- Properties: `cert_path`, `ca_chain`, `tenant_id`, `auth_flow`

### 3i. Cookie/Session & Header Auth (`detectors/auth/session_header_auth.py`)

- Languages: java, python, typescript
- Patterns grouped by auth_type:
  - **session**: `express-session`, `cookie-session`, `@SessionAttributes`, `SessionMiddleware`, `HttpSession`, `SESSION_ENGINE`
  - **header**: `X-API-Key` header checks, custom Authorization header parsing, API key middleware
  - **api_key**: API key validation patterns, `req.headers['x-api-key']`
  - **csrf**: `@csrf_protect`, `csrf_exempt`, `CsrfViewMiddleware`, `csurf`
- Nodes: GUARD with specific auth_type, MIDDLEWARE for session/cookie middleware
- Properties: `auth_type`, `session_store` (redis, memcached, db)

---

## 4. Frontend Component Detectors (5 detectors)

### 4a. React Components (`detectors/frontend/react_components.py`)

- Languages: typescript, javascript
- Patterns:
  - Function components: `export default function CompName()`, `export const Comp = () =>`, `export const Comp: React.FC`
  - Class components: `class Comp extends React.Component`, `class Comp extends Component`
  - Memo/forwardRef: `React.memo(Comp)`, `React.forwardRef(...)`
  - Hooks: `useState`, `useEffect`, `useContext`, `useReducer`, `useMemo`, `useCallback`, `useRef`, custom `use*` functions
- Nodes: COMPONENT with `framework: "react"`, `component_type: "function"|"class"`
- Nodes: HOOK for custom hooks (files starting with `use` or functions starting with `use`)
- Edges: RENDERS from component → child component (JSX tag `<ChildName` in component body)

### 4b. Vue Components (`detectors/frontend/vue_components.py`)

- Languages: typescript, javascript, vue
- Patterns:
  - Options API: `export default { name: 'CompName' }`, `export default defineComponent({...})`
  - Composition API: `<script setup>` blocks (regex on .vue files via content)
  - Composables: `export function useFetch()`, `export function useAuth()`
- Nodes: COMPONENT with `framework: "vue"`, HOOK for composables
- Config: Add `.vue: "vue"` to extension map and include_extensions

### 4c. Angular Components (`detectors/frontend/angular_components.py`)

- Language: typescript
- Patterns:
  - `@Component({ selector: 'app-name', ... })`
  - `@Injectable({ providedIn: 'root' })` → MIDDLEWARE (service)
  - `@Directive({ selector: '[appHighlight]' })`
  - `@Pipe({ name: 'pipeName' })`
  - `@NgModule({ declarations: [...], imports: [...] })`
- Nodes: COMPONENT for @Component/@Directive/@Pipe, MIDDLEWARE for @Injectable
- Edges: IMPORTS for dependency injection in constructors
- Properties: `selector`, `framework: "angular"`

### 4d. Svelte Components (`detectors/frontend/svelte_components.py`)

- Languages: typescript, javascript, svelte
- Patterns:
  - `<script>` blocks with `export let prop`
  - `$:` reactive statements
  - `on:click`, `bind:value` event/bind patterns
- Nodes: COMPONENT with `framework: "svelte"`
- Config: Add `.svelte: "svelte"` to extension map and include_extensions

### 4e. Frontend Route Detector (`detectors/frontend/frontend_routes.py`)

- Languages: typescript, javascript
- Patterns:
  - React Router: `<Route path="/" component={Comp}>`, `<Route path="/" element={<Comp/>}>`
  - Vue Router: `{ path: '/', component: CompName }`, `createRouter({...})`
  - Next.js: File-based routing detected from path (`pages/*.tsx`, `app/**/page.tsx`)
  - Angular: `{ path: '', component: CompName }`, `RouterModule.forRoot([...])`
- Nodes: ENDPOINT with `protocol: "frontend_route"`, `framework`
- Edges: RENDERS from route endpoint → component

---

## 5. File Organization

```
src/code_intelligence/
├── classifiers/                    # NEW
│   ├── __init__.py
│   └── layer_classifier.py
├── detectors/
│   ├── auth/                       # NEW
│   │   ├── __init__.py
│   │   ├── ldap_auth.py
│   │   ├── certificate_auth.py
│   │   └── session_header_auth.py
│   ├── frontend/                   # NEW
│   │   ├── __init__.py
│   │   ├── react_components.py
│   │   ├── vue_components.py
│   │   ├── angular_components.py
│   │   ├── svelte_components.py
│   │   └── frontend_routes.py
│   ├── java/
│   │   └── spring_security.py      # NEW
│   ├── python/
│   │   ├── django_auth.py          # NEW
│   │   └── fastapi_auth.py         # NEW
│   ├── typescript/
│   │   ├── nestjs_guards.py        # NEW
│   │   └── passport_jwt.py         # NEW
│   └── config/
│       └── kubernetes_rbac.py      # NEW
```

## 6. Infrastructure Changes

### Registry (`registry.py`)

Add 15 new detector modules:
```python
# Auth detectors
"code_intelligence.detectors.java.spring_security",
"code_intelligence.detectors.python.django_auth",
"code_intelligence.detectors.python.fastapi_auth",
"code_intelligence.detectors.typescript.nestjs_guards",
"code_intelligence.detectors.typescript.passport_jwt",
"code_intelligence.detectors.config.kubernetes_rbac",
"code_intelligence.detectors.auth.ldap_auth",
"code_intelligence.detectors.auth.certificate_auth",
"code_intelligence.detectors.auth.session_header_auth",
# Frontend detectors
"code_intelligence.detectors.frontend.react_components",
"code_intelligence.detectors.frontend.vue_components",
"code_intelligence.detectors.frontend.angular_components",
"code_intelligence.detectors.frontend.svelte_components",
"code_intelligence.detectors.frontend.frontend_routes",
```

### Analyzer (`analyzer.py`)

1. Add LayerClassifier call between detector aggregation and linkers
2. Add `vue` and `svelte` to `_STRUCTURED_LANGUAGES`
3. Add parsing passthrough for vue/svelte (return raw text like markdown/proto)

### Config (`config.py` + `file_discovery.py`)

Add to extension map and include_extensions:
- `.vue: "vue"`
- `.svelte: "svelte"`

### Graph Model (`models/graph.py`)

Add 4 NodeKind values: COMPONENT, GUARD, MIDDLEWARE, HOOK
Add 2 EdgeKind values: PROTECTS, RENDERS

## 7. Testing

Each detector gets a unit test with a fixture file. Additionally:
- **Consistency test**: Run each detector twice on same input, assert `result1 == result2`
- **Layer classifier test**: Assert deterministic classification on a mixed-language fixture set
- **Integration test**: Run full analysis on contoso-real-estate (has TS frontend), verify layer properties present on all nodes
