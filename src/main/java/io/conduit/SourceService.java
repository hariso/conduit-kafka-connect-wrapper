/*
 * Copyright 2022 Meroxa, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.conduit;

import java.util.HashMap;

import io.conduit.grpc.Source;
import io.conduit.grpc.SourcePluginGrpc;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.connect.source.SourceTask;
import org.slf4j.MDC;

/**
 * A gRPC service exposing source plugin methods.
 */
@Slf4j
public class SourceService extends SourcePluginGrpc.SourcePluginImplBase {
    private final TaskFactory taskFactory;
    private SourceTask task;
    private HashMap<String, String> config;
    private boolean started;
    private SourceStream runStream;
    private SourcePosition position;

    public SourceService(TaskFactory taskFactory) {
        this.taskFactory = taskFactory;
    }

    @Override
    public void configure(Source.Configure.Request req, StreamObserver<Source.Configure.Response> respObserver) {
        log.info("Configuring the source.");

        try {
            // the returned config map is unmodifiable, so we make a copy
            // since we need to remove some keys
            doConfigure(new HashMap<>(req.getConfigMap()));
            log.info("Done configuring the source.");

            respObserver.onNext(Source.Configure.Response.newBuilder().build());
            respObserver.onCompleted();
        } catch (Exception e) {
            log.error("Error while configuring source.", e);
            respObserver.onError(
                    Status.INTERNAL
                            .withDescription("couldn't configure task: " + e.getMessage())
                            .withCause(e)
                            .asException()
            );
        }
    }

    private void doConfigure(HashMap<String, String> config) {
        // logging
        MDC.put("pipelineId", config.remove("pipelineId"));
        MDC.put("connectorName", config.remove("connectorName"));

        this.task = taskFactory.newSourceTask(config.remove("task.class"));
        this.config = config;
    }

    @Override
    public void start(Source.Start.Request request, StreamObserver<Source.Start.Response> responseObserver) {
        log.info("Starting the source.");

        try {
            this.position = SourcePosition.fromString(request.getPosition().toStringUtf8());
            task.initialize(
                    new SimpleSourceTaskCtx(config, position)
            );
            task.start(config);
            started = true;
            log.info("Source started.");

            responseObserver.onNext(Source.Start.Response.newBuilder().build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("Error while starting.", e);
            responseObserver.onError(
                    Status.INTERNAL.withDescription("couldn't start task: " + e.getMessage()).withCause(e).asException()
            );
        }
    }

    @Override
    public StreamObserver<Source.Run.Request> run(StreamObserver<Source.Run.Response> responseObserver) {
        this.runStream = new SourceStream(
                task,
                position,
                responseObserver,
                Transformations::fromKafkaSource
        );
        runStream.start();
        return runStream;
    }

    @Override
    public void stop(Source.Stop.Request request, StreamObserver<Source.Stop.Response> responseObserver) {
        // todo check if a record is being flushed
        runStream.onCompleted();
        responseObserver.onNext(Source.Stop.Response.newBuilder().build());
        responseObserver.onCompleted();
    }

    @Override
    public void teardown(Source.Teardown.Request request, StreamObserver<Source.Teardown.Response> responseObserver) {
        log.info("Tearing down...");
        try {
            if (task != null && started) {
                task.stop();
            }
            responseObserver.onNext(Source.Teardown.Response.newBuilder().build());
            responseObserver.onCompleted();
            log.info("Torn down.");
        } catch (Exception e) {
            log.error("Couldn't tear down.", e);
            responseObserver.onError(
                    Status.INTERNAL.withDescription("Couldn't tear down: " + e.getMessage()).withCause(e).asException()
            );
        }
    }
}