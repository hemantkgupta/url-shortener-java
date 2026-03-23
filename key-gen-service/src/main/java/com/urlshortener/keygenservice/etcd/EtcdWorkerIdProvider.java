package com.urlshortener.keygenservice.etcd;

import io.etcd.jetcd.ByteSequence;
import io.etcd.jetcd.Client;
import io.etcd.jetcd.CloseableClient;
import io.etcd.jetcd.KV;
import io.etcd.jetcd.Lease;
import io.etcd.jetcd.lease.LeaseKeepAliveResponse;
import io.etcd.jetcd.op.Cmp;
import io.etcd.jetcd.op.CmpTarget;
import io.etcd.jetcd.op.Op;
import io.etcd.jetcd.options.PutOption;
import io.grpc.stub.StreamObserver;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;

/**
 * Acquires and holds a unique Snowflake worker ID (0-1023) via etcd.
 *
 * On startup:
 *   1. Create a lease with a short TTL (default 15 s).
 *   2. Scan worker IDs 0-1023 and atomically claim the first unclaimed slot
 *      using an etcd transaction (CAS on key version == 0).
 *   3. Start a keepalive to renew the lease while this process is alive.
 *
 * On shutdown (or crash):
 *   - The lease is revoked (or expires automatically), releasing the worker ID
 *     for another instance to claim.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.key-gen.strategy", havingValue = "SNOWFLAKE")
public class EtcdWorkerIdProvider {

    @Value("${app.key-gen.etcd.endpoints:http://localhost:2379}")
    private String endpoints;

    @Value("${app.key-gen.etcd.lease-ttl-seconds:15}")
    private long leaseTtlSeconds;

    @Value("${app.key-gen.etcd.worker-key-prefix:/key-gen/workers/}")
    private String workerKeyPrefix;

    @Value("${app.key-gen.etcd.max-worker-id:1023}")
    private int maxWorkerId;

    private Client etcdClient;
    private long leaseId;
    private CloseableClient keepAlive;
    private int workerId = -1;

    @PostConstruct
    void init() throws Exception {
        etcdClient = Client.builder()
                .endpoints(endpoints.split(","))
                .build();

        Lease leaseClient = etcdClient.getLeaseClient();
        KV   kvClient     = etcdClient.getKVClient();

        leaseId = leaseClient.grant(leaseTtlSeconds).get().getID();

        String instanceId = InetAddress.getLocalHost().getHostName()
                + ":" + ProcessHandle.current().pid();

        for (int id = 0; id <= maxWorkerId; id++) {
            ByteSequence key   = key(id);
            ByteSequence value = ByteSequence.from(instanceId, StandardCharsets.UTF_8);
            PutOption putOpt   = PutOption.builder().withLeaseId(leaseId).build();

            // Atomic claim: only succeeds if key does not yet exist (version == 0)
            boolean claimed = kvClient.txn()
                    .If(new Cmp(key, Cmp.Op.EQUAL, CmpTarget.version(0)))
                    .Then(Op.put(key, value, putOpt))
                    .commit().get()
                    .isSucceeded();

            if (claimed) {
                workerId = id;
                log.info("Claimed Snowflake worker ID {} via etcd (instance={})", id, instanceId);
                break;
            }
        }

        if (workerId < 0) {
            throw new IllegalStateException(
                    "No available Snowflake worker IDs in etcd (all 0-" + maxWorkerId + " taken)");
        }

        // Keep the lease alive so no other instance can steal our worker ID
        keepAlive = leaseClient.keepAlive(leaseId, new StreamObserver<>() {
            @Override public void onNext(LeaseKeepAliveResponse r) { /* renewed */ }
            @Override public void onError(Throwable t) { log.error("etcd keepalive error", t); }
            @Override public void onCompleted() { log.info("etcd keepalive stream closed"); }
        });
    }

    @PreDestroy
    void release() {
        try {
            if (keepAlive != null) keepAlive.close();
            if (etcdClient != null && leaseId != 0) {
                etcdClient.getLeaseClient().revoke(leaseId).get();
                log.info("Released Snowflake worker ID {} from etcd", workerId);
            }
        } catch (Exception e) {
            log.warn("Error releasing etcd lease for worker ID {}: {}", workerId, e.getMessage());
        } finally {
            if (etcdClient != null) etcdClient.close();
        }
    }

    /** The 10-bit worker ID claimed from etcd. */
    public int getWorkerId() {
        if (workerId < 0) throw new IllegalStateException("Worker ID not yet assigned");
        return workerId;
    }

    private ByteSequence key(int id) {
        return ByteSequence.from(workerKeyPrefix + id, StandardCharsets.UTF_8);
    }
}
