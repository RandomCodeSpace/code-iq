package io.github.randomcodespace.iq.detector.proto;

import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.detector.DetectorTestUtils;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProtoStructureDetectorTest {

    private final ProtoStructureDetector d = new ProtoStructureDetector();

    @Test
    void detectsPackage() {
        String code = "syntax = \"proto3\";\npackage com.example.api;\n";
        DetectorResult r = d.detect(ctx(code));
        assertTrue(r.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.CONFIG_KEY
                && "com.example.api".equals(n.getFqn())));
    }

    @Test
    void detectsService() {
        String code = """
                service UserService {
                    rpc GetUser(GetUserRequest) returns (User);
                }
                """;
        DetectorResult r = d.detect(ctx(code));
        assertTrue(r.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.INTERFACE
                && "UserService".equals(n.getLabel())));
    }

    @Test
    void detectsRpc() {
        String code = """
                service OrderService {
                    rpc CreateOrder(CreateOrderRequest) returns (Order);
                    rpc GetOrder(GetOrderRequest) returns (Order);
                }
                """;
        DetectorResult r = d.detect(ctx(code));
        assertEquals(2, r.nodes().stream().filter(n -> n.getKind() == NodeKind.METHOD).count());
    }

    @Test
    void rpcNodeHasRequestAndResponseType() {
        String code = """
                service Svc {
                    rpc DoThing(ThingRequest) returns (ThingResponse);
                }
                """;
        DetectorResult r = d.detect(ctx(code));
        var rpc = r.nodes().stream().filter(n -> n.getKind() == NodeKind.METHOD).findFirst().orElseThrow();
        assertEquals("ThingRequest", rpc.getProperties().get("request_type"));
        assertEquals("ThingResponse", rpc.getProperties().get("response_type"));
    }

    @Test
    void detectsRpcContainsEdge() {
        String code = """
                service UserService {
                    rpc GetUser(GetUserRequest) returns (User);
                }
                """;
        DetectorResult r = d.detect(ctx(code));
        assertTrue(r.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.CONTAINS));
    }

    @Test
    void detectsMessage() {
        String code = """
                message User {
                    int64 id = 1;
                    string name = 2;
                    string email = 3;
                }
                """;
        DetectorResult r = d.detect(ctx(code));
        assertTrue(r.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.PROTOCOL_MESSAGE
                && "User".equals(n.getLabel())));
    }

    @Test
    void detectsImport() {
        String code = "import \"google/protobuf/timestamp.proto\";\nimport \"google/protobuf/empty.proto\";\n";
        DetectorResult r = d.detect(ctx(code));
        assertEquals(2, r.edges().stream().filter(e -> e.getKind() == EdgeKind.IMPORTS).count());
    }

    @Test
    void detectsServiceAndMessage() {
        String code = "package grpc.test;\nservice UserService {\n  rpc GetUser(GetUserReq) returns (User);\n}\nmessage User {\n  string name = 1;\n}";
        DetectorResult r = d.detect(ctx(code));
        assertTrue(r.nodes().size() >= 3);
    }

    @Test
    void fullProtoFile() {
        String code = """
                syntax = "proto3";
                package com.example.orders;
                import "google/protobuf/timestamp.proto";
                service OrderService {
                    rpc CreateOrder(CreateOrderRequest) returns (Order);
                    rpc GetOrder(GetOrderRequest) returns (Order);
                    rpc ListOrders(ListOrdersRequest) returns (ListOrdersResponse);
                }
                message Order {
                    int64 id = 1;
                    string status = 2;
                }
                message CreateOrderRequest {
                    string item = 1;
                }
                message GetOrderRequest {
                    int64 id = 1;
                }
                message ListOrdersRequest {}
                message ListOrdersResponse {
                    repeated Order orders = 1;
                }
                """;
        DetectorResult r = d.detect(ctx(code));
        // 1 package + 1 service + 3 RPCs + 5 messages
        assertTrue(r.nodes().size() >= 10);
    }

    @Test
    void noMatchOnComment() {
        DetectorResult r = d.detect(ctx("// comment\n/* multi-line comment */\n"));
        assertEquals(0, r.nodes().size());
    }

    @Test
    void emptyContentReturnsEmpty() {
        DetectorResult r = d.detect(ctx(""));
        assertTrue(r.nodes().isEmpty());
        assertTrue(r.edges().isEmpty());
    }

    @Test
    void nullContentReturnsEmpty() {
        DetectorContext ctxNull = new DetectorContext("test.proto", "proto", null);
        DetectorResult r = d.detect(ctxNull);
        assertTrue(r.nodes().isEmpty());
    }

    @Test
    void returnsCorrectName() {
        assertEquals("proto_structure", d.getName());
    }

    @Test
    void supportedLanguagesContainsProto() {
        assertTrue(d.getSupportedLanguages().contains("proto"));
    }

    @Test
    void deterministic() {
        String code = """
                syntax = "proto3";
                package example;
                import "google/protobuf/empty.proto";
                service Svc {
                    rpc Do(Req) returns (Resp);
                    rpc List(google.protobuf.Empty) returns (ListResp);
                }
                message Req { string id = 1; }
                message Resp { string result = 1; }
                message ListResp { repeated Resp items = 1; }
                """;
        DetectorTestUtils.assertDeterministic(d, ctx(code));
    }

    private static DetectorContext ctx(String content) {
        return DetectorTestUtils.contextFor("proto", content);
    }
}
