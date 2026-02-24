package mauro.plugin.duckdb

import org.maurodata.plugin.importer.config.ImportGroupConfig
import org.maurodata.plugin.importer.config.ImportParameterConfig

class CSVImportParams extends DuckDBImportParams {
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
