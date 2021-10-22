# Apache AVRO
Apache Avro™ is a data serialization system.

Learn more about Avro, please visit our website at:

  http://avro.apache.org/

## Overview

  * 1.10.0.5p - latest version, based on jackson 2.9+, aims to be api compatible with avro 1.10.X
  * 1.8.1.50p - latest version, based on jackson 1.9

 This fork, contains numerous fixes, and features not implement (but reported) in the original project.
 This fork focuses only on the java implementation. For non-java part please see official fork or other forks.

 Starting with version 1.10.0.4p the additional logical types: any, big-integer, duration, instant,
 json*, regexp, schema temporal url, uri have moved to a separate library, which you will need to additionally include:

```xml
    <dependency>
      <groupId>org.spf4j</groupId>
      <artifactId>avro-logical-types-fork</artifactId>
      <version>1.1</version>
      <scope>test</scope>
    </dependency>
```
  you can try using avro-logical-types-official for using those logical types with the official avro library.

Join the discussion on Gitter: [![Gitter chat](https://badges.gitter.im/zolyfarkas/spf4j-avro.png)](https://gitter.im/spf4j-avro/Lobby)


## Getting started:

 [ ![Download latest](https://api.bintray.com/packages/zolyfarkas/core/avro/images/download.svg) ](https://bintray.com/zolyfarkas/core/avro/_latestVersion)

This fork is published to github packages, and you can use it by adding the repositories to your pom.xml
 ([see](https://docs.github.com/en/packages/guides/configuring-apache-maven-for-use-with-github-packages) for more info):

            <repositories>
              <repository>
                <id>github</id>
                <url>https://maven.pkg.github.com/zolyfarkas/*</url>
                <snapshots>
                  <enabled>true</enabled>
                </snapshots>
              </repository>
            </repositories>
            <pluginRepositories>
                <pluginRepository>
                  <id>github</id>
                  <url>https://maven.pkg.github.com/zolyfarkas/*</url>
                  <snapshots>
                    <enabled>true</enabled>
                  </snapshots>
                </pluginRepository>
            </pluginRepositories>

 and reference the avro lib as:

            <dependency>
              <groupId>org.apache.avro</groupId>
              <artifactId>avro</artifactId>
              <version>1.10.0.1p</version>
              <type>pom</type>
            </dependency>


  previous versions have been published to bintray,
  and should be available there (if I interpret the deco text correctly) until Feb 1st, 2022.



## Features implemented in this fork on the java side:

### 1) ExtendedJsonDecoder/Encoder -  encodes unions of null and a single type as a more normal key=value rather than key={type=value}.
 ExtendedJsonDecoder will infer missing fields using the default values from the write'r schema and as such need not be present in the serialized json. The Extended[Generic|Specific]DatumWriter can be used to omit serializing fields that equal the  default values defined in the schema. This can make the json payload smaller than the binary payload in some use cases.

for
```
union {null, string} field = null;
```
instead of:
```
"field" : {"string" : "value"}
```
the ExtendedJsonEncoder will serialize:
```
"field" : "value"
```

### 2) Evolvable enums, with custom string values, where additionally you can rename enum values to maintain backward compatibility. Example:

```
@symbolAliases({"DIAMONDS" : ["Bling Bling", "ROCKS"]}) // you can correct bad names backward compatibly.
@stringSymbols({"SPADES" : "S P A D E S"}) //you can have stringValues (toString()) that are not restricted to identifiers
 enum Suit {
  UNKNOWN, SPADES, DIAMONDS, CLUBS, HEARTS
} = "UNKNOWN";
```

### 3) Generated java classes are annotated with @Nullable or @Nonnull as appropriate. As such compiling in conjuction with tools like spotbugs or google error-prone will result in better quality code.

### 4) @Immutable record support. (setters will not be generated). This encourages use of builders and constructors to build the records.

### 5) Numerous bug fixes and performance enhancements.

### 6) Enhanced decimal logical type support, (custom binary encoding, or official controllable via @format("official") or feature flag) where JSON serialization is a proper number. (not bytes/string)

for
```
@logicalType("decimal") bytes field = "";
```
instead of:
```
"field" : "bytesvalue"
```
the ExtendedJsonEncoder will serialize:
```
"field" : 123.45
```

### 7) json_record, json_array, json_any logical type support.
 
for
```
@logicalType("json_record") string field = "{}";
```
Avro JsonEncoder will serialize:
```
"field" : "{ \"field\" : \"value \"}"
```
the ExtendedJsonEncoder will serialize:
```
"field" : {"field" : "value"}
```

### 8) any type support. implemented via logicalTypes, with clean Json image.

```
@logicalType("any")
record AnyType {
  string avsc;
  bytes content;
}
```

using this type like:

```
...
 AnyType field;
...
```

will serialize in json to:

```
"field" :  {
  "avsc" : ... { the avro  schema } ...
  "content" : ... { the json image } ...
}
```

### 9) @deprecated support for record and fields. You can use @deprecated to deprecate avro records.

### 10) @beta @deprecated schema annotations support for your data model lifecycle.

### 11) Improved generated javadoc. (unions list all supported types)

### 12) url and uri logical type support.

### 13) bigint, uuid, date, instant logical types support.

### 14) mixin support in AVDL.

### 15) temporal logical type support, which is the equivalent of union{year, yearmonth, yearquarter, date, date time}.

### 16) regex logical type support.

### 17) Duration logical type support. ex: "PT1H"...

## Examples for above  in action:

 * [core-schema](https://github.com/zolyfarkas/core-schema)
 * [generic examples](https://github.com/zolyfarkas/avro-schema-examples)

 for more info see: https://bintray.com/zolyfarkas/core/avro

# Building jars

Run `./build.sh docker` and once inside docker container run `mvn -P dist package -DskipTests javadoc:aggregate -Denforcer.skip`

Jars will be available in lang/java/target

Run `mvn deploy` to upload them to Google Artifact Registry

Make sure to change the version in lang/java/avro pom.xml. Otherwise the upload will fail

