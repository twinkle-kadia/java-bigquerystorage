/*
 * Copyright 2019 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cloud.bigquery.storage.v1alpha2.it;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.google.api.core.ApiFuture;
import com.google.cloud.ServiceOptions;
import com.google.cloud.bigquery.*;
import com.google.cloud.bigquery.Schema;
import com.google.cloud.bigquery.storage.test.SchemaTest.FakeFooType;
import com.google.cloud.bigquery.storage.test.Test.*;
import com.google.cloud.bigquery.storage.v1alpha2.*;
import com.google.cloud.bigquery.storage.v1alpha2.Storage.*;
import com.google.cloud.bigquery.storage.v1alpha2.Stream.WriteStream;
import com.google.cloud.bigquery.testing.RemoteBigQueryHelper;
import com.google.protobuf.Descriptors;
import com.google.protobuf.Int64Value;
import com.google.protobuf.Message;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.threeten.bp.Duration;

/** Integration tests for BigQuery Storage API. */
public class ITBigQueryWriteManualClientTest {
  private static final Logger LOG =
      Logger.getLogger(ITBigQueryWriteManualClientTest.class.getName());
  private static final String DATASET = RemoteBigQueryHelper.generateDatasetName();
  private static final String TABLE = "testtable";
  private static final String TABLE2 = "complicatedtable";
  private static final String DESCRIPTION = "BigQuery Write Java manual client test dataset";

  private static BigQueryWriteClient client;
  private static TableInfo tableInfo;
  private static TableInfo tableInfo2;
  private static String tableId;
  private static String tableId2;
  private static BigQuery bigquery;

  @BeforeClass
  public static void beforeClass() throws IOException {
    client = BigQueryWriteClient.create();

    RemoteBigQueryHelper bigqueryHelper = RemoteBigQueryHelper.create();
    bigquery = bigqueryHelper.getOptions().getService();
    DatasetInfo datasetInfo =
        DatasetInfo.newBuilder(/* datasetId = */ DATASET).setDescription(DESCRIPTION).build();
    bigquery.create(datasetInfo);
    LOG.info("Created test dataset: " + DATASET);
    tableInfo =
        TableInfo.newBuilder(
                TableId.of(DATASET, TABLE),
                StandardTableDefinition.of(
                    Schema.of(
                        com.google.cloud.bigquery.Field.newBuilder("foo", LegacySQLTypeName.STRING)
                            .setMode(Field.Mode.NULLABLE)
                            .build())))
            .build();
    com.google.cloud.bigquery.Field.Builder innerTypeFieldBuilder =
        com.google.cloud.bigquery.Field.newBuilder(
            "inner_type",
            LegacySQLTypeName.RECORD,
            com.google.cloud.bigquery.Field.newBuilder("value", LegacySQLTypeName.STRING)
                .setMode(Field.Mode.REPEATED)
                .build());

    tableInfo2 =
        TableInfo.newBuilder(
                TableId.of(DATASET, TABLE2),
                StandardTableDefinition.of(
                    Schema.of(
                        Field.newBuilder(
                                "nested_repeated_type",
                                LegacySQLTypeName.RECORD,
                                innerTypeFieldBuilder.setMode(Field.Mode.REPEATED).build())
                            .setMode(Field.Mode.REPEATED)
                            .build(),
                        innerTypeFieldBuilder.setMode(Field.Mode.NULLABLE).build())))
            .build();
    bigquery.create(tableInfo);
    bigquery.create(tableInfo2);
    tableId =
        String.format(
            "projects/%s/datasets/%s/tables/%s",
            ServiceOptions.getDefaultProjectId(), DATASET, TABLE);
    tableId2 =
        String.format(
            "projects/%s/datasets/%s/tables/%s",
            ServiceOptions.getDefaultProjectId(), DATASET, TABLE2);
  }

  @AfterClass
  public static void afterClass() {
    if (client != null) {
      client.close();
    }

    if (bigquery != null) {
      RemoteBigQueryHelper.forceDelete(bigquery, DATASET);
      LOG.info("Deleted test dataset: " + DATASET);
    }
  }

  private AppendRowsRequest.Builder createAppendRequest(String streamName, String[] messages) {
    AppendRowsRequest.Builder requestBuilder = AppendRowsRequest.newBuilder();

    AppendRowsRequest.ProtoData.Builder dataBuilder = AppendRowsRequest.ProtoData.newBuilder();
    dataBuilder.setWriterSchema(ProtoSchemaConverter.convert(FooType.getDescriptor()));

    ProtoBufProto.ProtoRows.Builder rows = ProtoBufProto.ProtoRows.newBuilder();
    for (String message : messages) {
      FooType foo = FooType.newBuilder().setFoo(message).build();
      rows.addSerializedRows(foo.toByteString());
    }
    dataBuilder.setRows(rows.build());
    return requestBuilder.setProtoRows(dataBuilder.build()).setWriteStream(streamName);
  }

  private AppendRowsRequest.Builder createAppendRequestComplicateType(
      String streamName, String[] messages) {
    AppendRowsRequest.Builder requestBuilder = AppendRowsRequest.newBuilder();

    AppendRowsRequest.ProtoData.Builder dataBuilder = AppendRowsRequest.ProtoData.newBuilder();
    dataBuilder.setWriterSchema(ProtoSchemaConverter.convert(ComplicateType.getDescriptor()));

    ProtoBufProto.ProtoRows.Builder rows = ProtoBufProto.ProtoRows.newBuilder();
    for (String message : messages) {
      ComplicateType foo =
          ComplicateType.newBuilder()
              .setInnerType(InnerType.newBuilder().addValue(message).addValue(message).build())
              .build();
      rows.addSerializedRows(foo.toByteString());
    }
    dataBuilder.setRows(rows.build());
    return requestBuilder.setProtoRows(dataBuilder.build()).setWriteStream(streamName);
  }

  @Test
  public void testBatchWriteWithCommittedStream()
      throws IOException, InterruptedException, ExecutionException {
    WriteStream writeStream =
        client.createWriteStream(
            CreateWriteStreamRequest.newBuilder()
                .setParent(tableId)
                .setWriteStream(
                    WriteStream.newBuilder().setType(WriteStream.Type.COMMITTED).build())
                .build());
    try (StreamWriter streamWriter =
        StreamWriter.newBuilder(writeStream.getName())
            .setBatchingSettings(
                StreamWriter.Builder.DEFAULT_BATCHING_SETTINGS
                    .toBuilder()
                    .setRequestByteThreshold(1024 * 1024L) // 1 Mb
                    .setElementCountThreshold(2L)
                    .setDelayThreshold(Duration.ofSeconds(2))
                    .build())
            .build()) {
      LOG.info("Sending one message");
      ApiFuture<AppendRowsResponse> response =
          streamWriter.append(
              createAppendRequest(writeStream.getName(), new String[] {"aaa"}).build());
      assertEquals(0, response.get().getOffset());

      LOG.info("Sending two more messages");
      ApiFuture<AppendRowsResponse> response1 =
          streamWriter.append(
              createAppendRequest(writeStream.getName(), new String[] {"bbb", "ccc"}).build());
      ApiFuture<AppendRowsResponse> response2 =
          streamWriter.append(
              createAppendRequest(writeStream.getName(), new String[] {"ddd"}).build());
      assertEquals(1, response1.get().getOffset());
      assertEquals(3, response2.get().getOffset());

      TableResult result =
          bigquery.listTableData(
              tableInfo.getTableId(), BigQuery.TableDataListOption.startIndex(0L));
      Iterator<FieldValueList> iter = result.getValues().iterator();
      assertEquals("aaa", iter.next().get(0).getStringValue());
      assertEquals("bbb", iter.next().get(0).getStringValue());
      assertEquals("ccc", iter.next().get(0).getStringValue());
      assertEquals("ddd", iter.next().get(0).getStringValue());
      assertEquals(false, iter.hasNext());
    }
  }

  @Test
  public void testJsonStreamWriterBatchWriteWithCommittedStream()
      throws IOException, InterruptedException, ExecutionException,
          Descriptors.DescriptorValidationException {
    String tableName = "JsonTable";
    TableInfo tableInfo =
        TableInfo.newBuilder(
                TableId.of(DATASET, tableName),
                StandardTableDefinition.of(
                    Schema.of(
                        com.google.cloud.bigquery.Field.newBuilder("foo", LegacySQLTypeName.STRING)
                            .build())))
            .build();
    bigquery.create(tableInfo);
    TableName parent = TableName.of(ServiceOptions.getDefaultProjectId(), DATASET, tableName);
    WriteStream writeStream =
        client.createWriteStream(
            CreateWriteStreamRequest.newBuilder()
                .setParent(parent.toString())
                .setWriteStream(
                    WriteStream.newBuilder().setType(WriteStream.Type.COMMITTED).build())
                .build());
    try (JsonStreamWriter jsonStreamWriter =
        JsonStreamWriter.newBuilder(writeStream.getName(), writeStream.getTableSchema())
            .setBatchingSettings(
                StreamWriter.Builder.DEFAULT_BATCHING_SETTINGS
                    .toBuilder()
                    .setRequestByteThreshold(1024 * 1024L) // 1 Mb
                    .setElementCountThreshold(2L)
                    .setDelayThreshold(Duration.ofSeconds(2))
                    .build())
            .build()) {
      LOG.info("Sending one message");
      JSONObject foo = new JSONObject();
      foo.put("foo", "aaa");
      JSONArray jsonArr = new JSONArray();
      jsonArr.put(foo);

      ApiFuture<AppendRowsResponse> response =
          jsonStreamWriter.append(jsonArr, -1, /* allowUnknownFields */ false);
      assertEquals(0, response.get().getOffset());

      LOG.info("Sending two more messages");
      JSONObject foo1 = new JSONObject();
      foo1.put("foo", "bbb");
      JSONObject foo2 = new JSONObject();
      foo2.put("foo", "ccc");
      JSONArray jsonArr1 = new JSONArray();
      jsonArr1.put(foo1);
      jsonArr1.put(foo2);

      JSONObject foo3 = new JSONObject();
      foo3.put("foo", "ddd");
      JSONArray jsonArr2 = new JSONArray();
      jsonArr2.put(foo3);

      ApiFuture<AppendRowsResponse> response1 =
          jsonStreamWriter.append(jsonArr1, -1, /* allowUnknownFields */ false);
      ApiFuture<AppendRowsResponse> response2 =
          jsonStreamWriter.append(jsonArr2, -1, /* allowUnknownFields */ false);
      assertEquals(1, response1.get().getOffset());
      assertEquals(3, response2.get().getOffset());

      TableResult result =
          bigquery.listTableData(
              tableInfo.getTableId(), BigQuery.TableDataListOption.startIndex(0L));
      Iterator<FieldValueList> iter = result.getValues().iterator();
      assertEquals("aaa", iter.next().get(0).getStringValue());
      assertEquals("bbb", iter.next().get(0).getStringValue());
      assertEquals("ccc", iter.next().get(0).getStringValue());
      assertEquals("ddd", iter.next().get(0).getStringValue());
      assertEquals(false, iter.hasNext());
      jsonStreamWriter.close();
    }
  }

  @Test
  public void testJsonStreamWriterSchemaUpdate()
      throws IOException, InterruptedException, ExecutionException,
          Descriptors.DescriptorValidationException {
    String tableName = "SchemaUpdateTable";
    TableInfo tableInfo =
        TableInfo.newBuilder(
                TableId.of(DATASET, tableName),
                StandardTableDefinition.of(
                    Schema.of(
                        com.google.cloud.bigquery.Field.newBuilder("foo", LegacySQLTypeName.STRING)
                            .build())))
            .build();

    bigquery.create(tableInfo);
    TableName parent = TableName.of(ServiceOptions.getDefaultProjectId(), DATASET, tableName);
    WriteStream writeStream =
        client.createWriteStream(
            CreateWriteStreamRequest.newBuilder()
                .setParent(parent.toString())
                .setWriteStream(
                    WriteStream.newBuilder().setType(WriteStream.Type.COMMITTED).build())
                .build());

    try (JsonStreamWriter jsonStreamWriter =
        JsonStreamWriter.newBuilder(writeStream.getName(), writeStream.getTableSchema())
            .setBatchingSettings(
                StreamWriter.Builder.DEFAULT_BATCHING_SETTINGS
                    .toBuilder()
                    .setElementCountThreshold(1L)
                    .build())
            .build()) {
      // 1). Send 1 row
      JSONObject foo = new JSONObject();
      foo.put("foo", "aaa");
      JSONArray jsonArr = new JSONArray();
      jsonArr.put(foo);

      ApiFuture<AppendRowsResponse> response =
          jsonStreamWriter.append(jsonArr, -1, /* allowUnknownFields */ false);
      assertEquals(0, response.get().getOffset());
      // 2). Schema update and wait until querying it returns a new schema.
      try {
        com.google.cloud.bigquery.Table table = bigquery.getTable(DATASET, tableName);
        Schema schema = table.getDefinition().getSchema();
        FieldList fields = schema.getFields();
        Field newField =
            Field.newBuilder("bar", LegacySQLTypeName.STRING).setMode(Field.Mode.NULLABLE).build();

        List<Field> fieldList = new ArrayList<Field>();
        fieldList.add(fields.get(0));
        fieldList.add(newField);
        Schema newSchema = Schema.of(fieldList);
        // Update the table with the new schema
        com.google.cloud.bigquery.Table updatedTable =
            table.toBuilder().setDefinition(StandardTableDefinition.of(newSchema)).build();
        updatedTable.update();
        int millis = 0;
        while (millis <= 10000) {
          if (newSchema.equals(table.reload().getDefinition().getSchema())) {
            break;
          }
          Thread.sleep(1000);
          millis += 1000;
        }
        newSchema = schema;
        LOG.info(
            "bar column successfully added to table in "
                + millis
                + " millis: "
                + bigquery.getTable(DATASET, tableName).getDefinition().getSchema());
      } catch (BigQueryException e) {
        LOG.severe("bar column was not added. \n" + e.toString());
      }
      // 3). Send rows to wait for updatedSchema to be returned.
      JSONObject foo2 = new JSONObject();
      foo2.put("foo", "bbb");
      JSONArray jsonArr2 = new JSONArray();
      jsonArr2.put(foo2);

      int next = 0;
      for (int i = 1; i < 100; i++) {
        ApiFuture<AppendRowsResponse> response2 =
            jsonStreamWriter.append(jsonArr2, -1, /* allowUnknownFields */ false);
        assertEquals(i, response2.get().getOffset());
        if (response2.get().hasUpdatedSchema()) {
          next = i;
          break;
        } else {
          Thread.sleep(1000);
        }
      }

      int millis = 0;
      while (millis <= 10000) {
        if (jsonStreamWriter.getDescriptor().getFields().size() == 2) {
          LOG.info("JsonStreamWriter successfully updated internal descriptor!");
          break;
        }
        Thread.sleep(100);
        millis += 100;
      }
      assertTrue(jsonStreamWriter.getDescriptor().getFields().size() == 2);
      // 4). Send rows with updated schema.
      JSONObject updatedFoo = new JSONObject();
      updatedFoo.put("foo", "ccc");
      updatedFoo.put("bar", "ddd");
      JSONArray updatedJsonArr = new JSONArray();
      updatedJsonArr.put(updatedFoo);
      for (int i = 0; i < 10; i++) {
        ApiFuture<AppendRowsResponse> response3 =
            jsonStreamWriter.append(updatedJsonArr, -1, /* allowUnknownFields */ false);
        assertEquals(next + 1 + i, response3.get().getOffset());
      }

      TableResult result3 =
          bigquery.listTableData(
              tableInfo.getTableId(), BigQuery.TableDataListOption.startIndex(0L));
      Iterator<FieldValueList> iter3 = result3.getValues().iterator();
      assertEquals("aaa", iter3.next().get(0).getStringValue());
      for (int j = 1; j <= next; j++) {
        assertEquals("bbb", iter3.next().get(0).getStringValue());
      }
      for (int j = next + 1; j < next + 1 + 10; j++) {
        FieldValueList temp = iter3.next();
        assertEquals("ccc", temp.get(0).getStringValue());
        assertEquals("ddd", temp.get(1).getStringValue());
      }
      assertEquals(false, iter3.hasNext());
    }
  }

  @Test
  public void testComplicateSchemaWithPendingStream()
      throws IOException, InterruptedException, ExecutionException {
    WriteStream writeStream =
        client.createWriteStream(
            CreateWriteStreamRequest.newBuilder()
                .setParent(tableId2)
                .setWriteStream(WriteStream.newBuilder().setType(WriteStream.Type.PENDING).build())
                .build());
    try (StreamWriter streamWriter = StreamWriter.newBuilder(writeStream.getName()).build()) {
      LOG.info("Sending two messages");
      ApiFuture<AppendRowsResponse> response =
          streamWriter.append(
              createAppendRequestComplicateType(writeStream.getName(), new String[] {"aaa"})
                  .setOffset(Int64Value.of(0L))
                  .build());
      assertEquals(0, response.get().getOffset());

      ApiFuture<AppendRowsResponse> response2 =
          streamWriter.append(
              createAppendRequestComplicateType(writeStream.getName(), new String[] {"bbb"})
                  .setOffset(Int64Value.of(1L))
                  .build());
      assertEquals(1, response2.get().getOffset());

      // Nothing showed up since rows are not committed.
      TableResult result =
          bigquery.listTableData(
              tableInfo2.getTableId(), BigQuery.TableDataListOption.startIndex(0L));
      Iterator<FieldValueList> iter = result.getValues().iterator();
      assertEquals(false, iter.hasNext());

      FinalizeWriteStreamResponse finalizeResponse =
          client.finalizeWriteStream(
              FinalizeWriteStreamRequest.newBuilder().setName(writeStream.getName()).build());

      ApiFuture<AppendRowsResponse> response3 =
          streamWriter.append(
              createAppendRequestComplicateType(writeStream.getName(), new String[] {"ccc"})
                  .setOffset(Int64Value.of(1L))
                  .build());
      try {
        assertEquals(2, response3.get().getOffset());
        fail("Append to finalized stream should fail.");
      } catch (Exception expected) {
        // The exception thrown is not stable. Opened a bug to fix it.
      }
    }
    // Finalize row count is not populated.
    // assertEquals(1, finalizeResponse.getRowCount());
    BatchCommitWriteStreamsResponse batchCommitWriteStreamsResponse =
        client.batchCommitWriteStreams(
            BatchCommitWriteStreamsRequest.newBuilder()
                .setParent(tableId2)
                .addWriteStreams(writeStream.getName())
                .build());
    assertEquals(true, batchCommitWriteStreamsResponse.hasCommitTime());
    TableResult queryResult =
        bigquery.query(
            QueryJobConfiguration.newBuilder("SELECT * from " + DATASET + '.' + TABLE2).build());
    Iterator<FieldValueList> queryIter = queryResult.getValues().iterator();
    assertTrue(queryIter.hasNext());
    assertEquals(
        "[FieldValue{attribute=REPEATED, value=[FieldValue{attribute=PRIMITIVE, value=aaa}, FieldValue{attribute=PRIMITIVE, value=aaa}]}]",
        queryIter.next().get(1).getRepeatedValue().toString());
    assertEquals(
        "[FieldValue{attribute=REPEATED, value=[FieldValue{attribute=PRIMITIVE, value=bbb}, FieldValue{attribute=PRIMITIVE, value=bbb}]}]",
        queryIter.next().get(1).getRepeatedValue().toString());
    assertFalse(queryIter.hasNext());
  }

  @Test
  public void testStreamError() throws IOException, InterruptedException, ExecutionException {
    WriteStream writeStream =
        client.createWriteStream(
            CreateWriteStreamRequest.newBuilder()
                .setParent(tableId)
                .setWriteStream(
                    WriteStream.newBuilder().setType(WriteStream.Type.COMMITTED).build())
                .build());
    try (StreamWriter streamWriter = StreamWriter.newBuilder(writeStream.getName()).build()) {
      AppendRowsRequest request =
          createAppendRequest(writeStream.getName(), new String[] {"aaa"}).build();
      request
          .toBuilder()
          .setProtoRows(request.getProtoRows().toBuilder().clearWriterSchema().build())
          .build();
      ApiFuture<AppendRowsResponse> response =
          streamWriter.append(
              createAppendRequest(writeStream.getName(), new String[] {"aaa"}).build());
      assertEquals(0L, response.get().getOffset());
      // Send in a bogus stream name should cause in connection error.
      ApiFuture<AppendRowsResponse> response2 =
          streamWriter.append(
              createAppendRequest(writeStream.getName(), new String[] {"aaa"})
                  .setOffset(Int64Value.of(100L))
                  .build());
      try {
        response2.get().getOffset();
        Assert.fail("Should fail");
      } catch (ExecutionException e) {
        assertEquals(
            "OUT_OF_RANGE: The offset is beyond stream, expected offset 1, received 100",
            e.getCause().getMessage());
      }
      // We can keep sending requests on the same stream.
      ApiFuture<AppendRowsResponse> response3 =
          streamWriter.append(
              createAppendRequest(writeStream.getName(), new String[] {"aaa"}).build());
      assertEquals(1L, response3.get().getOffset());
    } finally {
    }
  }

  @Test
  public void testStreamReconnect() throws IOException, InterruptedException, ExecutionException {
    WriteStream writeStream =
        client.createWriteStream(
            CreateWriteStreamRequest.newBuilder()
                .setParent(tableId)
                .setWriteStream(
                    WriteStream.newBuilder().setType(WriteStream.Type.COMMITTED).build())
                .build());
    try (StreamWriter streamWriter = StreamWriter.newBuilder(writeStream.getName()).build()) {
      ApiFuture<AppendRowsResponse> response =
          streamWriter.append(
              createAppendRequest(writeStream.getName(), new String[] {"aaa"})
                  .setOffset(Int64Value.of(0L))
                  .build());
      assertEquals(0L, response.get().getOffset());
    }

    try (StreamWriter streamWriter = StreamWriter.newBuilder(writeStream.getName()).build()) {
      // Currently there is a bug that reconnection must wait 5 seconds to get the real row count.
      Thread.sleep(5000L);
      ApiFuture<AppendRowsResponse> response =
          streamWriter.append(
              createAppendRequest(writeStream.getName(), new String[] {"bbb"})
                  .setOffset(Int64Value.of(1L))
                  .build());
      assertEquals(1L, response.get().getOffset());
    }
  }

  class CallAppend<T extends Message> implements Runnable {
    List<ApiFuture<Long>> resultList;
    List<T> messages;

    CallAppend(List<ApiFuture<Long>> resultList, List<T> messages) {
      this.resultList = resultList;
      this.messages = messages;
    }

    @Override
    public void run() {
      try {
        LOG.info("size: " + resultList.size());
        resultList.add(DirectWriter.<T>append(tableId, messages));
      } catch (Exception e) {
        fail("Unexpected Exception: " + e.toString());
      }
    }
  }

  @Test
  public void testDirectWrite()
      throws IOException, InterruptedException, ExecutionException,
          Descriptors.DescriptorValidationException {
    final FooType fa = FooType.newBuilder().setFoo("aaa").build();
    final FooType fb = FooType.newBuilder().setFoo("bbb").build();
    Set<Long> expectedOffset = new HashSet<>();
    for (int i = 0; i < 10; i++) {
      expectedOffset.add(Long.valueOf(i * 2));
    }
    ExecutorService executor = Executors.newFixedThreadPool(10);
    List<Future<Long>> responses = new ArrayList<>();
    Callable<Long> callable =
        new Callable<Long>() {
          @Override
          public Long call() throws IOException, InterruptedException, ExecutionException {
            ApiFuture<Long> result = DirectWriter.<FooType>append(tableId, Arrays.asList(fa, fb));
            return result.get();
          }
        };
    for (int i = 0; i < 10; i++) {
      responses.add(executor.submit(callable));
    }
    for (Future<Long> response : responses) {
      assertTrue(expectedOffset.remove(response.get()));
    }
    assertTrue(expectedOffset.isEmpty());

    JSONObject a_json = new JSONObject();
    a_json.put("foo", "aaa");
    JSONObject b_json = new JSONObject();
    b_json.put("foo", "bbb");
    final JSONArray jsonArr = new JSONArray();
    jsonArr.put(a_json);
    jsonArr.put(b_json);

    expectedOffset = new HashSet<>();
    for (int i = 0; i < 10; i++) {
      expectedOffset.add(Long.valueOf(i * 2));
    }
    executor = Executors.newFixedThreadPool(10);
    responses = new ArrayList<>();
    callable =
        new Callable<Long>() {
          @Override
          public Long call()
              throws IOException, InterruptedException, ExecutionException,
                  Descriptors.DescriptorValidationException {
            ApiFuture<Long> result = DirectWriter.append(tableId, jsonArr);
            return result.get();
          }
        };
    for (int i = 0; i < 10; i++) {
      responses.add(executor.submit(callable));
    }
    for (Future<Long> response : responses) {
      assertTrue(expectedOffset.remove(response.get()));
    }
    assertTrue(expectedOffset.isEmpty());
    executor.shutdown();
    try {
      executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
    } catch (InterruptedException e) {
      LOG.info(e.toString());
    }

    DirectWriter.clearCache();
  }

  @Test
  public void testDirectWriteFail() throws IOException, InterruptedException, ExecutionException {
    final FakeFooType fa = FakeFooType.newBuilder().setFoo(100).build();
    Set<Long> expectedOffset = new HashSet<>();
    for (int i = 0; i < 10; i++) {
      expectedOffset.add(Long.valueOf(i));
    }
    ExecutorService executor = Executors.newFixedThreadPool(10);
    List<Future<Long>> responses = new ArrayList<>();
    Callable<Long> callable =
        new Callable<Long>() {
          @Override
          public Long call()
              throws IOException, InterruptedException, ExecutionException,
                  IllegalArgumentException {
            ApiFuture<Long> result = DirectWriter.<FakeFooType>append(tableId, Arrays.asList(fa));
            return result.get();
          }
        };

    for (int i = 0; i < 10; i++) {
      responses.add(executor.submit(callable));
    }
    for (Future<Long> response : responses) {
      try {
        response.get();
      } catch (ExecutionException e) {
        assertEquals(
            "The proto field FakeFooType.foo does not have a matching type with the big query field testtable.foo.",
            e.getCause().getMessage());
      }
    }
    executor.shutdown();
    try {
      executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
    } catch (InterruptedException e) {
      LOG.info(e.toString());
    }
    DirectWriter.clearCache();
  }

  @Test
  public void testFlushRows() throws IOException, InterruptedException, ExecutionException {
    String tableName = "BufferTable";
    TableInfo tableInfo =
        TableInfo.newBuilder(
                TableId.of(DATASET, tableName),
                StandardTableDefinition.of(
                    Schema.of(
                        com.google.cloud.bigquery.Field.newBuilder("foo", LegacySQLTypeName.STRING)
                            .build())))
            .build();
    bigquery.create(tableInfo);
    TableName parent = TableName.of(ServiceOptions.getDefaultProjectId(), DATASET, tableName);
    WriteStream writeStream =
        client.createWriteStream(
            CreateWriteStreamRequest.newBuilder()
                .setParent(parent.toString())
                .setWriteStream(WriteStream.newBuilder().setType(WriteStream.Type.BUFFERED).build())
                .build());
    try (StreamWriter streamWriter = StreamWriter.newBuilder(writeStream.getName()).build()) {
      ApiFuture<AppendRowsResponse> response =
          streamWriter.append(
              createAppendRequest(writeStream.getName(), new String[] {"aaa"})
                  .setOffset(Int64Value.of(0L))
                  .build());
      assertEquals(0L, response.get().getOffset());
      streamWriter.flush(0);
    }
    TableResult result =
        bigquery.listTableData(tableInfo.getTableId(), BigQuery.TableDataListOption.startIndex(0L));
    Iterator<FieldValueList> iter = result.getValues().iterator();
    assertEquals("aaa", iter.next().get(0).getStringValue());
    assertEquals(false, iter.hasNext());
  }
}
