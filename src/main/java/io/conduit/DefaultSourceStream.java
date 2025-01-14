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

import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.function.Function;

import com.google.protobuf.ByteString;
import io.conduit.grpc.Record;
import io.conduit.grpc.Source;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.SneakyThrows;
import org.apache.kafka.connect.source.SourceRecord;
import org.apache.kafka.connect.source.SourceTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link io.grpc.stub.StreamObserver} implementation which exposes a Kafka connector source task
 * through a gRPC stream.
 */
public class DefaultSourceStream implements SourceStream {
    public static final Logger logger = LoggerFactory.getLogger(DefaultSourceStream.class);
    
    private final SourceTask task;
    private final StreamObserver<Source.Run.Response> responseObserver;
    private boolean shouldRun = true;

    private final Queue<SourceRecord> buffer = new LinkedList<>();
    private final Function<SourceRecord, Record.Builder> transformer;
    private final SourcePosition position;

    public DefaultSourceStream(SourceTask task,
                               SourcePosition position,
                               StreamObserver<Source.Run.Response> responseObserver,
                               Function<SourceRecord, Record.Builder> transformer) {
        this.task = task;
        this.position = position;
        this.responseObserver = responseObserver;
        this.transformer = transformer;
    }

    @Override
    public void run() {
        while (shouldRun) {
            try {
                if (buffer.isEmpty()) {
                    fillBuffer();
                }
                SourceRecord rec = buffer.poll();
                // We may get so-called tombstone records, i.e. records with a null payload.
                // This can happen when records are deleted, for example.
                // This is used in Kafka Connect internally, more precisely for log compaction in Kafka.
                // For more info: https://kafka.apache.org/documentation/#compaction
                if (rec.value() != null) {
                    responseObserver.onNext(responseWith(rec));
                }
            } catch (Exception e) {
                logger.error("Couldn't write record.", e);
                responseObserver.onError(
                        Status.INTERNAL
                                .withDescription("couldn't read record: " + e.getMessage())
                                .withCause(e)
                                .asException()
                );
            }
        }
        logger.info("SourceStream loop stopped.");
    }

    @Override
    public void onNext(Source.Run.Request value) {
        // todo Acknowledging record not implemented yet...
        // See: https://github.com/ConduitIO/conduit-kafka-connect-wrapper/issues/59
    }

    @SneakyThrows
    private void fillBuffer() {
        List<SourceRecord> polled = task.poll();
        while (Utils.isEmpty(polled)) {
            polled = task.poll();
        }
        buffer.addAll(polled);
    }

    @SneakyThrows
    private Source.Run.Response responseWith(SourceRecord rec) {
        position.add(rec.sourcePartition(), rec.sourceOffset());

        Record.Builder conduitRec = transformer.apply(rec)
                .setPosition(position.asByteString());

        return Source.Run.Response.newBuilder()
                .setRecord(conduitRec)
                .build();
    }

    @Override
    public void onError(Throwable t) {
        logger.error("Experienced an error.", t);
        stop();
        responseObserver.onError(
                Status.INTERNAL.withDescription("Error: " + t.getMessage()).withCause(t).asException()
        );
    }

    @Override
    public void onCompleted() {
        logger.info("Completed.");
        stop();
        responseObserver.onCompleted();
    }

    private void stop() {
        logger.info("Stopping...");
        shouldRun = false;
    }

    /**
     * Starts this stream. The method is not blocking -- the actual work is done in a separate thread.
     */
    public void startAsync() {
        Thread thread = new Thread(this);
        thread.setUncaughtExceptionHandler((t, e) -> {
            logger.error("Uncaught exception for thread {}.", t.getName(), e);
            onError(e);
        });
        thread.start();
    }

    @Override
    public ByteString lastRead() {
        return position.asByteString();
    }
}
