/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.avro.compiler.specific;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.html.HtmlEscapers;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.avro.Conversion;
import org.apache.avro.LogicalType;
import org.apache.avro.Protocol;
import org.apache.avro.Protocol.Message;
import org.apache.avro.Schema;
import org.apache.avro.Schema.Field;
import org.apache.avro.SchemaNormalization;
import org.apache.avro.JsonProperties;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericData.StringType;
import org.apache.avro.specific.SpecificData;
import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.runtime.RuntimeServices;
import org.apache.velocity.runtime.log.LogChute;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static org.apache.avro.specific.SpecificData.RESERVED_WORDS;
import org.apache.commons.lang.StringEscapeUtils;

/**
 * Generate specific Java interfaces and classes for protocols and schemas.
 *
 * Java reserved keywords are mangled to preserve compilation.
 */
public class SpecificCompiler {

  /*
   * From Section 4.10 of the Java VM Specification:
   *    A method descriptor is valid only if it represents method parameters with a total length of 255 or less,
   *    where that length includes the contribution for this in the case of instance or interface method invocations.
   *    The total length is calculated by summing the contributions of the individual parameters, where a parameter
   *    of type long or double contributes two units to the length and a parameter of any other type contributes one unit.
   *
   * Arguments of type Double/Float contribute 2 "parameter units" to this limit, all other types contribute 1
   * "parameter unit". All instance methods for a class are passed a reference to the instance (`this), and hence,
   * they are permitted at most `JVM_METHOD_ARG_LIMIT-1` "parameter units" for their arguments.
   *
   * @see <a href="http://docs.oracle.com/javase/specs/jvms/se7/html/jvms-4.html#jvms-4.10">JVM Spec: Section 4.10</a>
   */
  private static final int JVM_METHOD_ARG_LIMIT = 255;

  /*
   * Note: This is protected instead of private only so it's visible for testing.
   */
  protected static final int MAX_FIELD_PARAMETER_UNIT_COUNT = JVM_METHOD_ARG_LIMIT - 1;

  public static enum FieldVisibility {
    PUBLIC, PUBLIC_DEPRECATED, PRIVATE
  }

  private final Set<Schema> queue = new HashSet<Schema>();
  private Protocol protocol;
  private VelocityEngine velocityEngine;
  private String templateDir;
  private FieldVisibility fieldVisibility = FieldVisibility.PUBLIC_DEPRECATED;
  private boolean createSetters = true;
  private boolean createAllArgsConstructor = true;
  private String outputCharacterEncoding;

  /*
   * Used in the record.vm template.
   */
  public boolean isCreateAllArgsConstructor() {
    return createAllArgsConstructor;
  }

  /* Reserved words for accessor/mutator methods */
  private static final Set<String> ACCESSOR_MUTATOR_RESERVED_WORDS =
      new HashSet<String>(Arrays.asList(new String[] {
            "class", "schema", "classSchema"
          }));
  static {
    // Add reserved words to accessor/mutator reserved words
    ACCESSOR_MUTATOR_RESERVED_WORDS.addAll(RESERVED_WORDS);
  }

  /* Reserved words for error types */
  private static final Set<String> ERROR_RESERVED_WORDS = new HashSet<String>(
      Arrays.asList(new String[] { "message", "cause" }));
  static {
    // Add accessor/mutator reserved words to error reserved words
    ERROR_RESERVED_WORDS.addAll(ACCESSOR_MUTATOR_RESERVED_WORDS);
  }

  private static final String FILE_HEADER =
      "/**\n" +
      " * Autogenerated by Avro\n" +
      " * \n" +
      " * DO NOT EDIT DIRECTLY\n" +
      " */\n";

  public SpecificCompiler(Protocol protocol) {
    this();
    // enqueue all types
    for (Schema s : protocol.getTypes()) {
      enqueue(s);
    }
    this.protocol = protocol;
  }

  public SpecificCompiler(Schema schema) {
    this();
    enqueue(schema);
    this.protocol = null;
  }

  SpecificCompiler() {
    this.templateDir =
      System.getProperty("org.apache.avro.specific.templates",
                         "/org/apache/avro/compiler/specific/templates/java/classic/");
    initializeVelocity();
  }

  /** Set the resource directory where templates reside. First, the compiler checks
   * the system path for the specified file, if not it is assumed that it is
   * present on the classpath.*/
  public void setTemplateDir(String templateDir) {
    this.templateDir = templateDir;
  }

  /**
   * @return true if the record fields should be marked as deprecated
   */
  public boolean deprecatedFields() {
    return (this.fieldVisibility == FieldVisibility.PUBLIC_DEPRECATED);
  }

  /**
   * @return true if the record fields should be public
   */
  public boolean publicFields() {
    return (this.fieldVisibility == FieldVisibility.PUBLIC ||
            this.fieldVisibility == FieldVisibility.PUBLIC_DEPRECATED);
  }

  /**
   * @return true if the record fields should be private
   */
  public boolean privateFields() {
    return (this.fieldVisibility == FieldVisibility.PRIVATE);
  }

  /**
   * Sets the field visibility option.
   */
  public void setFieldVisibility(FieldVisibility fieldVisibility) {
    this.fieldVisibility = fieldVisibility;
  }

  public boolean isCreateSetters() {
      return this.createSetters;
  }

  /**
   * Set to false to not create setter methods for the fields of the record.
   */
  public void setCreateSetters(boolean createSetters) {
    this.createSetters = createSetters;
  }

  private static String logChuteName = null;

  private void initializeVelocity() {
    this.velocityEngine = new VelocityEngine();

    // These  properties tell Velocity to use its own classpath-based
    // loader, then drop down to check the root and the current folder
    velocityEngine.addProperty("resource.loader", "class, file");
    velocityEngine.addProperty("class.resource.loader.class",
        "org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader");
    velocityEngine.addProperty("file.resource.loader.class",
        "org.apache.velocity.runtime.resource.loader.FileResourceLoader");
    velocityEngine.addProperty("file.resource.loader.path", "/, .");
    velocityEngine.setProperty("runtime.references.strict", true);

    // try to use Slf4jLogChute, but if we can't use the null one.
    if (null == logChuteName) {
      // multiple threads can get here concurrently, but that's ok.
      try {
        new Slf4jLogChute();
        logChuteName = Slf4jLogChute.class.getName();
      } catch (Exception e) {
        logChuteName = "org.apache.velocity.runtime.log.NullLogChute";
      }
    }
    velocityEngine.setProperty("runtime.log.logsystem.class", logChuteName);
  }

  /**
   * Captures output file path and contents.
   */
  static class OutputFile {
    String path;
    String contents;
    String outputCharacterEncoding;

    /**
     * Writes output to path destination directory when it is newer than src,
     * creating directories as necessary.  Returns the created file.
     */
    File writeToDestination(File src, File destDir) throws IOException {
      File f = new File(destDir, path);
      if (src != null && f.exists() && f.lastModified() >= src.lastModified())
        return f;                                 // already up to date: ignore
      f.getParentFile().mkdirs();
      Writer fw;
      if (outputCharacterEncoding != null) {
        fw = new OutputStreamWriter(new FileOutputStream(f), outputCharacterEncoding);
      } else {
        fw = new FileWriter(f);
      }
      try {
        fw.write(FILE_HEADER);
        fw.write(contents);
      } finally {
        fw.close();
      }
      return f;
    }
  }

  /**
   * Generates Java interface and classes for a protocol.
   * @param src the source Avro protocol file
   * @param dest the directory to place generated files in
   */
  public static void compileProtocol(File src, File dest) throws IOException {
    compileProtocol(new File[] {src}, dest);
  }

  /**
   * Generates Java interface and classes for a number of protocol files.
   * @param srcFiles the source Avro protocol files
   * @param dest the directory to place generated files in
   */
  public static void compileProtocol(File[] srcFiles, File dest) throws IOException {
    for (File src : srcFiles) {
      Protocol protocol = Protocol.parse(src);
      SpecificCompiler compiler = new SpecificCompiler(protocol);
      compiler.compileToDestination(src, dest);
    }
  }

  /** Generates Java classes for a schema. */
  public static void compileSchema(File src, File dest) throws IOException {
    compileSchema(new File[] {src}, dest);
  }

  /** Generates Java classes for a number of schema files. */
  public static void compileSchema(File[] srcFiles, File dest) throws IOException {
    Schema.Parser parser = new Schema.Parser();

    for (File src : srcFiles) {
      Schema schema = parser.parse(src);
      SpecificCompiler compiler = new SpecificCompiler(schema);
      compiler.compileToDestination(src, dest);
    }
  }

  /** Recursively enqueue schemas that need a class generated. */
  private void enqueue(Schema schema) {
    if (queue.contains(schema)) return;
    switch (schema.getType()) {
    case RECORD:
      queue.add(schema);
      for (Schema.Field field : schema.getFields())
        enqueue(field.schema());
      break;
    case MAP:
      enqueue(schema.getValueType());
      break;
    case ARRAY:
      enqueue(schema.getElementType());
      break;
    case UNION:
      for (Schema s : schema.getTypes())
        enqueue(s);
      break;
    case ENUM:
    case FIXED:
      queue.add(schema);
      break;
    case STRING: case BYTES:
    case INT: case LONG:
    case FLOAT: case DOUBLE:
    case BOOLEAN: case NULL:
      break;
    default: throw new RuntimeException("Unknown type: "+schema);
    }
  }

  /** Generate java classes for enqueued schemas. */
  Collection<OutputFile> compile() {
    List<OutputFile> out = new ArrayList<OutputFile>();
    for (Schema schema : queue) {
      out.add(compile(schema));
    }
    if (protocol != null) {
      out.add(compileInterface(protocol));
    }
    return out;
  }

  /** Generate output under dst, unless existing file is newer than src. */
  public void compileToDestination(File src, File dst) throws IOException {
    for (Schema schema : queue) {
      OutputFile o = compile(schema);
      o.writeToDestination(src, dst);
    }
    if (protocol != null) {
      compileInterface(protocol).writeToDestination(src, dst);
    }
  }

  private String renderTemplate(String templateName, VelocityContext context) {
    Template template;
    try {
      template = this.velocityEngine.getTemplate(templateName);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    StringWriter writer = new StringWriter();
    template.merge(context, writer);
    return writer.toString();
  }

  OutputFile compileInterface(Protocol protocol) {
    protocol = addStringType(protocol);           // annotate protocol as needed
    VelocityContext context = new VelocityContext();
    context.put("protocol", protocol);
    context.put("this", this);
    for (GenEntity ent : GenEntity.values()) {
      context.put( GenEntity.class.getSimpleName() + "_" + ent.toString(), ent);
    }
    String out = renderTemplate(templateDir+"protocol.vm", context);

    OutputFile outputFile = new OutputFile();
    String mangledName = mangle(protocol.getName());
    outputFile.path = makePath(mangledName, protocol.getNamespace());
    outputFile.contents = out;
    outputFile.outputCharacterEncoding = outputCharacterEncoding;
    return outputFile;
  }

  static String makePath(String name, String space) {
    if (space == null || space.isEmpty()) {
      return name + ".java";
    } else {
      return space.replace('.', File.separatorChar) + File.separatorChar + name
          + ".java";
    }
  }

  public static final ThreadLocal<File> SCHEMA_OUTPUT_DIR = new ThreadLocal<File>();

  private static void writeOutputSchema(Schema schema) {
    File outputDir = SCHEMA_OUTPUT_DIR.get();
    if (outputDir != null) {
          String name = mangle(schema.getName());
          String nameSpace = schema.getNamespace();
          String path;
          if (nameSpace == null || nameSpace.isEmpty()) {
            path = name + ".avsc";
          } else {
            path = nameSpace.replace('.', File.separatorChar) + File.separatorChar + name
                + ".avsc";
          }
          File destFile = new File(outputDir.getPath() + File.separatorChar + path);
          File destFolder = destFile.getParentFile();
          if (destFolder.exists()) {
            if (!destFolder.isDirectory()) {
              throw new RuntimeException(destFolder + " should be a folder");
            }
          } else {
            if (!destFolder.mkdirs()) {
              throw new RuntimeException("Cannot create folder " + destFolder);
            }
          }
          try {
            Writer writer = new OutputStreamWriter(new FileOutputStream(destFile),
                    StandardCharsets.UTF_8);
            try {
              writer.append(schema.toString());
            } finally {
              writer.close();
            }
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
    }
  }


  /**
   * Returns the number of parameter units required by fields for the
   * AllArgsConstructor.
   *
   * @param record a Record schema
   */
  protected int calcAllArgConstructorParameterUnits(Schema record) {

    if (record.getType() != Schema.Type.RECORD)
      throw new RuntimeException("This method must only be called for record schemas.");

    return record.getFields().size();
  }

  protected void validateRecordForCompilation(Schema record) {
    this.createAllArgsConstructor =
        calcAllArgConstructorParameterUnits(record) <= MAX_FIELD_PARAMETER_UNIT_COUNT;

    if (!this.createAllArgsConstructor)
      new Slf4jLogChute().log(LogChute.WARN_ID, "Record '" + record.getFullName() +
              "' contains more than " + MAX_FIELD_PARAMETER_UNIT_COUNT +
              " parameters which exceeds the JVM " +
              "spec for the number of permitted constructor arguments. Clients must " +
              "rely on the builder pattern to create objects instead. For more info " +
              "see JIRA ticket AVRO-1642.");
  }

  public OutputFile compile(Schema schema) {
    schema = addStringType(schema);               // annotate schema as needed
    String output = "";
    VelocityContext context = new VelocityContext();
    context.put("this", this);
    context.put("schema", schema);
    context.put("esc",  new StringEscapeUtils());
    for (GenEntity ent : GenEntity.values()) {
      context.put( GenEntity.class.getSimpleName() + "_" + ent.toString(), ent);
    }
    switch (schema.getType()) {
    case RECORD:
      validateRecordForCompilation(schema);
      output = renderTemplate(templateDir+"record.vm", context);
      writeOutputSchema(schema);
      break;
    case ENUM:
      output = renderTemplate(templateDir+"enum.vm", context);
      writeOutputSchema(schema);
      break;
    case FIXED:
      output = renderTemplate(templateDir+"fixed.vm", context);
      break;
    case BOOLEAN:
    case NULL:
      break;
    default: throw new RuntimeException("Unknown type: "+schema);
    }

    OutputFile outputFile = new OutputFile();
    String name = mangle(schema.getName());
    outputFile.path = makePath(name, schema.getNamespace());
    outputFile.contents = output;
    outputFile.outputCharacterEncoding = outputCharacterEncoding;
    return outputFile;
  }

  private StringType stringType = StringType.CharSequence;

  /** Set the Java type to be emitted for string schemas. */
  public void setStringType(StringType t) { this.stringType = t; }

  // annotate map and string schemas with string type
  private Protocol addStringType(Protocol p) {
    if (stringType != StringType.String)
      return p;

    Protocol newP = new Protocol(p.getName(), p.getDoc(), p.getNamespace());
    Map<Schema,Schema> types = new LinkedHashMap<Schema,Schema>();

    // Copy properties
    for (Map.Entry<String,JsonNode> prop : p.getJsonProps().entrySet())
      newP.addProp(prop.getKey(), prop.getValue());   // copy props

    // annotate types\
    final Collection<Schema> pTypes = p.getTypes();
    Collection<Schema> namedTypes = new LinkedHashSet<Schema>(pTypes.size());
    for (Schema s : pTypes)
      namedTypes.add(addStringType(s, types));
    newP.setTypes(namedTypes);

    // annotate messages
    Map<String,Message> newM = newP.getMessages();
    for (Message m : p.getMessages().values())
      newM.put(m.getName(), m.isOneWay()
               ? newP.createMessage(m.getName(), m.getDoc(), m.getJsonProps(),
                                    addStringType(m.getRequest(), types))
               : newP.createMessage(m.getName(), m.getDoc(), m.getJsonProps(),
                                    addStringType(m.getRequest(), types),
                                    addStringType(m.getResponse(), types),
                                    addStringType(m.getErrors(), types)));
    return newP;
  }

  private Schema addStringType(Schema s) {
    if (stringType != StringType.String)
      return s;
    return addStringType(s, new LinkedHashMap<Schema,Schema>());
  }

  // annotate map and string schemas with string type
  private Schema addStringType(Schema s, Map<Schema,Schema> seen) {
    if (seen.containsKey(s)) return seen.get(s); // break loops
    Schema result = s;
    switch (s.getType()) {
    case STRING:
      result = Schema.create(Schema.Type.STRING);
      if (s.getLogicalType() == null) {
        GenericData.setStringType(result, stringType);
      }
      break;
    case RECORD:
      result =
        Schema.createRecord(s.getFullName(), s.getDoc(), null, s.isError());
      for (String alias : s.getAliases())
        result.addAlias(alias, null);             // copy aliases
      seen.put(s, result);
      List<Field> newFields = new ArrayList<Field>();
      for (Field f : s.getFields()) {
        Schema fSchema = addStringType(f.schema(), seen);
        Field newF =
          new Field(f.name(), fSchema, f.doc(), f.defaultValue(), f.order());
        for (Map.Entry<String,JsonNode> p : f.getJsonProps().entrySet())
          newF.addProp(p.getKey(), p.getValue()); // copy props
        for (String a : f.aliases())
          newF.addAlias(a);                       // copy aliases
        newFields.add(newF);
      }
      result.setFields(newFields);
      break;
    case ARRAY:
      Schema e = addStringType(s.getElementType(), seen);
      result = Schema.createArray(e);
      break;
    case MAP:
      Schema v = addStringType(s.getValueType(), seen);
      result = Schema.createMap(v);
      GenericData.setStringType(result, stringType);
      break;
    case UNION:
      List<Schema> types = new ArrayList<Schema>();
      for (Schema branch : s.getTypes())
        types.add(addStringType(branch, seen));
      result = Schema.createUnion(types);
      break;
    }
    for (Map.Entry<String,JsonNode> p : s.getJsonProps().entrySet()) {
      result.addProp(p.getKey(), p.getValue());   // copy props
    }
    final LogicalType logicalType = s.getLogicalType();
    if (result != s && logicalType != null) {
      logicalType.addToSchema(result);
    }
    seen.put(s, result);
    return result;
  }

  private String getStringType(JsonNode overrideClassProperty) {
    if (overrideClassProperty != null)
      return overrideClassProperty.textValue();
    switch (stringType) {
    case String:        return "java.lang.String";
    case Utf8:          return "org.apache.avro.util.Utf8";
    case CharSequence:  return "java.lang.CharSequence";
    default: throw new RuntimeException("Unknown string type: "+stringType);
   }
  }

  private static final Schema NULL_SCHEMA = Schema.create(Schema.Type.NULL);

  /** Utility for template use.  Returns the java type for a Schema. */
  public String javaType(Schema schema) {
      LogicalType logicalType = schema.getLogicalType();
      if (logicalType != null) {
          Conversion<Object> conversion = GenericData.get().getConversionFor(logicalType);
          if (conversion != null) {
            return conversion.getConvertedType().getName();
          } else {
            return javaTypeInternal(schema);
          }
      } else {
         return javaTypeInternal(schema);
      }
  }

  public String javaTypeInternal(Schema schema) {
    switch (schema.getType()) {
    case RECORD:
    case ENUM:
    case FIXED:
      return mangle(schema.getFullName());
    case ARRAY:
      return "java.util.List<" + javaType(schema.getElementType()) + ">";
    case MAP:
      return "java.util.Map<"
        + getStringType(schema.getJsonProp(SpecificData.KEY_CLASS_PROP))+","
        + javaType(schema.getValueType()) + ">";
    case UNION:
      List<Schema> types = schema.getTypes(); // elide unions with null
      if ((types.size() == 2) && types.contains(NULL_SCHEMA))
        return javaType(types.get(types.get(0).equals(NULL_SCHEMA) ? 1 : 0));
      return "java.lang.Object";
    case STRING:
      return getStringType(schema.getJsonProp(SpecificData.CLASS_PROP));
    case BYTES:   return "java.nio.ByteBuffer";
    case INT:     return "java.lang.Integer";
    case LONG:    return "java.lang.Long";
    case FLOAT:   return "java.lang.Float";
    case DOUBLE:  return "java.lang.Double";
    case BOOLEAN: return "java.lang.Boolean";
    case NULL:    return "java.lang.Void";
    default: throw new RuntimeException("Unknown type: "+schema);
    }
  }

  /** Utility for template use.  Returns the unboxed java type for a Schema. */
  public String javaUnbox(Schema schema) {
    switch (schema.getType()) {
    case INT:     return schema.getLogicalType() == null ? "int" : javaType(schema);
    case LONG:    return schema.getLogicalType() == null ? "long" : javaType(schema);
    case FLOAT:   return schema.getLogicalType() == null ? "float" : javaType(schema);
    case DOUBLE:  return schema.getLogicalType() == null ? "double" : javaType(schema);
    case BOOLEAN: return schema.getLogicalType() == null ? "boolean" : javaType(schema);
    default:      return javaType(schema);
    }
  }



  /**
   * @deprecated use Set<String> javaAnnotations(JsonProperties props, GenEntity entity) variant.
   */
  @Deprecated
  public Set<String> javaAnnotations(JsonProperties props) {
    Set<String> result = new HashSet<>();
    for (GenEntity entity : GenEntity.values()) {
      result.addAll(javaAnnotations(props, entity));
    }
    return result;
  }

  /** Utility for template use.  Returns the java annotations for a schema element. */
  public Set<String> javaAnnotations(JsonProperties props, GenEntity entity) {
    return CompilerExtensions.generate(this, entity, props);
  }

  // maximum size for string constants, to avoid javac limits
  int maxStringChars = 8192;

  /** Utility for template use. Takes a (potentially overly long) string and
   *  splits it into a quoted, comma-separted sequence of escaped strings.
   *  @param s The string to split
   *  @return A sequence of quoted, comma-separated, escaped strings
   */
  public String javaSplit(String s) throws IOException {
    StringBuilder b = new StringBuilder("\"");    // initial quote
    for (int i = 0; i < s.length(); i += maxStringChars) {
      if (i != 0) b.append("\",\"");              // insert quote-comma-quote
      String chunk = s.substring(i, Math.min(s.length(), i + maxStringChars));
      b.append(javaEscape(chunk));                // escape chunks
    }
    b.append("\"");                               // final quote
    return b.toString();
  }

  /** Utility for template use.  Escapes quotes and backslashes. */
  public static String javaEscape(Object o) {
      return o.toString().replace("\\","\\\\").replace("\"", "\\\"");
  }

  /** Utility for template use.  Escapes comment end with HTML entities. */
  public static String escapeForJavadoc(String s) {
      return s.replace("*/", "*&#47;");
  }

  /** Utility for template use.  Returns empty string for null. */
  public static String nullToEmpty(String x) {
    return x == null ? "" : x;
  }

  /** Utility for template use.  Adds a dollar sign to reserved words. */
  public static String mangle(String word) {
    return mangle(word, false);
  }

  /** Utility for template use.  Adds a dollar sign to reserved words. */
  public static String mangle(String word, boolean isError) {
    return mangle(word, isError ? ERROR_RESERVED_WORDS : RESERVED_WORDS);
  }

  /** Utility for template use.  Adds a dollar sign to reserved words. */
  public static String mangle(String word, Set<String> reservedWords) {
    return mangle(word, reservedWords, false);
  }

  /** Utility for template use.  Adds a dollar sign to reserved words. */
  public static String mangle(String word, Set<String> reservedWords,
      boolean isMethod) {
    if (word.contains(".")) {
      // If the 'word' is really a full path of a class we must mangle just the classname
      int lastDot = word.lastIndexOf(".");
      String packageName = word.substring(0, lastDot + 1);
      String className = word.substring(lastDot + 1);
      return packageName + mangle(className, reservedWords, isMethod);
    }
    if (reservedWords.contains(word) ||
        (isMethod && reservedWords.contains(
            Character.toLowerCase(word.charAt(0)) +
            ((word.length() > 1) ? word.substring(1) : "")))) {
      return word + "$";
    }
    return word;
  }

  /** Utility for use by templates. Return schema fingerprint as a long. */
  public static long fingerprint64(Schema schema) {
    return SchemaNormalization.parsingFingerprint64(schema);
  }

  /**
   * Generates the name of a field accessor method.
   * @param schema the schema in which the field is defined.
   * @param field the field for which to generate the accessor name.
   * @return the name of the accessor method for the given field.
   */
  public static String generateGetMethod(Schema schema, Field field) {
    return generateMethodName(schema, field, "get", "");
  }

  /**
   * Generates the name of a field mutator method.
   * @param schema the schema in which the field is defined.
   * @param field the field for which to generate the mutator name.
   * @return the name of the mutator method for the given field.
   */
  public static String generateSetMethod(Schema schema, Field field) {
    return generateMethodName(schema, field, "set", "");
  }

  /**
   * Generates the name of a field "has" method.
   * @param schema the schema in which the field is defined.
   * @param field the field for which to generate the "has" method name.
   * @return the name of the has method for the given field.
   */
  public static String generateHasMethod(Schema schema, Field field) {
    return generateMethodName(schema, field, "has", "");
  }

  /**
   * Generates the name of a field "clear" method.
   * @param schema the schema in which the field is defined.
   * @param field the field for which to generate the accessor name.
   * @return the name of the has method for the given field.
   */
  public static String generateClearMethod(Schema schema, Field field) {
    return generateMethodName(schema, field, "clear", "");
  }

  /** Utility for use by templates. Does this schema have a Builder method? */
  public static boolean hasBuilder(Schema schema) {
    switch (schema.getType()) {
      case RECORD:
        return schema.getLogicalType() == null;
      case UNION:
        List<Schema> types = schema.getTypes(); // elide unions with null
        if ((types.size() == 2) && types.contains(NULL_SCHEMA)) {
          return hasBuilder(types.get(types.get(0).equals(NULL_SCHEMA) ? 1 : 0));
        }
        return false;

      default:
        return false;
    }
  }

  /**
   * Generates the name of a field Builder accessor method.
   * @param schema the schema in which the field is defined.
   * @param field the field for which to generate the Builder accessor name.
   * @return the name of the Builder accessor method for the given field.
   */
  public static String generateGetBuilderMethod(Schema schema, Field field) {
    return generateMethodName(schema, field, "get", "Builder");
  }

  /**
   * Generates the name of a field Builder mutator method.
   * @param schema the schema in which the field is defined.
   * @param field the field for which to generate the Builder mutator name.
   * @return the name of the Builder mutator method for the given field.
   */
  public static String generateSetBuilderMethod(Schema schema, Field field) {
    return generateMethodName(schema, field, "set", "Builder");
  }

  /**
   * Generates the name of a field Builder "has" method.
   * @param schema the schema in which the field is defined.
   * @param field the field for which to generate the "has" Builder method name.
   * @return the name of the "has" Builder method for the given field.
   */
  public static String generateHasBuilderMethod(Schema schema, Field field) {
    return generateMethodName(schema, field, "has", "Builder");
  }

  /**
   * Generates a method name from a field name.
   * @param schema the schema in which the field is defined.
   * @param field the field for which to generate the accessor name.
   * @param prefix method name prefix, e.g. "get" or "set".
   * @param postfix method name postfix, e.g. "" or "Builder".
   * @return the generated method name.
   */
  private static String generateMethodName(Schema schema, Field field,
      String prefix, String postfix) {

    // Check for the special case in which the schema defines two fields whose
    // names are identical except for the case of the first character:
    char firstChar = field.name().charAt(0);
    String conflictingFieldName = (Character.isLowerCase(firstChar) ?
        Character.toUpperCase(firstChar) : Character.toLowerCase(firstChar)) +
        (field.name().length() > 1 ? field.name().substring(1) : "");
    boolean fieldNameConflict = schema.getField(conflictingFieldName) != null;

    StringBuilder methodBuilder = new StringBuilder(prefix);
    String fieldName = mangle(field.name(),
        schema.isError() ? ERROR_RESERVED_WORDS :
          ACCESSOR_MUTATOR_RESERVED_WORDS, true);

    boolean nextCharToUpper = true;
    for (int ii = 0; ii < fieldName.length(); ii++) {
      if (fieldName.charAt(ii) == '_') {
        nextCharToUpper = true;
      }
      else if (nextCharToUpper) {
        methodBuilder.append(Character.toUpperCase(fieldName.charAt(ii)));
        nextCharToUpper = false;
      }
      else {
        methodBuilder.append(fieldName.charAt(ii));
      }
    }
    methodBuilder.append(postfix);

    // If there is a field name conflict append $0 or $1
    if (fieldNameConflict) {
      if (methodBuilder.charAt(methodBuilder.length() - 1) != '$') {
        methodBuilder.append('$');
      }
      methodBuilder.append(Character.isLowerCase(firstChar) ? '0' : '1');
    }

    return methodBuilder.toString();
  }

  /** Tests whether an unboxed Java type can be set to null */
  public static boolean isUnboxedJavaTypeNullable(Schema schema) {
    switch (schema.getType()) {
    // Primitives can't be null; assume anything else can
    case INT:
    case LONG:
    case FLOAT:
    case DOUBLE:
    case BOOLEAN: return false;
    default: return true;
    }
  }

  public static void main(String[] args) throws Exception {
    //compileSchema(new File(args[0]), new File(args[1]));
    compileProtocol(new File(args[0]), new File(args[1]));
  }

  public static final class Slf4jLogChute implements LogChute {
    private Logger logger = LoggerFactory.getLogger("AvroVelocityLogChute");
    @Override
    public void init(RuntimeServices rs) throws Exception {
      // nothing to do
    }

    @Override
    public void log(int level, String message) {
      switch (level) {
      case LogChute.DEBUG_ID:
        logger.debug(message);
        break;
      case LogChute.TRACE_ID:
        logger.trace(message);
        break;
      case LogChute.WARN_ID:
        logger.warn(message);
        break;
      case LogChute.ERROR_ID:
        logger.error(message);
        break;
      default:
      case LogChute.INFO_ID:
        logger.info(message);
        break;
      }
    }

    @Override
    public void log(int level, String message, Throwable t) {
      switch (level) {
      case LogChute.DEBUG_ID:
        logger.debug(message, t);
        break;
      case LogChute.TRACE_ID:
        logger.trace(message, t);
        break;
      case LogChute.WARN_ID:
        logger.warn(message, t);
        break;
      case LogChute.ERROR_ID:
        logger.error(message, t);
        break;
      default:
      case LogChute.INFO_ID:
        logger.info(message, t);
        break;
      }
    }

    @Override
    public boolean isLevelEnabled(int level) {
      switch (level) {
      case LogChute.DEBUG_ID:
        return logger.isDebugEnabled();
      case LogChute.TRACE_ID:
        return logger.isTraceEnabled();
      case LogChute.WARN_ID:
        return logger.isWarnEnabled();
      case LogChute.ERROR_ID:
        return logger.isErrorEnabled();
      default:
      case LogChute.INFO_ID:
        return logger.isInfoEnabled();
      }
    }
  }

  /** Sets character encoding for generated java file
  * @param outputCharacterEncoding Character encoding for output files (defaults to system encoding)
  */
  public void setOutputCharacterEncoding(String outputCharacterEncoding) {
    this.outputCharacterEncoding = outputCharacterEncoding;
  }

  private static final void indent(StringBuilder builder, int indentation) {
    for (int i = 0; i < indentation; i++) {
      builder.append(' ');
    }
  }

  public String extraFieldDoc(final Field field) {
    return extraFieldDoc(field, 3);
  }

  public String extraFieldDoc(final Field field, final int indentation) {
    Schema schema = field.schema();
    if (schema.getType() == Schema.Type.UNION
            && "java.lang.Object".equals(javaUnbox(schema))) {
      StringBuilder sb = new StringBuilder(80);
      sb.append("<p>The following types are supported:</p>\n");
      indent(sb, indentation);
      sb.append("* <ul>\n");
      for (Schema us : schema.getTypes()) {
         if (us.getType() == Schema.Type.NULL) {
           continue;
         }
         indent(sb, indentation);
         sb.append("* <li>@see ");
         sb.append(HtmlEscapers.htmlEscaper().escape(javaUnbox(us)));
         sb.append("</li>\n");
      }
      indent(sb, indentation);
      sb.append("* </ul>");
      return sb.toString();
    } else {
      return null;
    }
  }

}

