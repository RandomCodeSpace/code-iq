"""Tests for Kubernetes manifest detector."""

from osscodeiq.detectors.base import DetectorContext, DetectorResult
from osscodeiq.detectors.config.kubernetes import KubernetesDetector
from osscodeiq.models.graph import NodeKind, EdgeKind


def _ctx(parsed_data, path="k8s/deploy.yaml"):
    return DetectorContext(
        file_path=path, language="yaml", content=b"",
        parsed_data=parsed_data, module_name="test",
    )


def _yaml_single(doc):
    return {"type": "yaml", "data": doc}


def _yaml_multi(docs):
    return {"type": "yaml_multi", "documents": docs}


class TestKubernetesDetector:
    def setup_method(self):
        self.detector = KubernetesDetector()

    def test_no_parsed_data(self):
        ctx = _ctx(None)
        result = self.detector.detect(ctx)
        assert len(result.nodes) == 0

    def test_non_k8s_yaml(self):
        ctx = _ctx(_yaml_single({"kind": "NotKubernetes", "metadata": {"name": "test"}}))
        result = self.detector.detect(ctx)
        assert len(result.nodes) == 0

    def test_deployment(self):
        doc = {
            "kind": "Deployment",
            "metadata": {"name": "web-app", "namespace": "prod", "labels": {"app": "web"}},
            "spec": {
                "selector": {"matchLabels": {"app": "web"}},
                "template": {
                    "metadata": {"labels": {"app": "web"}},
                    "spec": {
                        "containers": [
                            {
                                "name": "web",
                                "image": "nginx:1.21",
                                "ports": [{"containerPort": 80, "protocol": "TCP"}],
                                "env": [{"name": "ENV_VAR", "value": "val"}],
                            }
                        ]
                    },
                },
            },
        }
        result = self.detector.detect(_ctx(_yaml_single(doc)))
        infra = [n for n in result.nodes if n.kind == NodeKind.INFRA_RESOURCE]
        assert len(infra) == 1
        assert infra[0].label == "Deployment/web-app"
        assert infra[0].properties["namespace"] == "prod"

        containers = [n for n in result.nodes if n.kind == NodeKind.CONFIG_KEY]
        assert len(containers) == 1
        assert containers[0].properties["image"] == "nginx:1.21"
        assert "80/TCP" in containers[0].properties["ports"]
        assert "ENV_VAR" in containers[0].properties["env_vars"]

    def test_service_with_selector(self):
        docs = [
            {
                "kind": "Deployment",
                "metadata": {"name": "api"},
                "spec": {
                    "selector": {"matchLabels": {"app": "api"}},
                    "template": {"metadata": {"labels": {"app": "api"}}, "spec": {"containers": [{"name": "api", "image": "api:1"}]}},
                },
            },
            {
                "kind": "Service",
                "metadata": {"name": "api-svc"},
                "spec": {"selector": {"app": "api"}, "ports": [{"port": 80}]},
            },
        ]
        result = self.detector.detect(_ctx(_yaml_multi(docs)))
        infra = [n for n in result.nodes if n.kind == NodeKind.INFRA_RESOURCE]
        assert len(infra) == 2
        depends = [e for e in result.edges if e.kind == EdgeKind.DEPENDS_ON]
        assert len(depends) == 1
        assert "app=api" in depends[0].label

    def test_configmap(self):
        doc = {
            "kind": "ConfigMap",
            "metadata": {"name": "app-config", "namespace": "default"},
        }
        result = self.detector.detect(_ctx(_yaml_single(doc)))
        assert len(result.nodes) == 1
        assert result.nodes[0].label == "ConfigMap/app-config"

    def test_pvc(self):
        doc = {
            "kind": "PersistentVolumeClaim",
            "metadata": {"name": "data-pvc"},
            "spec": {"accessModes": ["ReadWriteOnce"]},
        }
        result = self.detector.detect(_ctx(_yaml_single(doc)))
        assert len(result.nodes) == 1
        assert "PersistentVolumeClaim" in result.nodes[0].label

    def test_cronjob(self):
        doc = {
            "kind": "CronJob",
            "metadata": {"name": "cleanup"},
            "spec": {
                "schedule": "0 2 * * *",
                "jobTemplate": {
                    "spec": {
                        "template": {
                            "spec": {
                                "containers": [{"name": "cleanup", "image": "busybox"}]
                            }
                        }
                    }
                },
            },
        }
        result = self.detector.detect(_ctx(_yaml_single(doc)))
        infra = [n for n in result.nodes if n.kind == NodeKind.INFRA_RESOURCE]
        assert len(infra) == 1
        containers = [n for n in result.nodes if n.kind == NodeKind.CONFIG_KEY]
        assert len(containers) == 1
        assert containers[0].properties["image"] == "busybox"

    def test_statefulset(self):
        doc = {
            "kind": "StatefulSet",
            "metadata": {"name": "db"},
            "spec": {
                "selector": {"matchLabels": {"app": "db"}},
                "template": {
                    "metadata": {"labels": {"app": "db"}},
                    "spec": {"containers": [{"name": "postgres", "image": "postgres:14"}]},
                },
            },
        }
        result = self.detector.detect(_ctx(_yaml_single(doc)))
        assert len([n for n in result.nodes if n.kind == NodeKind.INFRA_RESOURCE]) == 1
        assert len([n for n in result.nodes if n.kind == NodeKind.CONFIG_KEY]) == 1

    def test_ingress_routes_to_service(self):
        docs = [
            {
                "kind": "Service",
                "metadata": {"name": "web-svc"},
                "spec": {"selector": {"app": "web"}},
            },
            {
                "kind": "Ingress",
                "metadata": {"name": "web-ingress"},
                "spec": {
                    "rules": [
                        {
                            "http": {
                                "paths": [
                                    {
                                        "path": "/",
                                        "backend": {"service": {"name": "web-svc", "port": {"number": 80}}},
                                    }
                                ]
                            }
                        }
                    ]
                },
            },
        ]
        result = self.detector.detect(_ctx(_yaml_multi(docs)))
        connects = [e for e in result.edges if e.kind == EdgeKind.CONNECTS_TO]
        assert len(connects) == 1
        assert "web-svc" in connects[0].label

    def test_pod(self):
        doc = {
            "kind": "Pod",
            "metadata": {"name": "debug-pod"},
            "spec": {"containers": [{"name": "debug", "image": "busybox"}]},
        }
        result = self.detector.detect(_ctx(_yaml_single(doc)))
        assert len([n for n in result.nodes if n.kind == NodeKind.INFRA_RESOURCE]) == 1
        assert len([n for n in result.nodes if n.kind == NodeKind.CONFIG_KEY]) == 1

    def test_multi_doc_filters_non_k8s(self):
        docs = [
            {"kind": "Deployment", "metadata": {"name": "app"}, "spec": {"template": {"spec": {"containers": [{"name": "c", "image": "i"}]}}}},
            {"kind": "NotK8s", "metadata": {"name": "foo"}},
            {"something": "else"},
        ]
        result = self.detector.detect(_ctx(_yaml_multi(docs)))
        infra = [n for n in result.nodes if n.kind == NodeKind.INFRA_RESOURCE]
        assert len(infra) == 1

    def test_determinism(self):
        doc = {
            "kind": "Deployment",
            "metadata": {"name": "app"},
            "spec": {"template": {"spec": {"containers": [{"name": "c", "image": "img"}]}}},
        }
        r1 = self.detector.detect(_ctx(_yaml_single(doc)))
        r2 = self.detector.detect(_ctx(_yaml_single(doc)))
        assert [n.id for n in r1.nodes] == [n.id for n in r2.nodes]
        assert [(e.source, e.target) for e in r1.edges] == [(e.source, e.target) for e in r2.edges]

    def test_ingress_default_backend(self):
        docs = [
            {"kind": "Service", "metadata": {"name": "default-svc"}, "spec": {}},
            {
                "kind": "Ingress",
                "metadata": {"name": "default-ingress"},
                "spec": {"defaultBackend": {"service": {"name": "default-svc", "port": {"number": 80}}}},
            },
        ]
        result = self.detector.detect(_ctx(_yaml_multi(docs)))
        connects = [e for e in result.edges if e.kind == EdgeKind.CONNECTS_TO]
        assert len(connects) == 1

    def test_init_containers(self):
        doc = {
            "kind": "Deployment",
            "metadata": {"name": "app"},
            "spec": {
                "template": {
                    "spec": {
                        "containers": [{"name": "main", "image": "app:1"}],
                        "initContainers": [{"name": "init", "image": "init:1"}],
                    }
                }
            },
        }
        result = self.detector.detect(_ctx(_yaml_single(doc)))
        containers = [n for n in result.nodes if n.kind == NodeKind.CONFIG_KEY]
        assert len(containers) == 2
