package io.conduit;

import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.protobuf.ByteString;
import io.conduit.grpc.Data;
import io.conduit.grpc.Record;
import org.apache.kafka.connect.data.Schema;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class RawDataSchemaProviderTest {
    @Test
    public void testFull() {
        RawDataSchemaProvider underTest = new RawDataSchemaProvider("myschema", null);
        ObjectNode json = Utils.mapper.createObjectNode()
                .put("byteField", Byte.MAX_VALUE)
                .put("shortField", Short.MAX_VALUE)
                .put("intField", Integer.MAX_VALUE)
                .put("longField", Long.MAX_VALUE)
                .put("stringField", "test string")
                .put("bytesField", "test bytes".getBytes(StandardCharsets.UTF_8))
                .put("floatField", 12.34f)
                .put("doubleField", 12.34d)
                .put("boolField", true)
                .set("stringArrayField", Utils.mapper.createArrayNode().add("a").add("b").add("c"));

        Record record = Record.newBuilder()
                .setKey(Data.newBuilder().setRawData(ByteString.copyFromUtf8("test-key")).build())
                .setPayload(Data.newBuilder()
                        .setRawData(ByteString.copyFromUtf8(json.toString()))
                        .build()
                ).build();

        Schema result = underTest.provide(record);

        assertEquals("myschema", result.name());
        assertEquals(json.size(), result.fields().size());
        assertEquals(Schema.Type.STRUCT, result.type());
        // When parsing raw JSON, we interpret ints as longs
        assertEquals(Schema.Type.INT8, result.field("byteField").schema().type());
        assertEquals(Schema.Type.INT16, result.field("shortField").schema().type());
        assertEquals(Schema.Type.INT32, result.field("intField").schema().type());
        assertEquals(Schema.Type.INT64, result.field("longField").schema().type());
        assertEquals(Schema.Type.FLOAT64, result.field("floatField").schema().type());
        assertEquals(Schema.Type.FLOAT64, result.field("doubleField").schema().type());
        assertEquals(Schema.Type.STRING, result.field("stringField").schema().type());
        // bytes are Base64 encoded
        assertEquals(Schema.Type.STRING, result.field("bytesField").schema().type());
        assertEquals(Schema.Type.BOOLEAN, result.field("boolField").schema().type());
        assertEquals(Schema.Type.ARRAY, result.field("stringArrayField").schema().type());
        assertEquals(Schema.Type.STRING, result.field("stringArrayField").schema().valueSchema().type());
    }

    @Test
    public void testNestedJson() {
        RawDataSchemaProvider underTest = new RawDataSchemaProvider("myschema", null);
        ObjectNode json = Utils.mapper.createObjectNode()
                .set("nested", Utils.mapper.createObjectNode().put("field", "value"));

        Record record = Record.newBuilder()
                .setKey(Data.newBuilder().setRawData(ByteString.copyFromUtf8("test-key")).build())
                .setPayload(Data.newBuilder()
                        .setRawData(ByteString.copyFromUtf8(json.toString()))
                        .build()
                ).build();

        Schema result = underTest.provide(record);

        assertEquals("myschema", result.name());
        assertEquals(json.size(), result.fields().size());
        assertEquals(Schema.Type.STRUCT, result.type());
        Schema nested = result.field("nested").schema();
        assertEquals(Schema.Type.STRUCT, nested.type());
        assertEquals(Schema.Type.STRING, nested.field("field").schema().type());
    }
}
