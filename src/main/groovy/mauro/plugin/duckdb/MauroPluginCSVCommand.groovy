package mauro.plugin.duckdb

import groovy.util.logging.Slf4j
import io.micronaut.configuration.picocli.PicocliRunner
import jakarta.inject.Inject
import org.maurodata.domain.datamodel.DataModel
import org.maurodata.plugin.importer.FileParameter
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import org.maurodata.domain.folder.Folder
import org.maurodata.plugin.exporter.json.JsonFolderExporterPlugin

import java.nio.file.Files
import java.nio.file.Path

@Command(name = 'mauro-plugin-duckdb-csv', description = 'Catalogue CSV, TSV, PSV, or TAB extracts as Mauro data models.',
        mixinStandardHelpOptions = true, showAtFileInUsageHelp = true)
@Slf4j
class MauroPluginCSVCommand implements Runnable {

    @Inject
    CSVDataModelImporter csvDataModelImporter

    @Inject
    BackwardsCompatibleJsonDataModelExporterPlugin backwardsCompatibleJsonDataModelExporterPlugin

    @Inject
    JsonFolderExporterPlugin jsonFolderExporterPlugin

    @Option(names = ['-s', '--source'], description = 'Input CSV/TSV/PSV/TAB file, ZIP archive, or directory tree.', required = true, order = 1)
    String sourceFile

    @Option(names = ['-m', '--model-name'], description = 'Override the generated DataModel or VersionedFolder label.', required = false, order = 2)
    String modelName

    @Option(names = ['--output-structure'], description = 'Output structure: DATA_MODEL (default) or VERSIONED_FOLDER (immediate child directories become DataModels).', defaultValue = 'DATA_MODEL', order = 3)
    String outputStructure

    @Option(names = ['--metadata-namespace'], description = 'Override the stable DuckDB namespace used for source and profiling metadata.', order = 4)
    String metadataNamespace

    @Option(names = ['--include-schema'], description = 'Include directory names matching SQL LIKE pattern(s), using %% and _ wildcards.', split = ',', order = 5)
    List<String> includeSchemas = []

    @Option(names = ['--exclude-schema'], description = 'Exclude directory names matching SQL LIKE pattern(s), using %% and _ wildcards.', split = ',', order = 6)
    List<String> excludeSchemas = []

    @Option(names = ['--included-tables'], description = 'Include CSV table names matching SQL LIKE pattern(s), using %% and _ wildcards.', split = ',', order = 7)
    List<String> includeTablesLike = []

    @Option(names = ['--excluded-tables'], description = 'Exclude CSV table names matching SQL LIKE pattern(s), using %% and _ wildcards.', split = ',', order = 8)
    List<String> excludeTablesLike = []

    @Option(names = ['--included-columns'], description = 'Include column names matching SQL LIKE pattern(s), using %% and _ wildcards.', split = ',', order = 9)
    List<String> includeColumnsLike = []

    @Option(names = ['--excluded-columns'], description = 'Exclude column names matching SQL LIKE pattern(s), using %% and _ wildcards.', split = ',', order = 10)
    List<String> excludeColumnsLike = []

    @Option(names = ['--enum-given-adornment'], description = 'Column suffixes that identify enumerations, e.g. _IND.', split = ',', order = 11)
    List<String> enumGivenAdornment = []

    @Option(names = ['--enum-lookup-adornment'], description = 'Column suffixes that identify coded enumeration columns.', split = ',', order = 12)
    List<String> enumLookupAdornment = []

    @Option(names = ['--enum-ignore-column'], description = 'Exact column names excluded from enumeration detection.', split = ',', order = 13)
    List<String> enumIgnoreColumn = []

    @Option(names = ['--enum-ignore-column-like'], description = 'Column-name fragments excluded from enumeration detection.', split = ',', order = 14)
    List<String> enumIgnoreColumnLike = []

    @Option(names = ['--detect-enumerations'], negatable = true, description = 'Detect enumeration columns (use --no-detect-enumerations to disable).', defaultValue = 'true', order = 15)
    boolean detectEnumerations

    @Option(names = ['--generate-summary-metadata'], negatable = true, description = 'Generate value distribution summaries (use --no-generate-summary-metadata to disable).', defaultValue = 'true', order = 16)
    boolean generateSummaryMetadata

    @Option(names = ['--max-enumerations'], description = 'Maximum distinct values for automatic enumeration detection.', defaultValue = '80', order = 17)
    int maxEnumerations

    @Option(names = ['--detect-types'], negatable = true, description = 'Infer column types from CSV values (use --no-detect-types to import values as strings).', defaultValue = 'true', order = 18)
    boolean detectTypes

    @Option(names = ['--first-row-is-header'], negatable = true, description = 'Treat the first row as column headers (use --no-first-row-is-header otherwise).', defaultValue = 'true', order = 19)
    boolean firstRowIsHeader

    @Option(names = ['--headers'], description = 'Comma-separated replacement headers, used when the first row is not a header.', order = 20)
    String headers

    @Option(names = ['-o', '--output'], description = 'Output JSON file.', required = true, order = 21)
    Path output

    static void main(String[] args) throws Exception {
        PicocliRunner.run(MauroPluginCSVCommand.class, args)
    }

    void run() {
        CSVImportParams params = new CSVImportParams
        (
            importFile: new FileParameter(sourceFile, "", new byte[0]),
            modelName: modelName,
            outputStructure: outputStructure,
            metadataNamespace: metadataNamespace,
            includeSchemas: includeSchemas,
            excludeSchemas: excludeSchemas,
            includeTablesLike: includeTablesLike,
            excludeTablesLike: excludeTablesLike,
            includeColumnsLike: includeColumnsLike,
            excludeColumnsLike: excludeColumnsLike,
            enumGivenAdornment: enumGivenAdornment,
            enumLookupAdornment: enumLookupAdornment,
            enumIgnoreColumn: enumIgnoreColumn,
            enumIgnoreColumnLike: enumIgnoreColumnLike,
            detectEnumerations: detectEnumerations,
            generateSummaryMetadata: generateSummaryMetadata,
            maxEnumerations: maxEnumerations,
            detectTypes: detectTypes,
            firstRowIsHeader: firstRowIsHeader,
            headers: headers
        )
        log.info 'Importing DataModel...'
        List<DataModel> dataModels = csvDataModelImporter.importDomain(params)
        log.info "Imported ${dataModels.size()} DataModel(s)"

        byte[] dataModelJson
        if (params.outputStructure.equalsIgnoreCase('VERSIONED_FOLDER')) {
            String folderLabel = modelName ?: new File(sourceFile).name.replaceFirst(/(?i)\.zip$/, '')
            Folder folder = new Folder(label: folderLabel, branchName: 'main', dataModels: dataModels)
            folder.metadata << new org.maurodata.domain.facet.Metadata(namespace: CSVDataModelImporter.namespace(params), key: 'sourceIdentifier', value: new File(sourceFile).name)
            dataModelJson = jsonFolderExporterPlugin.exportModel(folder)
        } else {
            if (!dataModels) throw new IllegalStateException('No DataModel was produced from the source.')
            dataModelJson = backwardsCompatibleJsonDataModelExporterPlugin.exportModel(dataModels.first())
        }

        log.info "Writing to file ${output.toString()}"
        Files.write(output, dataModelJson)
        log.info 'Finished!'
    }
}
