package java

import (
	"testing"

	"github.com/randomcodespace/codeiq/internal/detector"
	"github.com/randomcodespace/codeiq/internal/model"
)

const azureFuncSample = `import com.microsoft.azure.functions.annotation.*;

public class Functions {
    @FunctionName("HttpFn")
    public HttpResponseMessage httpFn(@HttpTrigger(name = "req", methods = {"GET"}) HttpRequestMessage req) {
        return req.createResponseBuilder().build();
    }

    @FunctionName("QueueFn")
    public void queueFn(@ServiceBusQueueTrigger(name = "msg", queueName = "orders", connection = "Conn") String msg) {}

    @FunctionName("TopicFn")
    public void topicFn(@ServiceBusTopicTrigger(name = "msg", topicName = "events", subscriptionName = "s", connection = "Conn") String msg) {}

    @FunctionName("EhFn")
    public void ehFn(@EventHubTrigger(name = "events", eventHubName = "telemetry", connection = "Conn") String events) {}

    @FunctionName("TimerFn")
    public void timerFn(@TimerTrigger(name = "timer", schedule = "0 */5 * * * *") String timer) {}
}`

func TestAzureFunctionsPositive(t *testing.T) {
	d := NewAzureFunctionsDetector()
	r := d.Detect(&detector.Context{FilePath: "src/Functions.java", Language: "java", Content: azureFuncSample})
	if len(r.Nodes) < 8 {
		t.Fatalf("expected >=8 nodes (5 funcs + endpoint + queue + topic + hub), got %d", len(r.Nodes))
	}
	triggers := map[string]bool{}
	for _, n := range r.Nodes {
		if n.Kind == model.NodeAzureFunction {
			if t, _ := n.Properties["trigger_type"].(string); t != "" {
				triggers[t] = true
			}
		}
	}
	for _, want := range []string{"http", "serviceBusQueue", "serviceBusTopic", "eventHub", "timer"} {
		if !triggers[want] {
			t.Errorf("missing trigger_type=%s", want)
		}
	}
}

func TestAzureFunctionsNegative(t *testing.T) {
	d := NewAzureFunctionsDetector()
	r := d.Detect(&detector.Context{FilePath: "src/X.java", Language: "java", Content: "class X {}"})
	if len(r.Nodes) != 0 {
		t.Fatalf("expected 0, got %d", len(r.Nodes))
	}
}

func TestAzureFunctionsDeterminism(t *testing.T) {
	d := NewAzureFunctionsDetector()
	c := &detector.Context{FilePath: "src/X.java", Language: "java", Content: azureFuncSample}
	r1 := d.Detect(c)
	r2 := d.Detect(c)
	if len(r1.Nodes) != len(r2.Nodes) {
		t.Fatal("non-deterministic")
	}
}
