package linker

import (
	"fmt"
	"sort"

	"github.com/randomcodespace/codeiq/internal/model"
)

// TopicLinker pairs messaging producers with consumers that share a
// topic/queue/event/message-queue node, emitting direct CALLS edges.
//
// Mirrors src/main/java/io/github/randomcodespace/iq/analyzer/linker/TopicLinker.java
// (lines 34-115). Supports Kafka, RabbitMQ, TIBCO EMS, IBM MQ, Azure Service
// Bus, Spring application events, and other enterprise messaging patterns.
type TopicLinker struct{}

// NewTopicLinker returns a stateless linker.
func NewTopicLinker() *TopicLinker { return &TopicLinker{} }

var (
	producerEdgeKinds = map[model.EdgeKind]struct{}{
		model.EdgeProduces:  {},
		model.EdgeSendsTo:   {},
		model.EdgePublishes: {},
	}
	consumerEdgeKinds = map[model.EdgeKind]struct{}{
		model.EdgeConsumes:     {},
		model.EdgeReceivesFrom: {},
		model.EdgeListens:      {},
	}
	topicNodeKinds = map[model.NodeKind]struct{}{
		model.NodeTopic:        {},
		model.NodeQueue:        {},
		model.NodeEvent:        {},
		model.NodeMessageQueue: {},
	}
)

// Link scans nodes for topic-like kinds and edges for producer/consumer kinds,
// then emits a CALLS edge from each producer to each non-self consumer that
// share a topic label.
func (l *TopicLinker) Link(nodes []*model.CodeNode, edges []*model.CodeEdge) Result {
	topicIDsByLabel := make(map[string][]string)
	for _, n := range nodes {
		if _, ok := topicNodeKinds[n.Kind]; ok {
			topicIDsByLabel[n.Label] = append(topicIDsByLabel[n.Label], n.ID)
		}
	}
	if len(topicIDsByLabel) == 0 {
		return Result{}
	}

	producersByTopic := map[string][]string{}
	consumersByTopic := map[string][]string{}
	for _, e := range edges {
		if _, ok := producerEdgeKinds[e.Kind]; ok {
			producersByTopic[e.TargetID] = append(producersByTopic[e.TargetID], e.SourceID)
		} else if _, ok := consumerEdgeKinds[e.Kind]; ok {
			consumersByTopic[e.TargetID] = append(consumersByTopic[e.TargetID], e.SourceID)
		}
	}

	// Deterministic iteration: walk labels alphabetically.
	labels := make([]string, 0, len(topicIDsByLabel))
	for k := range topicIDsByLabel {
		labels = append(labels, k)
	}
	sort.Strings(labels)

	var newEdges []*model.CodeEdge
	for _, label := range labels {
		topicIDs := topicIDsByLabel[label]
		prodSet := map[string]struct{}{}
		consSet := map[string]struct{}{}
		for _, tid := range topicIDs {
			for _, p := range producersByTopic[tid] {
				prodSet[p] = struct{}{}
			}
			for _, c := range consumersByTopic[tid] {
				consSet[c] = struct{}{}
			}
		}
		prods := sortedKeys(prodSet)
		cons := sortedKeys(consSet)
		for _, p := range prods {
			for _, c := range cons {
				if p == c {
					continue
				}
				newEdges = append(newEdges, &model.CodeEdge{
					ID:       fmt.Sprintf("topic-link:%s->%s", p, c),
					Kind:     model.EdgeCalls,
					SourceID: p,
					TargetID: c,
					Source:   SrcTopicLinker,
					Properties: map[string]any{
						"inferred": true,
						"topic":    label,
					},
				})
			}
		}
	}
	return Result{Edges: newEdges}
}

// sortedKeys returns the keys of a string set in ascending order.
func sortedKeys(m map[string]struct{}) []string {
	out := make([]string, 0, len(m))
	for k := range m {
		out = append(out, k)
	}
	sort.Strings(out)
	return out
}
