/*
 * Copyright 2016 The Apache Software Foundation.
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
package org.apache.avro.logicalTypes;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.apache.avro.AbstractLogicalType;
import org.apache.avro.AvroNamesRefResolver;
import org.apache.avro.Schema;
import org.apache.avro.SchemaResolver;
import org.apache.avro.data.RawJsonString;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.BinaryDecoder;
import org.apache.avro.io.DatumReader;
import org.apache.avro.io.DatumWriter;
import org.apache.avro.io.Decoder;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.io.Encoder;
import org.apache.avro.io.EncoderFactory;
import org.apache.avro.io.ExtendedJsonDecoder;
import org.apache.avro.io.ExtendedJsonEncoder;
import org.apache.avro.io.JsonExtensionDecoder;
import org.apache.avro.io.JsonExtensionEncoder;
import org.apache.avro.reflect.ExtendedReflectData;
import org.apache.avro.reflect.ExtendedReflectDatumWriter;
import org.apache.avro.util.CharSequenceReader;
import org.apache.avro.util.Optional;

/**
 * Decimal represents arbitrary-precision fixed-scale decimal numbers
 */
public final class AnyAvroLogicalType extends AbstractLogicalType<Object> {


  private final SchemaResolver resolver;

  private final Schema uSchema;

  private final int schemaIdx;

  private final int contentIdx;

  AnyAvroLogicalType(Schema schema, final SchemaResolver resolver) {
    super(schema.getType(), Collections.EMPTY_SET, "any",
            Collections.EMPTY_MAP, Object.class);
    if (type != Schema.Type.RECORD) {
       throw new IllegalArgumentException("any logincal type must be backed by RECORD, not" + type);
    }
    Schema.Field sField = schema.getField("avsc");
    Schema.Field cField = schema.getField("content");
    if (sField == null || cField == null) {
      throw new IllegalArgumentException("Schema " + schema + " must have fields 'avsc' and 'content'");
    }
    if (sField.schema().getType() != Schema.Type.STRING) {
      throw new IllegalArgumentException("Schema " + schema + " field 'avsc' must have string type");
    }
    if (cField.schema().getType() != Schema.Type.BYTES) {
      throw new IllegalArgumentException("Schema " + schema + " field 'content' must have bytes type");
    }
    contentIdx = cField.pos();
    schemaIdx = sField.pos();
    this.uSchema = schema;
    this.resolver = resolver;
  }

  @Override
  public Object deserialize(Object object) {
    GenericRecord rec = (GenericRecord) object;
    CharSequence schema = (CharSequence) rec.get(schemaIdx);
    Schema sch;
    try {
      sch = new Schema.Parser(new AvroNamesRefResolver(resolver)).parse(
              Schema.FACTORY.createParser(new CharSequenceReader(schema)));
    } catch (IOException ex) {
      throw new UncheckedIOException(ex);
    }
    ByteBuffer bb = (ByteBuffer) rec.get(contentIdx);
    DatumReader reader = new GenericDatumReader(sch, sch);
    int arrayOffset = bb.arrayOffset();
    InputStream is = new ByteArrayInputStream(bb.array(), arrayOffset, bb.limit() - arrayOffset);
    try {
      Decoder decoder = DecoderFactory.get().binaryDecoder(is, null);
      return reader.read(null, decoder);
    } catch (IOException | RuntimeException ex) {
      throw new RuntimeException(this + " parsing failed for " + sch + ", from " + is, ex);
    }
  }

  @Override
  public Object serialize(Object toSer) {
      Schema schema;
      if (toSer == null) {
        schema = Schema.create(Schema.Type.NULL);
      } else {
        schema = ExtendedReflectData.get().getSchema(toSer.getClass());
        if (schema == null) {
          schema = ExtendedReflectData.get().createSchema(toSer.getClass(), toSer, new HashMap<>());
        }
        if (this.equals(schema.getLogicalType())) {
          return toSer;
        }
      }
      String strSchema = toString(schema);
      GenericRecord result = new GenericData.Record(uSchema);
      result.put(schemaIdx, strSchema);
      ByteArrayOutputStream bos = new ByteArrayOutputStream();
      DatumWriter writer = new ExtendedReflectDatumWriter(schema);
      Encoder encoder = EncoderFactory.get().binaryEncoder(bos, null);
      try {
        writer.write(toSer, encoder);
        encoder.flush();
      } catch (IOException | RuntimeException ex) {
        throw new RuntimeException("Cannot serialize " + toSer, ex);
      }
      result.put(contentIdx, ByteBuffer.wrap(bos.toByteArray()));
      return result;
    }

  public String toString(Schema schema) throws UncheckedIOException {
    return toString(schema, resolver);
  }

  public static String toString(Schema schema, SchemaResolver res) throws UncheckedIOException {
    StringWriter sw = new StringWriter();
    try {
      JsonGenerator jgen = Schema.FACTORY.createGenerator(sw);
      schema.toJson(new AvroNamesRefResolver(res), jgen);
      jgen.flush();
    } catch (IOException ex) {
      throw new UncheckedIOException(ex);
    }
    return sw.toString();
  }

  @Override
  public Optional<Object> tryDirectDecode(Decoder dec, final Schema schema) throws IOException {
    if (dec instanceof JsonExtensionDecoder) {
      JsonExtensionDecoder pd = (JsonExtensionDecoder) dec;
      JsonNode theJson = pd.readValueAsTree(schema);
      JsonNode avscNode = theJson.get("avsc");
      if (avscNode.isTextual()) {
        String schemaText = avscNode.asText();
        char fc = schemaText.charAt(0);
        if (fc == '{' || fc == '"') {
          Schema anySchema = new Schema.Parser(new AvroNamesRefResolver(resolver)).setValidate(false).parse(schemaText);
          JsonNode cntnt = theJson.get("content");
          String asText = cntnt.asText();
          byte[] bytes = asText.getBytes(StandardCharsets.ISO_8859_1);
          BinaryDecoder jdec = DecoderFactory.get().directBinaryDecoder(new ByteArrayInputStream(bytes), null);
          DatumReader reader = new GenericDatumReader(anySchema, anySchema);
          return Optional.of(reader.read(null, jdec));
        }
      }
      Schema anySchema = Schema.parse(avscNode, new AvroNamesRefResolver(resolver), true, false, true);
      JsonNode cntnt = theJson.get("content");
      String jsonString = Schema.MAPPER.writeValueAsString(cntnt);
      ExtendedJsonDecoder jdec = new ExtendedJsonDecoder(anySchema, jsonString);
      DatumReader reader = new GenericDatumReader(anySchema, anySchema);
      return Optional.of(reader.read(null, jdec));
    } else {
      return Optional.empty();
    }
  }

  @Override
  public boolean tryDirectEncode(Object toSer, Encoder enc, final Schema schema) throws IOException {
    if (enc instanceof JsonExtensionEncoder) {
      Schema avsc;
      if (toSer == null) {
        avsc = Schema.create(Schema.Type.NULL);
      } else {
        avsc = ExtendedReflectData.get().getSchema(toSer.getClass());
        if (avsc == null) {
          avsc = ExtendedReflectData.get().createSchema(toSer.getClass(), toSer, new HashMap<>());
        }
        if (this.equals(avsc.getLogicalType())) {
          return false;
        }
      }
      Map record = new HashMap(4);
      record.put("avsc", new RawJsonString(toString(avsc)));
      ByteArrayOutputStream bab = new ByteArrayOutputStream();
      ExtendedJsonEncoder penc = new ExtendedJsonEncoder(avsc, bab);
      ExtendedReflectDatumWriter wr = new ExtendedReflectDatumWriter(avsc);
      wr.write(toSer, penc);
      penc.flush();
      record.put("content", new RawJsonString(new String(bab.toByteArray())));
      ((JsonExtensionEncoder) enc).writeValue(record, schema);
      return true;
    } else {
      return false;
    }
  }



}
