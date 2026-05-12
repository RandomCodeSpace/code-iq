package java

import (
	"testing"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

const wsSample = `import javax.websocket.server.ServerEndpoint;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;

@ServerEndpoint("/chat")
public class ChatEndpoint { }

@Controller
public class StompController {
    @MessageMapping("/hello")
    @SendTo("/topic/greetings")
    public Greeting greet(HelloMessage message) {
        simpMessagingTemplate.convertAndSend("/topic/alerts", alert);
        return new Greeting();
    }
}`

func TestWebSocketPositive(t *testing.T) {
	d := NewWebSocketDetector()
	r := d.Detect(&detector.Context{FilePath: "src/Chat.java", Language: "java", Content: wsSample})
	if len(r.Nodes) < 3 {
		t.Fatalf("expected >=3 nodes (server, message, topic), got %d", len(r.Nodes))
	}
	hasJsr := false
	hasStomp := false
	for _, n := range r.Nodes {
		if n.Kind == model.NodeWebSocketEndpoint {
			if n.Properties["type"] == "jsr356" {
				hasJsr = true
			}
			if n.Properties["type"] == "stomp" {
				hasStomp = true
			}
		}
	}
	if !hasJsr {
		t.Errorf("expected jsr356 WebSocket node")
	}
	if !hasStomp {
		t.Errorf("expected stomp WebSocket node")
	}
}

func TestWebSocketNegative(t *testing.T) {
	d := NewWebSocketDetector()
	r := d.Detect(&detector.Context{FilePath: "src/X.java", Language: "java", Content: "class X {}"})
	if len(r.Nodes) != 0 {
		t.Fatalf("expected 0, got %d", len(r.Nodes))
	}
}

func TestWebSocketDeterminism(t *testing.T) {
	d := NewWebSocketDetector()
	c := &detector.Context{FilePath: "src/X.java", Language: "java", Content: wsSample}
	r1 := d.Detect(c)
	r2 := d.Detect(c)
	if len(r1.Nodes) != len(r2.Nodes) {
		t.Fatal("non-deterministic")
	}
}
