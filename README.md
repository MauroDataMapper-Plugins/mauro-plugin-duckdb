## Usage

```
Usage: mauro-plugin-duckdb-csv [-hV] [-m=<modelName>] -o=<output>
                               -s=<sourceFile> [@<filename>...]
Extract metadata from CSV via DuckDB schema to Mauro JSON
      [@<filename>...]    One or more argument files containing options.
  -h, --help              Show this help message and exit.
  -m, --model-name=<modelName>
                          Label of Model
  -o, --output=<output>   Output file
  -s, --source=<sourceFile>
                          The source: zip, csv, or directory
  -V, --version           Print version information and exit.
```

Example using gradlew:

```bash
./gradlew run --args='-s csv_directory -o csv_datamodel.json'
```

### CSV catalogue fixtures

Checked-in CSV trees under `src/test/resources/csv-catalogues` mirror representative Oracle and SQL Server test schemas and include nested-directory, quoted-value, and headerless TSV cases. The CLI tests import these directories directly.

Try the SQL Server shaped catalogue with table and column selection:

```bash
./gradlew run --args='-s src/test/resources/csv-catalogues/sqlserver/metadata_simple --included-tables organisation --included-columns org_type -o /tmp/organisation.json'
```

Try the multi-model VersionedFolder fixture with explicit headers for the headerless TSV:

```bash
./gradlew run --args='-s src/test/resources/csv-catalogues/edge_cases --output-structure VERSIONED_FOLDER --included-tables headerless --no-first-row-is-header --headers code,value -o /tmp/edge-catalogue.json'
```

### Standalone command-line jar

Build a single jar containing the CLI and its runtime dependencies:

```bash
./gradlew clean shadowJar
```

The artifact is written to `build/libs/mauro-plugin-duckdb-cli-<version>-standalone.jar`. Copy that jar to the target machine and run it with Java 17 or later; the target machine does not need Gradle or network access:

```bash
java -jar mauro-plugin-duckdb-cli-<version>-standalone.jar --help
java -jar mauro-plugin-duckdb-cli-<version>-standalone.jar --source extracts -o catalogue.json
```

---

### Micronaut 4.5.0 Documentation

- [User Guide](https://docs.micronaut.io/4.5.0/guide/index.html)
- [API Reference](https://docs.micronaut.io/4.5.0/api/index.html)
- [Configuration Reference](https://docs.micronaut.io/4.5.0/guide/configurationreference.html)
- [Micronaut Guides](https://guides.micronaut.io/index.html)


- [Micronaut Gradle Plugin documentation](https://micronaut-projects.github.io/micronaut-gradle-plugin/latest/)
- [Shadow Gradle Plugin](https://plugins.gradle.org/plugin/com.github.johnrengelman.shadow)

### Feature serialization-jackson documentation

- [Micronaut Serialization Jackson Core documentation](https://micronaut-projects.github.io/micronaut-serialization/latest/guide/)


