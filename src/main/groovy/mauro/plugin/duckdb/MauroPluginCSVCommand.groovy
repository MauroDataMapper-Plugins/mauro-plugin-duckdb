package mauro.plugin.duckdb

import groovy.util.logging.Slf4j
import io.micronaut.configuration.picocli.PicocliRunner
import jakarta.inject.Inject
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import uk.ac.ox.softeng.mauro.domain.datamodel.DataModel
import uk.ac.ox.softeng.mauro.plugin.importer.FileParameter

import java.nio.file.Files
import java.nio.file.Path

@Command(name = 'mauro-plugin-duckdb-csv', description = 'Extract metadata from CSV via DuckDB schema to Mauro JSON',
        mixinStandardHelpOptions = true, showAtFileInUsageHelp = true)
@Slf4j
class MauroPluginCSVCommand implements Runnable {

    @Inject
    CSVDataModelImporter csvDataModelImporter

    @Inject
    BackwardsCompatibleJsonDataModelExporterPlugin backwardsCompatibleJsonDataModelExporterPlugin

    @Option(names = ['-s', '--source'], description = 'The source: zip, csv, or directory', required = true)
    String sourceFile

    @Option(names = ['-m', '--model-name'], description = 'Label of Model', required = false)
    String modelName

    @Option(names = ['-o', '--output'], description = 'Output file', required = true)
    Path output

    static void main(String[] args) throws Exception {
        PicocliRunner.run(MauroPluginCSVCommand.class, args)
    }

    void run() {
        CSVImportParams params = new CSVImportParams
        (
                importFile: new FileParameter(sourceFile,"",new byte[0]),
                modelName: modelName
        )
        log.info 'Importing DataModel...'
        DataModel dataModel = csvDataModelImporter.importDomain(params).first()
        log.info "Imported DataModel [$dataModel.label]"

        log.info "Exporting DataModel JSON..."
        byte[] dataModelJson = backwardsCompatibleJsonDataModelExporterPlugin.exportModel(dataModel)
        log.info "Exported DataModel JSON"

        log.info "Writing to file ${output.toString()}"
        Files.write(output, dataModelJson)
        log.info 'Finished!'

        System.exit(0)
    }
}
