package mauro.plugin.duckdb


import uk.ac.ox.softeng.mauro.plugin.importer.FileImportParameters
import uk.ac.ox.softeng.mauro.plugin.importer.config.ImportGroupConfig
import uk.ac.ox.softeng.mauro.plugin.importer.config.ImportParameterConfig

class DuckDBImportParams extends FileImportParameters {

    @ImportParameterConfig(
            displayName = 'Maximum Enumerations',
            description = 'The maximum number of unique values to be interpreted as a defined enumeration',
            order = 4,
            optional = true,
            group = @ImportGroupConfig(
                    name = 'Configuration',
                    order = 2
            )
    )
    Integer maxEnumerations = 20

    @ImportParameterConfig(
            displayName = 'Generate Summary Metadata',
            description = 'Whether to produce data distribution charts for column values',
            order = 3,
            group = @ImportGroupConfig(
                    name = 'Configuration',
                    order = 2
            )
    )
    Boolean generateSummaryMetadata = true

    @ImportParameterConfig(
            displayName = 'Detect Enumerations',
            description = 'Whether to treat columns with small numbers of unique values as enumerations',
            order = 2,
            group = @ImportGroupConfig(
                    name = 'Configuration',
                    order = 2
            )
    )
    Boolean detectEnumerations = true



}
