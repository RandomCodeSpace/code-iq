package java

import (
	"testing"

	"github.com/randomcodespace/codeiq/internal/detector"
)

const grpcSample = `import io.grpc.stub.StreamObserver;

@GrpcService
public class UserServiceImpl extends UserServiceGrpc.UserServiceImplBase {
    @Override
    public void getUser(UserRequest req, StreamObserver<UserResponse> obs) { obs.onCompleted(); }

    public void callOther() {
        OrderServiceGrpc.newBlockingStub(channel);
    }
}`

func TestGrpcPositive(t *testing.T) {
	d := NewGrpcServiceDetector()
	r := d.Detect(&detector.Context{FilePath: "src/UserSvc.java", Language: "java", Content: grpcSample})
	if len(r.Nodes) < 2 {
		t.Fatalf("expected >=2 nodes (service + RPC), got %d", len(r.Nodes))
	}
	hasService := false
	hasRpc := false
	for _, n := range r.Nodes {
		if n.Properties["service"] == "UserService" {
			if n.Properties["method"] == "getUser" {
				hasRpc = true
			} else {
				hasService = true
			}
		}
	}
	if !hasService {
		t.Errorf("missing gRPC service node")
	}
	if !hasRpc {
		t.Errorf("missing gRPC RPC method node")
	}
	hasCalls := false
	for _, e := range r.Edges {
		if e.Properties["target_service"] == "OrderService" {
			hasCalls = true
		}
	}
	if !hasCalls {
		t.Errorf("missing calls edge to OrderService")
	}
}

func TestGrpcNegative(t *testing.T) {
	d := NewGrpcServiceDetector()
	r := d.Detect(&detector.Context{FilePath: "src/X.java", Language: "java", Content: "class X {}"})
	if len(r.Nodes) != 0 {
		t.Fatalf("expected 0, got %d", len(r.Nodes))
	}
}

func TestGrpcDeterminism(t *testing.T) {
	d := NewGrpcServiceDetector()
	c := &detector.Context{FilePath: "src/X.java", Language: "java", Content: grpcSample}
	r1 := d.Detect(c)
	r2 := d.Detect(c)
	if len(r1.Nodes) != len(r2.Nodes) {
		t.Fatal("non-deterministic")
	}
}
