package java

import (
	"testing"

	"github.com/randomcodespace/codeiq/go/internal/detector"
)

const rmiSample = `import java.rmi.Remote;
import java.rmi.server.UnicastRemoteObject;

public interface MyService extends Remote {
    String hello() throws java.rmi.RemoteException;
}

public class MyServiceImpl extends UnicastRemoteObject implements MyService {
    public MyServiceImpl() {
        Registry.bind("MyServiceBinding", this);
    }
}

public class Client {
    public void call() {
        MyService s = (MyService) Naming.lookup("MyServiceBinding");
    }
}`

func TestRmiPositive(t *testing.T) {
	d := NewRmiDetector()
	r := d.Detect(&detector.Context{FilePath: "src/MyService.java", Language: "java", Content: rmiSample})
	if len(r.Nodes) != 1 {
		t.Fatalf("expected 1 interface node, got %d", len(r.Nodes))
	}
	hasExports := false
	hasInvokes := false
	for _, e := range r.Edges {
		switch e.Kind {
		case 27:
			hasInvokes = true
		case 28:
			hasExports = true
		}
		if e.Kind.String() == "exports_rmi" {
			hasExports = true
		}
		if e.Kind.String() == "invokes_rmi" {
			hasInvokes = true
		}
	}
	if !hasExports {
		t.Errorf("missing exports_rmi edge")
	}
	if !hasInvokes {
		t.Errorf("missing invokes_rmi edge")
	}
}

func TestRmiNegative(t *testing.T) {
	d := NewRmiDetector()
	r := d.Detect(&detector.Context{FilePath: "src/X.java", Language: "java", Content: "class X { }"})
	if len(r.Nodes) != 0 || len(r.Edges) != 0 {
		t.Fatalf("expected empty, got %d nodes %d edges", len(r.Nodes), len(r.Edges))
	}
}

func TestRmiDeterminism(t *testing.T) {
	d := NewRmiDetector()
	c := &detector.Context{FilePath: "src/X.java", Language: "java", Content: rmiSample}
	r1 := d.Detect(c)
	r2 := d.Detect(c)
	if len(r1.Nodes) != len(r2.Nodes) || len(r1.Edges) != len(r2.Edges) {
		t.Fatal("non-deterministic")
	}
}
