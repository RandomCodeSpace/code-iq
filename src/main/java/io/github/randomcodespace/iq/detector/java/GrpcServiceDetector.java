package io.github.randomcodespace.iq.detector.java;

import io.github.randomcodespace.iq.detector.AbstractRegexDetector;
import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.model.CodeEdge;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import io.github.randomcodespace.iq.detector.DetectorInfo;

/**
 * Detects gRPC service implementations and client stubs.
 */
@DetectorInfo(
    name = "grpc_service",
    category = "endpoints",
    description = "Detects gRPC service implementations and stub invocations",
    languages = {"java"},
    nodeKinds = {NodeKind.ENDPOINT},
    edgeKinds = {EdgeKind.CALLS, EdgeKind.EXPOSES},
    properties = {"method", "protocol"}
)
@Component
public class GrpcServiceDetector extends AbstractRegexDetector {

    private static final Pattern CLASS_RE = Pattern.compile("(?:public\\s+)?class\\s+(\\w+)");
    private static final Pattern GRPC_IMPL_RE = Pattern.compile(
            "class\\s+(\\w+)\\s+extends\\s+(\\w+)Grpc\\.(\\w+)ImplBase");
    private static final Pattern METHOD_RE = Pattern.compile(
            "public\\s+[\\w<>\\[\\]]+\\s+(\\w+)\\s*\\(\\s*(\\w+)");
    private static final Pattern GRPC_STUB_RE = Pattern.compile(
            "(\\w+)Grpc\\.new(?:Blocking|Future)?Stub\\s*\\(");

    @Override
    public String getName() {
        return "grpc_service";
    }

    @Override
    public Set<String> getSupportedLanguages() {
        return Set.of("java");
    }

    @Override
    public DetectorResult detect(DetectorContext ctx) {
        String text = ctx.content();
        if (text == null || text.isEmpty()) return DetectorResult.empty();

        boolean hasGrpcImpl = text.contains("ImplBase") || text.contains("@GrpcService");
        boolean hasGrpcStub = text.contains("Grpc.new");
        if (!hasGrpcImpl && !hasGrpcStub) return DetectorResult.empty();

        String[] lines = text.split("\n", -1);
        List<CodeNode> nodes = new ArrayList<>();
        List<CodeEdge> edges = new ArrayList<>();

        String className = null;
        int classLine = 0;
        for (int i = 0; i < lines.length; i++) {
            Matcher cm = CLASS_RE.matcher(lines[i]);
            if (cm.find()) { className = cm.group(1); classLine = i + 1; break; }
        }
        if (className == null) return DetectorResult.empty();

        String classNodeId = ctx.filePath() + ":" + className;

        // gRPC service implementation
        Matcher implMatch = GRPC_IMPL_RE.matcher(text);
        if (implMatch.find()) {
            String serviceProto = implMatch.group(2);
            String serviceId = "grpc:service:" + serviceProto;

            CodeNode serviceNode = new CodeNode();
            serviceNode.setId(serviceId);
            serviceNode.setKind(NodeKind.ENDPOINT);
            serviceNode.setLabel("gRPC " + serviceProto);
            serviceNode.setFqn(className + " (" + serviceProto + ")");
            serviceNode.setFilePath(ctx.filePath());
            serviceNode.setLineStart(classLine);
            if (text.contains("@GrpcService")) serviceNode.getAnnotations().add("@GrpcService");
            serviceNode.getProperties().put("protocol", "grpc");
            serviceNode.getProperties().put("service", serviceProto);
            serviceNode.getProperties().put("implementation", className);
            nodes.add(serviceNode);

            CodeEdge edge = new CodeEdge();
            edge.setId(classNodeId + "->exposes->" + serviceId);
            edge.setKind(EdgeKind.EXPOSES);
            edge.setSourceId(classNodeId);
            edge.setTarget(serviceNode);
            edges.add(edge);

            // Find RPC methods
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].contains("@Override")) {
                    for (int k = i + 1; k < Math.min(i + 3, lines.length); k++) {
                        Matcher mm = METHOD_RE.matcher(lines[k]);
                        if (mm.find()) {
                            String methodName = mm.group(1);
                            String rpcId = "grpc:rpc:" + serviceProto + "/" + methodName;
                            CodeNode rpcNode = new CodeNode();
                            rpcNode.setId(rpcId);
                            rpcNode.setKind(NodeKind.ENDPOINT);
                            rpcNode.setLabel("gRPC " + serviceProto + "/" + methodName);
                            rpcNode.setFqn(className + "." + methodName);
                            rpcNode.setFilePath(ctx.filePath());
                            rpcNode.setLineStart(k + 1);
                            rpcNode.getProperties().put("protocol", "grpc");
                            rpcNode.getProperties().put("service", serviceProto);
                            rpcNode.getProperties().put("method", methodName);
                            nodes.add(rpcNode);
                            break;
                        }
                    }
                }
            }
        }

        // gRPC client stubs
        for (Matcher m = GRPC_STUB_RE.matcher(text); m.find(); ) {
            String targetService = m.group(1);
            CodeEdge edge = new CodeEdge();
            edge.setId(classNodeId + "->calls->grpc:service:" + targetService);
            edge.setKind(EdgeKind.CALLS);
            edge.setSourceId(classNodeId);
            edge.setTarget(new CodeNode("grpc:service:" + targetService, NodeKind.ENDPOINT, targetService));
            edge.setProperties(Map.of("protocol", "grpc", "target_service", targetService));
            edges.add(edge);
        }

        return DetectorResult.of(nodes, edges);
    }
}
