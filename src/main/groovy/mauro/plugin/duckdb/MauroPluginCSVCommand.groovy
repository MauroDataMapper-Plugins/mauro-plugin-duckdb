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
import java.nio.file.StandardCopyOption

@Command(name = 'mauro-plugin-duckdb-csv', description = 'Catalogue CSV, TSV, PSV, or TAB extracts as Mauro data models.',
        mixinStandardHelpOptions = true, showAtFileInUsageHelp = true)
@Slf4j
class MauroPluginCSVCommand implements Runnable {
    private static boolean commandLineInvocation

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

    @Option(names = ['--temp'], description = 'Directory for all temporary and transitional files; created if needed.', order = 22)
    Path tempDirectory

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

    @Option(names = ['--ignore-csv-errors'], description = 'Allow DuckDB to skip malformed rows after strict CSV parsing attempts fail; emits a warning.', order = 20)
    boolean ignoreCsvErrors

    @Option(names = ['--headers'], description = 'Comma-separated replacement headers, used when the first row is not a header.', order = 21)
    String headers

    @Option(names = ['-o', '--output'], description = 'Output JSON file.', required = true, order = 22)
    Path output

    @Option(names = ['--log-file'], description = 'Append run logs to this file', order = 23)
    Path logFile

    @Option(names = ['--quiet'], description = 'Suppress console log messages; file logging is unaffected', order = 24)
    boolean quiet

    @Option(names = ['--summary'], description = 'Print an output summary, including when --quiet is set', order = 25)
    boolean summary

    static void main(String[] args) throws Exception {
        commandLineInvocation = true
        try {
            CSVRunLogging.configure(null, args?.contains('--quiet'))
            PicocliRunner.run(MauroPluginCSVCommand.class, args)
        } catch (Exception exception) {
            System.err.println("Catalogue run failed: ${exception.message ?: exception.class.simpleName}")
            System.exit(1)
        } finally {
            commandLineInvocation = false
        }
    }

    void run() {
        CSVRunSummary runSummary = new CSVRunSummary(sourcePath: sourceFile, outputPath: output?.toString(), outputStructure: outputStructure)
        try {
            CSVRunLogging.configure(logFile, quiet)
        } catch (Exception exception) {
            System.err.println("Cannot configure logging: ${exception.message}")
            runSummary.error = exception.message ?: exception.class.simpleName
            if (summary) printRunSummary(runSummary)
            if (commandLineInvocation) System.exit(1)
            throw exception
        }
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
            ignoreCsvErrors: ignoreCsvErrors,
            headers: headers,
            tempDirectory: tempDirectory,
            runSummary: runSummary
        )
        try {
            if (ignoreCsvErrors) {
                String warning = '--ignore-csv-errors is enabled; malformed CSV rows may be skipped.'
                log.warn(warning)
                runSummary.warnings << warning
            }
            log.info 'Importing DataModel...'
            List<DataModel> dataModels = csvDataModelImporter.importDomain(params)
            runSummary.modelsCatalogued = dataModels.size()
            runSummary.tablesCatalogued = 0
            runSummary.columnsCatalogued = 0
            dataModels.each { DataModel dataModel ->
                runSummary.tablesCatalogued += countTables(dataModel)
                runSummary.columnsCatalogued += countColumns(dataModel)
            }
            if (runSummary.tablesCatalogued == 0) {
                runSummary.omittedModels.addAll(dataModels*.label)
                log.info 'No catalogueable tables were found after applying exclusions; no catalogue was written.'
                if (summary) printRunSummary(runSummary)
                return
            }

            byte[] dataModelJson
            if (params.outputStructure.equalsIgnoreCase('VERSIONED_FOLDER')) {
                String folderLabel = modelName ?: new File(sourceFile).name.replaceFirst(/(?i)\.zip$/, '')
                Folder folder = new Folder(label: folderLabel, branchName: 'main', dataModels: dataModels)
                folder.metadata << new org.maurodata.domain.facet.Metadata(namespace: CSVDataModelImporter.namespace(params), key: 'sourceIdentifier', value: new File(sourceFile).name)
                dataModelJson = jsonFolderExporterPlugin.exportModel(folder)
            } else {
                dataModelJson = backwardsCompatibleJsonDataModelExporterPlugin.exportModel(dataModels.first())
            }

            Path absoluteOutput = output.toAbsolutePath().normalize()
            Path stagingRoot = tempDirectory?.toAbsolutePath()?.normalize() ?: absoluteOutput.parent
            Files.createDirectories(stagingRoot)
            Path staging = Files.createTempFile(stagingRoot, absoluteOutput.fileName.toString(), '.tmp')
            try {
                Files.write(staging, dataModelJson)
                try {
                    Files.move(staging, absoluteOutput, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (IOException ignored) {
                    Files.copy(staging, absoluteOutput, StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                Files.deleteIfExists(staging)
            }
            runSummary.catalogueWritten = true
            log.info "Wrote catalogue to ${output}"
            if (summary) printRunSummary(runSummary)
        } catch (Exception exception) {
            runSummary.error = exception.message ?: exception.class.simpleName
            System.err.println("Catalogue run failed: ${runSummary.error}")
            if (summary) printRunSummary(runSummary)
            if (commandLineInvocation) System.exit(1)
            throw exception
        }
    }

    private static int countTables(DataModel model) {
        int count = 0
        List pending = new ArrayList(model.dataClasses ?: [])
        while (!pending.isEmpty()) {
            def item = pending.remove(pending.size() - 1)
            if (item.dataElements) count++
            pending.addAll(item.dataClasses ?: [])
        }
        count
    }
    private static int countColumns(DataModel model) {
        int count = 0
        List pending = new ArrayList(model.dataClasses ?: [])
        while (!pending.isEmpty()) {
            def item = pending.remove(pending.size() - 1)
            count += item.dataElements?.size() ?: 0
            pending.addAll(item.dataClasses ?: [])
        }
        count
    }

    private static void printRunSummary(CSVRunSummary summary) {
        println 'CSV cataloguer run summary:'
        println "  Status: ${summary.status()}"
        println "  Source: ${summary.sourcePath}"
        println "  Output: ${summary.catalogueWritten ? summary.outputPath : "${summary.outputPath} (not written)"}"
        println "  Structure: ${summary.outputStructure}"
        println "  Files catalogued: ${summary.filesCatalogued}"
        println "  DataModels catalogued: ${summary.modelsCatalogued}"
        println "  Tables catalogued: ${summary.tablesCatalogued}"
        println "  Columns catalogued: ${summary.columnsCatalogued}"
        summary.skippedFiles.each { println "  Skipped file: ${it}" }
        summary.csvFilesReadIgnoringErrors.each { println "  CSV read using ignore_errors=true: ${it}" }
        summary.omittedModels.each { println "  Omitted empty DataModel: ${it}" }
        summary.omittedDirectories.each { println "  Omitted empty directory: ${it}" }
        summary.warnings.each { println "  Warning: ${it}" }
        if (summary.error) println "  Error: ${summary.error}"
    }
}
