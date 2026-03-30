package io.github.randomcodespace.iq.config;

import com.hazelcast.config.Config;
import com.hazelcast.config.EvictionConfig;
import com.hazelcast.config.EvictionPolicy;
import com.hazelcast.config.MapConfig;
import com.hazelcast.config.MaxSizePolicy;
import com.hazelcast.config.NearCacheConfig;
import com.hazelcast.core.Hazelcast;
import com.hazelcast.core.HazelcastInstance;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Hazelcast cache configuration with two profiles:
 * <ul>
 *   <li><b>serving</b> (default/local): Standalone Hazelcast instance, no network discovery</li>
 *   <li><b>k8s</b>: Kubernetes service-based discovery for clustered deployments</li>
 * </ul>
 *
 * Both modes support the same cache maps: graph-stats, kinds-list, kind-nodes,
 * node-detail, search-results, impact-trace.
 */
@Configuration("hazelcastConfigurer")
@Profile({"serving", "k8s"})
public class HazelcastConfig {

    @Value("${codeiq.hazelcast.k8s-discovery:false}")
    private boolean k8sDiscovery;

    @Value("${codeiq.hazelcast.k8s-service-dns:}")
    private String k8sServiceDns;

    @Bean
    HazelcastInstance hazelcastInstance() {
        return Hazelcast.newHazelcastInstance(hazelcastConfig());
    }

    Config hazelcastConfig() {
        var config = new Config();
        config.setInstanceName("code-iq-cache");
        config.setClusterName("code-iq");

        // --- Local profile: disable multicast for standalone mode ---
        if (!k8sDiscovery) {
            var joinConfig = config.getNetworkConfig().getJoin();
            joinConfig.getMulticastConfig().setEnabled(false);
            joinConfig.getTcpIpConfig().setEnabled(false);
        }

        // --- Near-cache for hot graph data ---
        var nearCacheConfig = new NearCacheConfig()
                .setName("graph-nodes")
                .setTimeToLiveSeconds(300)
                .setMaxIdleSeconds(120)
                .setEvictionConfig(
                        new EvictionConfig()
                                .setMaxSizePolicy(MaxSizePolicy.ENTRY_COUNT)
                                .setSize(10_000)
                                .setEvictionPolicy(EvictionPolicy.LRU)
                );

        // --- Cache map configs ---

        // graph-stats: infrequently updated, long TTL
        config.addMapConfig(new MapConfig("graph-stats")
                .setTimeToLiveSeconds(600));

        // kinds-list: infrequently updated, long TTL
        config.addMapConfig(new MapConfig("kinds-list")
                .setTimeToLiveSeconds(600));

        // kind-nodes: paginated results, medium TTL
        config.addMapConfig(new MapConfig("kind-nodes")
                .setTimeToLiveSeconds(300)
                .setEvictionConfig(
                        new EvictionConfig()
                                .setMaxSizePolicy(MaxSizePolicy.ENTRY_COUNT)
                                .setSize(5_000)
                                .setEvictionPolicy(EvictionPolicy.LRU)
                ));

        // node-detail: per-node detail with edges, near-cached
        config.addMapConfig(new MapConfig("node-detail")
                .setTimeToLiveSeconds(300)
                .setEvictionConfig(
                        new EvictionConfig()
                                .setMaxSizePolicy(MaxSizePolicy.FREE_HEAP_PERCENTAGE)
                                .setSize(25)
                                .setEvictionPolicy(EvictionPolicy.LRU)
                )
                .setNearCacheConfig(nearCacheConfig));

        // search-results: short TTL, bounded size
        config.addMapConfig(new MapConfig("search-results")
                .setTimeToLiveSeconds(120)
                .setEvictionConfig(
                        new EvictionConfig()
                                .setMaxSizePolicy(MaxSizePolicy.ENTRY_COUNT)
                                .setSize(1_000)
                                .setEvictionPolicy(EvictionPolicy.LRU)
                ));

        // impact-trace: graph traversal results, medium TTL
        config.addMapConfig(new MapConfig("impact-trace")
                .setTimeToLiveSeconds(300)
                .setEvictionConfig(
                        new EvictionConfig()
                                .setMaxSizePolicy(MaxSizePolicy.ENTRY_COUNT)
                                .setSize(2_000)
                                .setEvictionPolicy(EvictionPolicy.LRU)
                ));

        // --- K8s pod discovery ---
        if (k8sDiscovery) {
            var networkConfig = config.getNetworkConfig();
            var joinConfig = networkConfig.getJoin();
            joinConfig.getMulticastConfig().setEnabled(false);
            joinConfig.getTcpIpConfig().setEnabled(false);

            if (k8sServiceDns != null && !k8sServiceDns.isBlank()) {
                joinConfig.getTcpIpConfig().setEnabled(true);
                joinConfig.getTcpIpConfig().addMember(k8sServiceDns);
            }
        }

        return config;
    }
}
