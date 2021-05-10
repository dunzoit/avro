/*
 * Copyright 2018 The Apache Software Foundation.
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
package org.apache.avro;

import com.fasterxml.jackson.core.JsonGenerator;
import java.io.IOException;
import java.io.StringWriter;
import org.junit.Assert;
import org.junit.Test;

/**
 *
 * @author Zoltan Farkas
 */
public class SchemaSupplierTest {

  @Test
  public void testSchemaRefResolver() throws IOException {
    Schema recSchema = SchemaBuilder.record("TestRecord")
            .prop("id", "testId")
            .fields()
            .nullableLong("number", 0L)
            .endRecord();
    Schema schema = SchemaBuilder.array()
            .items(recSchema);
    StringWriter stringWriter = new StringWriter();
    JsonGenerator jgen = Schema.FACTORY.createGenerator(stringWriter);
    schema.toJson(new AvroNamesRefResolver(new TestResolver(recSchema)), jgen);
    jgen.flush();
    System.out.println(stringWriter.toString());
    Schema result = new Schema.Parser(new AvroNamesRefResolver(
            new TestResolver(recSchema))).parse(stringWriter.toString());
    Assert.assertEquals(schema, result);

  }

  private static class TestResolver implements  SchemaResolver {

    private final Schema recSchema;

    public TestResolver(Schema recSchema) {
      this.recSchema = recSchema;
    }

    @Override
    public String getId(Schema schema) {
      return schema.getProp("id");
    }

    @Override
    public Schema resolveSchema(String id) {
      if ("testId".equals(id))  {
        return recSchema;
      } else {
        throw new IllegalArgumentException("Unknown schema with id  "  + id);
      }
    }
  }

}
