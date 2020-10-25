/*
 * Copyright 2019 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.avro.logical_types;

import java.io.IOException;
import java.io.StringReader;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.apache.avro.AvroUtils;
import org.apache.avro.Conversion;
import org.apache.avro.LogicalType;
import org.apache.avro.LogicalTypes;
import org.apache.avro.Schema;
import org.apache.avro.SchemaBuilder;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.junit.Assert;
import org.junit.Test;

/**
 *
 * @author Zoltan Farkas
 */
public class TestInstantLogicalType {


  @Test
  public void testInstantRecord() throws IOException {
    Schema anyRecord = SchemaBuilder.record("Instant")
            .fields()
            .requiredLong("epochSecond")
            .requiredInt("nano")
            .endRecord();
    anyRecord.addProp(LogicalType.LOGICAL_TYPE_PROP, "instant");
    LogicalType lt = LogicalTypes.fromSchema(anyRecord);
    lt.addToSchema(anyRecord);

    Schema testSchema = SchemaBuilder.builder().record("test_record").fields()
            .name("instant").type(anyRecord)
            .noDefault()
            .endRecord();
    GenericData.Record record = new GenericData.Record(testSchema);
    record.put("instant", Instant.now());
      String writeAvroExtendedJson = AvroUtils.writeAvroExtendedJson(record);
    System.out.println(writeAvroExtendedJson);
    GenericRecord back = AvroUtils.readAvroExtendedJson(new StringReader(writeAvroExtendedJson), testSchema);
    Assert.assertEquals(record.toString(), back.toString());

  }

  //"2017-06-20 08:31:15-05"
  @Test
  public void testInstantWithoutSeconds() throws IOException {
    Schema strType = Schema.create(Schema.Type.STRING);
    strType.addProp(LogicalType.LOGICAL_TYPE_PROP, "instant");
    strType.addProp("format", "yyyy-MM-dd HH:mm:ssX");
    LogicalType lt = LogicalTypes.fromSchema(strType);
    lt.addToSchema(strType);
    Schema testSchema = SchemaBuilder.builder().record("test_record").fields()
            .name("instant").type(strType)
            .noDefault()
            .endRecord();
   GenericRecord rec = AvroUtils.readAvroExtendedJson(
           new StringReader("{ \"instant\":\"2017-06-20 08:31:15-05\"}"), testSchema);
   System.out.println(rec);
   Assert.assertTrue(rec.get("instant") instanceof Instant);
  }


@Test
  public void testInstantRecord2() throws IOException {
    Schema anyRecord = SchemaBuilder.record("Instant")
            .fields()
            .requiredLong("millis")
            .endRecord();
    anyRecord.addProp(LogicalType.LOGICAL_TYPE_PROP, "instant");
    LogicalType lt = LogicalTypes.fromSchema(anyRecord);
    lt.addToSchema(anyRecord);

    Schema testSchema = SchemaBuilder.builder().record("test_record").fields()
            .name("instant").type(anyRecord)
            .noDefault()
            .endRecord();
    GenericData.Record record = new GenericData.Record(testSchema);
    Instant now = Instant.now();
    record.put("instant", now.truncatedTo(ChronoUnit.MILLIS));
    String writeAvroExtendedJson = AvroUtils.writeAvroExtendedJson(record);
    System.out.println(writeAvroExtendedJson);
    GenericRecord back = AvroUtils.readAvroExtendedJson(new StringReader(writeAvroExtendedJson), testSchema);
    Assert.assertEquals(record.toString(), back.toString());

  }

@Test
  public void testInstantRecord3() throws IOException {
    Schema anyRecord = Schema.create(Schema.Type.STRING);
    anyRecord.addProp(LogicalType.LOGICAL_TYPE_PROP, "instant");
    LogicalType lt = LogicalTypes.fromSchema(anyRecord);
    lt.addToSchema(anyRecord);

    Schema testSchema = SchemaBuilder.builder().record("test_record").fields()
            .name("instant").type(anyRecord)
            .noDefault()
            .endRecord();
    GenericData.Record record = new GenericData.Record(testSchema);
    record.put("instant", Instant.now());
      String writeAvroExtendedJson = AvroUtils.writeAvroExtendedJson(record);
    System.out.println(writeAvroExtendedJson);
    GenericRecord back = AvroUtils.readAvroExtendedJson(new StringReader(writeAvroExtendedJson), testSchema);
    Assert.assertEquals(record.toString(), back.toString());

  }

  @Test
  public void testInstantRecord4() throws IOException {
    Schema anyRecord = Schema.create(Schema.Type.LONG);
    anyRecord.addProp(LogicalType.LOGICAL_TYPE_PROP, "instant");
    LogicalType lt = LogicalTypes.fromSchema(anyRecord);
    lt.addToSchema(anyRecord);

    Schema testSchema = SchemaBuilder.builder().record("test_record").fields()
            .name("instant").type(anyRecord)
            .noDefault()
            .endRecord();
    GenericData.Record record = new GenericData.Record(testSchema);
    record.put("instant", Instant.now().truncatedTo(ChronoUnit.MILLIS));
      String writeAvroExtendedJson = AvroUtils.writeAvroExtendedJson(record);
    System.out.println(writeAvroExtendedJson);
    GenericRecord back = AvroUtils.readAvroExtendedJson(new StringReader(writeAvroExtendedJson), testSchema);
    Assert.assertEquals(record.toString(), back.toString());

  }

 @Test
  public void testTSMillisRecord1() throws IOException {
    GenericData.get().clearConversions();
    Schema anyRecord = Schema.create(Schema.Type.LONG);
    anyRecord.addProp(LogicalType.LOGICAL_TYPE_PROP, "timestamp-millis");
    LogicalType lt = LogicalTypes.fromSchema(anyRecord);
    lt.addToSchema(anyRecord);

    Schema testSchema = SchemaBuilder.builder().record("test_record").fields()
            .name("instant").type(anyRecord)
            .noDefault()
            .endRecord();
    GenericData.Record record = new GenericData.Record(testSchema);
    record.put("instant", Instant.now().toEpochMilli());
      String writeAvroExtendedJson = AvroUtils.writeAvroExtendedJson(record);
    System.out.println(writeAvroExtendedJson);
    GenericRecord back = AvroUtils.readAvroExtendedJson(new StringReader(writeAvroExtendedJson), testSchema);
    Assert.assertEquals(record.toString(), back.toString());
    GenericData.get().defaultConversions();
  }


 @Test
  public void testTSMillisRecord4() throws IOException {
    Schema anyRecord = Schema.create(Schema.Type.LONG);
    LogicalType lt = LogicalTypes.timestampMillis();
    lt.addToSchema(anyRecord);
    GenericData gd = GenericData.get();
    gd.removeLogicalConversion("timestamp-millis");
    gd.addLogicalTypeConversion(new Conversion<Instant> () {
      @Override
      public Class<Instant> getConvertedType() {
        return Instant.class;
      }

      @Override
      public String getLogicalTypeName() {
        return "timestamp-millis";
      }

      @Override
      public Long toLong(Instant value, Schema schema, LogicalType type) {
        return value.toEpochMilli();
      }

      @Override
      public Instant fromLong(Long value, Schema schema, LogicalType type) {
        return Instant.ofEpochMilli(value);
      }

    });

    Schema testSchema = SchemaBuilder.builder().record("test_record").fields()
            .name("instant").type(anyRecord)
            .noDefault()
            .endRecord();
    GenericData.Record record = new GenericData.Record(testSchema);
    record.put("instant", Instant.now().truncatedTo(ChronoUnit.MILLIS));
      String writeAvroExtendedJson = AvroUtils.writeAvroExtendedJson(record);
    System.out.println(writeAvroExtendedJson);
    GenericRecord back = AvroUtils.readAvroExtendedJson(new StringReader(writeAvroExtendedJson), testSchema);
    Assert.assertEquals(record.toString(), back.toString());

  }




}
