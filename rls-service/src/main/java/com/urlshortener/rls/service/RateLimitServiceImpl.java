package com.urlshortener.rls.service;

import com.urlshortener.rls.proto.RateLimitDescriptor;
import com.urlshortener.rls.proto.RateLimitRequest;
import com.urlshortener.rls.proto.RateLimitResponse;
import com.urlshortener.rls.proto.RateLimitServiceGrpc;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.devh.boot.grpc.server.service.GrpcService;
import org.springframework.beans.factory.annotation.Value;

/**
 * gRPC implementation of Envoy's Rate Limit Service protocol.
 *
 * Envoy calls ShouldRateLimit() before forwarding each request.
 * We extract the domain + client IP from the descriptor, run the
 * sliding window check, and reply OK or OVER_LIMIT.
 *
 * Descriptor shape sent by Envoy (configured in envoy-*.yaml):
 *   domain = "write" | "read"
 *   entries = [{ key="remote_address", value="<client_ip>" }]
 */
@Slf4j
@GrpcService
@RequiredArgsConstructor
public class RateLimitServiceImpl extends RateLimitServiceGrpc.RateLimitServiceImplBase {

    private final SlidingWindowRateLimiter rateLimiter;

    // Limits loaded from application.properties — easy to tune without recompile
    @Value("${app.ratelimit.write.limit:100}")
    private int writeLimit;

    @Value("${app.ratelimit.write.window-ms:60000}")
    private long writeWindowMs;

    @Value("${app.ratelimit.read.limit:1000}")
    private int readLimit;

    @Value("${app.ratelimit.read.window-ms:60000}")
    private long readWindowMs;

    @Override
    public void shouldRateLimit(RateLimitRequest request,
                                StreamObserver<RateLimitResponse> responseObserver) {
        String domain   = request.getDomain();
        String clientIp = extractClientIp(request);

        if (clientIp == null) {
            // No IP in descriptor — allow through (misconfigured Envoy)
            responseObserver.onNext(allow(1));
            responseObserver.onCompleted();
            return;
        }

        int  limit    = "write".equals(domain) ? writeLimit  : readLimit;
        long windowMs = "write".equals(domain) ? writeWindowMs : readWindowMs;

        boolean allowed = rateLimiter.isAllowed(domain, clientIp, limit, windowMs);

        if (allowed) {
            responseObserver.onNext(allow(limit));
        } else {
            log.info("rate_limited domain={} ip={}", domain, clientIp);
            responseObserver.onNext(deny());
        }
        responseObserver.onCompleted();
    }

    // Pull the client IP from Envoy's remote_address descriptor entry
    private String extractClientIp(RateLimitRequest request) {
        for (RateLimitDescriptor descriptor : request.getDescriptorsList()) {
            for (RateLimitDescriptor.Entry entry : descriptor.getEntriesList()) {
                if ("remote_address".equals(entry.getKey())) {
                    return entry.getValue();
                }
            }
        }
        return null;
    }

    private RateLimitResponse allow(int limit) {
        return RateLimitResponse.newBuilder()
                .setOverallCode(RateLimitResponse.Code.OK)
                .addStatuses(RateLimitResponse.DescriptorStatus.newBuilder()
                        .setCode(RateLimitResponse.Code.OK)
                        .setLimitRemaining(limit))
                .build();
    }

    private RateLimitResponse deny() {
        return RateLimitResponse.newBuilder()
                .setOverallCode(RateLimitResponse.Code.OVER_LIMIT)
                .addStatuses(RateLimitResponse.DescriptorStatus.newBuilder()
                        .setCode(RateLimitResponse.Code.OVER_LIMIT)
                        .setLimitRemaining(0))
                .build();
    }
}
