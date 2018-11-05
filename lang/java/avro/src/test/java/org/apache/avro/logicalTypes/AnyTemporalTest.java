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
package org.apache.avro.logicalTypes;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Year;
import java.time.YearMonth;
import java.time.temporal.Temporal;
import org.apache.avro.LogicalType;
import org.apache.avro.LogicalTypes;
import org.junit.Assert;
import org.apache.avro.Schema;
import org.junit.Test;

/**
 *
 * @author Zoltan Farkas
 */
public class AnyTemporalTest {


  @Test
  public void testTemporal() {
    Schema schema = Schema.create(Schema.Type.STRING);
    schema.addProp("logicalType", "any_temporal");
    schema.setLogicalType(LogicalTypes.fromSchema(schema));
    LogicalType<Temporal> at = schema.getLogicalType();
    testDT(at);
    testD(at);
    testYM(at);
    testYM2(at);
    testY(at);
  }

  public void testDT(LogicalType<Temporal> at) {
    LocalDateTime now = LocalDateTime.now();
    Object serialize = at.serialize(now);
    Temporal now2 = at.deserialize(serialize);
    Assert.assertEquals(now, now2);
  }

  public void testD(LogicalType<Temporal> at) {
    LocalDate now = LocalDate.now();
    Object serialize = at.serialize(now);
    Temporal now2 = at.deserialize(serialize);
    Assert.assertEquals(now, now2);
  }

  public void testYM(LogicalType<Temporal> at) {
    YearMonth now = YearMonth.now();
    Object serialize = at.serialize(now);
    Temporal now2 = at.deserialize(serialize);
    Assert.assertEquals(now, now2);
  }

  public void testYM2(LogicalType<Temporal> at) {
    YearMonth now = YearMonth.of(-10, 2);
    Object serialize = at.serialize(now);
    Temporal now2 = at.deserialize(serialize);
    Assert.assertEquals(now, now2);
  }

  public void testY(LogicalType<Temporal> at) {
    Year now = Year.now();
    Object serialize = at.serialize(now);
    Temporal now2 = at.deserialize(serialize);
    Assert.assertEquals(now, now2);
  }

}
