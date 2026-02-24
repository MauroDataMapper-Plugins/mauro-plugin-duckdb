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


