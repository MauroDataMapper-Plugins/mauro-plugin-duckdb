## Usage

```
Usage: mauro-plugin-duckdb-csv [-hV] [--ignore-csv-errors] [--quiet] [--summary]
       [--[no-]detect-enumerations] [--[no-]detect-types]
       [--[no-]first-row-is-header] [--[no-]generate-summary-metadata]
       [--headers=<headers>] [--log-file=<logFile>] [-m=<modelName>]
       [--max-enumerations=<maxEnumerations>]
       [--metadata-namespace=<metadataNamespace>] -o=<output>
       [--output-structure=<outputStructure>] -s=<sourceFile>
       [--temp=<tempDirectory>]
       [--enum-given-adornment=<enumGivenAdornment>[,<enumGivenAdornment>...]]...
       [--enum-ignore-column=<enumIgnoreColumn>[,<enumIgnoreColumn>...]]...
       [--enum-ignore-column-like=<enumIgnoreColumnLike>[,<enumIgnoreColumnLike>...]]...
       [--enum-lookup-adornment=<enumLookupAdornment>[,<enumLookupAdornment>...]]...
       [--exclude-schema=<excludeSchemas>[,<excludeSchemas>...]]...
       [--excluded-columns=<excludeColumnsLike>[,<excludeColumnsLike>...]]...
       [--excluded-tables=<excludeTablesLike>[,<excludeTablesLike>...]]...
       [--include-schema=<includeSchemas>[,<includeSchemas>...]]...
       [--included-columns=<includeColumnsLike>[,<includeColumnsLike>...]]...
       [--included-tables=<includeTablesLike>[,<includeTablesLike>...]]...
       [@<filename>...]
Catalogue CSV, TSV, PSV, or TAB extracts as Mauro data models.
      [@<filename>...]      One or more argument files containing options.
      --[no-]detect-enumerations
                            Detect enumeration columns (use
                              --no-detect-enumerations to disable).
      --[no-]detect-types   Infer column types from CSV values (use
                              --no-detect-types to import values as strings).
      --quiet               Suppress console log messages; file logging is
                              unaffected.
      --summary             Print an output summary, including when --quiet is
                              set.
      --enum-given-adornment=<enumGivenAdornment>[,<enumGivenAdornment>...]
                            Column suffixes that identify enumerations, e.g.
                              _IND.
      --enum-ignore-column=<enumIgnoreColumn>[,<enumIgnoreColumn>...]
                            Exact column names excluded from enumeration
                              detection.
      --enum-ignore-column-like=<enumIgnoreColumnLike>[,
        <enumIgnoreColumnLike>...]
                            Column-name fragments excluded from enumeration
                              detection.
      --enum-lookup-adornment=<enumLookupAdornment>[,<enumLookupAdornment>...]
                            Column suffixes that identify coded enumeration
                              columns.
      --exclude-schema=<excludeSchemas>[,<excludeSchemas>...]
                            Exclude directory names matching SQL LIKE
                              pattern(s), using % and _ wildcards.
      --excluded-columns=<excludeColumnsLike>[,<excludeColumnsLike>...]
                            Exclude column names matching SQL LIKE pattern(s),
                              using % and _ wildcards.
      --excluded-tables=<excludeTablesLike>[,<excludeTablesLike>...]
                            Exclude CSV table names matching SQL LIKE
                              pattern(s), using % and _ wildcards.
      --[no-]first-row-is-header
                            Treat the first row as column headers (use
                              --no-first-row-is-header otherwise).
      --[no-]generate-summary-metadata
                            Generate value distribution summaries (use
                              --no-generate-summary-metadata to disable).
      --ignore-csv-errors   Allow DuckDB to skip malformed rows after strict
                              CSV parsing attempts fail; emits a warning.
  -h, --help                Show this help message and exit.
      --headers=<headers>   Comma-separated replacement headers, used when the
                              first row is not a header.
      --include-schema=<includeSchemas>[,<includeSchemas>...]
                            Include directory names matching SQL LIKE
                              pattern(s), using % and _ wildcards.
      --included-columns=<includeColumnsLike>[,<includeColumnsLike>...]
                            Include column names matching SQL LIKE pattern(s),
                              using % and _ wildcards.
      --included-tables=<includeTablesLike>[,<includeTablesLike>...]
                            Include CSV table names matching SQL LIKE
                              pattern(s), using % and _ wildcards.
      --log-file=<logFile>  Append run logs to this file.
  -m, --model-name=<modelName>
                            Override the generated DataModel or VersionedFolder
                              label.
      --max-enumerations=<maxEnumerations>
                            Maximum distinct values for automatic enumeration
                              detection.
      --metadata-namespace=<metadataNamespace>
                            Override the stable DuckDB namespace used for
                              source and profiling metadata.
  -o, --output=<output>     Output JSON file.
      --output-structure=<outputStructure>
                            Output structure: DATA_MODEL (default) or
                              VERSIONED_FOLDER (immediate child directories
                              become DataModels).
  -s, --source=<sourceFile> Input CSV/TSV/PSV/TAB file, ZIP archive, or
                              directory tree.
      --temp=<tempDirectory>
                            Directory for all temporary and transitional files;
                              created if needed.
  -V, --version             Print version information and exit.
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


