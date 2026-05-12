package java

import (
	"testing"

	"github.com/randomcodespace/codeiq/go/internal/detector"
)

const cosmosSample = `import com.azure.cosmos.CosmosClient;
public class OrderRepo {
    public void run() {
        CosmosClient client = ...;
        client.getDatabase("OrdersDb").getContainer("Orders").upsertItem(o);
        client.getDatabase("OrdersDb").getContainer("Customers");
    }
}`

func TestCosmosPositive(t *testing.T) {
	d := NewCosmosDbDetector()
	r := d.Detect(&detector.Context{FilePath: "src/OrderRepo.java", Language: "java", Content: cosmosSample})
	if len(r.Nodes) != 3 {
		t.Fatalf("expected 3 nodes (1 db + 2 containers), got %d", len(r.Nodes))
	}
	if len(r.Edges) != 3 {
		t.Fatalf("expected 3 connects_to edges, got %d", len(r.Edges))
	}
}

func TestCosmosNegative(t *testing.T) {
	d := NewCosmosDbDetector()
	r := d.Detect(&detector.Context{FilePath: "src/X.java", Language: "java", Content: "class X { void run() {} }"})
	if len(r.Nodes) != 0 {
		t.Fatalf("expected 0, got %d", len(r.Nodes))
	}
}

func TestCosmosDeterminism(t *testing.T) {
	d := NewCosmosDbDetector()
	c := &detector.Context{FilePath: "src/X.java", Language: "java", Content: cosmosSample}
	r1 := d.Detect(c)
	r2 := d.Detect(c)
	if len(r1.Nodes) != len(r2.Nodes) {
		t.Fatal("non-deterministic")
	}
	for i := range r1.Nodes {
		if r1.Nodes[i].ID != r2.Nodes[i].ID {
			t.Fatalf("node[%d] id drift", i)
		}
	}
}
