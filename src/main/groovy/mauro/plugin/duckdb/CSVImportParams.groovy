package mauro.plugin.duckdb

import org.maurodata.plugin.importer.config.ImportGroupConfig
import org.maurodata.plugin.importer.config.ImportParameterConfig
import java.nio.file.Path

class CSVImportParams extends DuckDBImportParams {
    Path tempDirectory
    CSVRunSummary runSummary

    @ImportParameterConfig(displayName = 'Ignore CSV errors', description = 'Allow DuckDB to skip malformed CSV rows after strict parsing attempts fail.', order = 7, optional = true, group = @ImportGroupConfig(name = 'Configuration', order = 2))
    Boolean ignoreCsvErrors = false

    @ImportParameterConfig(displayName = 'Output structure', description = 'Import the source as one DataModel or as a VersionedFolder whose immediate child directories are DataModels.', order = 1, group = @ImportGroupConfig(name = 'Output', order = 1))
    String outputStructure = 'DATA_MODEL'

    @ImportParameterConfig(displayName = 'DuckDB metadata namespace', description = 'Namespace used for metadata produced by DuckDB. Defaults to the stable DuckDB namespace.', order = 2, optional = true, group = @ImportGroupConfig(name = 'Metadata', order = 2))
    String metadataNamespace

    @ImportParameterConfig(displayName = 'Included schemas', description = 'Import only schema-like directory names matching these SQL LIKE patterns.', order = 1, optional = true, group = @ImportGroupConfig(name = 'Selection', order = 3))
    List<String> includeSchemas = []

    @ImportParameterConfig(displayName = 'Excluded schemas', description = 'Exclude schema-like directory names matching these SQL LIKE patterns.', order = 2, optional = true, group = @ImportGroupConfig(name = 'Selection', order = 3))
    List<String> excludeSchemas = []

    @ImportParameterConfig(displayName = 'Included tables', description = 'Import only CSV table names matching these SQL LIKE patterns.', order = 3, optional = true, group = @ImportGroupConfig(name = 'Selection', order = 3))
    List<String> includeTablesLike = []

    @ImportParameterConfig(displayName = 'Excluded tables', description = 'Exclude CSV table names matching these SQL LIKE patterns.', order = 4, optional = true, group = @ImportGroupConfig(name = 'Selection', order = 3))
    List<String> excludeTablesLike = []

    @ImportParameterConfig(displayName = 'Included columns', description = 'Import only column names matching these SQL LIKE patterns.', order = 5, optional = true, group = @ImportGroupConfig(name = 'Selection', order = 3))
    List<String> includeColumnsLike = []

    @ImportParameterConfig(displayName = 'Excluded columns', description = 'Exclude column names matching these SQL LIKE patterns.', order = 6, optional = true, group = @ImportGroupConfig(name = 'Selection', order = 3))
    List<String> excludeColumnsLike = []

    @ImportParameterConfig(displayName = 'Enumeration given adornments', description = 'Column-name suffixes that mark columns as enumerations, like _IND or _TZ.', order = 1, optional = true, group = @ImportGroupConfig(name = 'Enumeration', order = 4))
    List<String> enumGivenAdornment = []

    @ImportParameterConfig(displayName = 'Enumeration lookup adornments', description = 'Column-name suffixes that mark code columns as enumerations.', order = 2, optional = true, group = @ImportGroupConfig(name = 'Enumeration', order = 4))
    List<String> enumLookupAdornment = []

    @ImportParameterConfig(displayName = 'Enumeration ignore columns', description = 'Exact column names to exclude from enumeration detection.', order = 3, optional = true, group = @ImportGroupConfig(name = 'Enumeration', order = 4))
    List<String> enumIgnoreColumn = []

    @ImportParameterConfig(displayName = 'Enumeration ignore columns like', description = 'Column-name fragments to exclude from enumeration detection.', order = 4, optional = true, group = @ImportGroupConfig(name = 'Enumeration', order = 4))
    List<String> enumIgnoreColumnLike = []

    @ImportParameterConfig(
            displayName = 'Detect Types',
            description = 'Whether to detect appropriate types from the values in each column',
            order = 1,
            group = @ImportGroupConfig(
                    name = 'Configuration',
                    order = 2
            )
    )
    Boolean detectTypes = true

    @ImportParameterConfig(
            displayName = 'First row is Header',
            description = ['Is the first row the header row?',
                    'If the first row is not a header row then the list of headers must be supplied in the Headers field.'],
            order = 5,
            group = @ImportGroupConfig(
                    name = 'Configuration',
                    order = 2
            )
    )
    Boolean firstRowIsHeader = true

    @ImportParameterConfig(
            displayName = 'Headers',
            description = ['Comma-separated list of values to use as the column headers.',
                    'If supplied will be used as the headers instead of whatever\'s found in the CSV file.'],
            order = 6,
            optional = true,
            group = @ImportGroupConfig(
                    name = 'Configuration',
                    order = 2
            )
    )
    String headers
}
