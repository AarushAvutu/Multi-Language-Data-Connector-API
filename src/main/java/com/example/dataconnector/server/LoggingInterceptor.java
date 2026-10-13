package com.example.dataconnector.server;

import io.grpc.ForwardingServerCall.SimpleForwardingServerCall;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import java.util.logging.Logger;

/**
 * An interceptor is code that runs around <em>every</em> RPC — like a security guard at the
 * door. This one logs the method name, result status and how long the call took. Real systems
 * use the same hook for authentication, metrics and tracing.
 */
public final class LoggingInterceptor implements ServerInterceptor {

  private static final Logger LOG = Logger.getLogger("rpc");

  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
      ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
    long start = System.nanoTime();
    String method = call.getMethodDescriptor().getFullMethodName();
    ServerCall<ReqT, RespT> logged =
        new SimpleForwardingServerCall<>(call) {
          @Override
          public void close(Status status, Metadata trailers) {
            long ms = (System.nanoTime() - start) / 1_000_000;
            LOG.info(String.format("%s -> %s (%d ms)", method, status.getCode(), ms));
            super.close(status, trailers);
          }
        };
    return next.startCall(logged, headers);
  }
}
