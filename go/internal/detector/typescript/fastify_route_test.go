package typescript

import (
	"sort"
	"testing"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

const fastifySource = `import Fastify from 'fastify';
const app = Fastify();

app.get('/health', async () => ({ ok: true }));
app.post('/users', async (req) => req.body);

app.route({
    method: 'PUT',
    url: '/users/:id',
    schema: { params: { id: { type: 'string' } } },
    handler: async () => ({})
});

app.register(corsPlugin);
app.addHook('onRequest', async () => {});
`

func TestFastifyRoutePositive(t *testing.T) {
	d := NewFastifyRouteDetector()
	ctx := &detector.Context{
		FilePath: "src/server.ts",
		Language: "typescript",
		Content:  fastifySource,
	}
	r := d.Detect(ctx)
	var endpoints, hooks int
	for _, n := range r.Nodes {
		switch n.Kind {
		case model.NodeEndpoint:
			endpoints++
		case model.NodeMiddleware:
			hooks++
		}
	}
	if endpoints != 3 {
		t.Errorf("expected 3 endpoints, got %d", endpoints)
	}
	if hooks != 1 {
		t.Errorf("expected 1 hook (middleware), got %d", hooks)
	}
	if len(r.Edges) != 1 {
		t.Errorf("expected 1 register edge, got %d", len(r.Edges))
	}
}

func TestFastifyRouteGuardRejects(t *testing.T) {
	// Express patterns must NOT match without `fastify` import.
	d := NewFastifyRouteDetector()
	src := `const router = express.Router();
router.get('/x', (req, res) => res.send('hi'));`
	ctx := &detector.Context{
		FilePath: "src/express.ts",
		Language: "typescript",
		Content:  src,
	}
	if len(d.Detect(ctx).Nodes) != 0 {
		t.Fatal("guard should reject without fastify import")
	}
}

func TestFastifyRouteDeterminism(t *testing.T) {
	d := NewFastifyRouteDetector()
	ctx := &detector.Context{
		FilePath: "src/server.ts",
		Language: "typescript",
		Content:  fastifySource,
	}
	r1 := d.Detect(ctx)
	r2 := d.Detect(ctx)
	if len(r1.Nodes) != len(r2.Nodes) {
		t.Fatal("non-deterministic count")
	}
	sort.Slice(r1.Nodes, func(i, j int) bool { return r1.Nodes[i].ID < r1.Nodes[j].ID })
	sort.Slice(r2.Nodes, func(i, j int) bool { return r2.Nodes[i].ID < r2.Nodes[j].ID })
	for i := range r1.Nodes {
		if r1.Nodes[i].ID != r2.Nodes[i].ID {
			t.Fatalf("non-deterministic id at %d", i)
		}
	}
}
