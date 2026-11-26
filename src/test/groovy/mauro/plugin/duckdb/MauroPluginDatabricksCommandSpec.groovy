package mauro.plugin.duckdb

import io.micronaut.configuration.picocli.PicocliRunner
import io.micronaut.context.ApplicationContext
import io.micronaut.context.env.Environment
import groovy.json.JsonSlurper
import java.nio.file.Files
import org.maurodata.plugin.importer.FileParameter
import org.maurodata.plugin.importer.FileImportParameters
import org.maurodata.plugin.importer.json.JsonFolderImporterPlugin

import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

class MauroPluginDatabricksCommandSpec extends Specification {

    @Shared @AutoCleanup ApplicationContext ctx = ApplicationContext.run(Environment.CLI, Environment.TEST)

    void "CLI help describes the output modes and SQL-style selectors"() {
        given:
        ByteArrayOutputStream baos = new ByteArrayOutputStream()
        PrintStream originalOut = System.out
        System.setOut(new PrintStream(baos))

        try {
            String[] args = ['--help'] as String[]
            PicocliRunner.run(MauroPluginCSVCommand, ctx, args)
        } finally {
            System.setOut(originalOut)
        }

        expect:
        baos.toString().contains('--output-structure')
        baos.toString().contains('--included-tables')
        baos.toString().contains('--metadata-namespace')
        baos.toString().contains('--temp')
        baos.toString().contains('--log-file')
        baos.toString().contains('--quiet')
        baos.toString().contains('--summary')
        baos.toString().contains('--ignore-csv-errors')
    }

    void "empty filtered run is a successful no-op and summary explains why no output was written"() {
        given:
        File source = new File(getClass().getResource('/csv-catalogues/sqlserver/metadata_simple').toURI())
        def output = source.toPath().resolve('no-catalogue.json')
        def logFile = Files.createTempFile('csv-run', '.log')
        ByteArrayOutputStream baos = new ByteArrayOutputStream()
        PrintStream originalOut = System.out
        System.setOut(new PrintStream(baos))

        when:
        PicocliRunner.run(MauroPluginCSVCommand, ctx, [
            '--source', source.absolutePath,
            '--included-tables', 'no-such-table',
            '--quiet',
            '--log-file', logFile.toString(),
            '--summary',
            '--output', output.toString()
        ] as String[])

        then:
        !Files.exists(output)
        baos.toString().contains('Status: EMPTY (no catalogue written)')
        baos.toString().contains('Files catalogued: 0')
        !baos.toString().contains('Importing DataModel')
        Files.readString(logFile).contains('Importing DataModel')
        Files.readString(logFile).contains(' - X ')

        cleanup:
        System.setOut(originalOut)
    }

    void "ignore-csv-errors warns even when strict parsing succeeds"() {
        given:
        File source = new File(getClass().getResource('/csv-catalogues/sqlserver/metadata_simple').toURI())
        def output = Files.createTempDirectory('csv-cli-ignore-errors').resolve('catalogue.json')
        ByteArrayOutputStream baos = new ByteArrayOutputStream()
        PrintStream originalOut = System.out
        System.setOut(new PrintStream(baos))

        when:
        PicocliRunner.run(MauroPluginCSVCommand, ctx, [
            '--source', source.absolutePath,
            '--included-tables', 'organisation',
            '--ignore-csv-errors',
            '--quiet', '--summary',
            '--output', output.toString()
        ] as String[])

        then:
        Files.exists(output)
        baos.toString().contains('--ignore-csv-errors is enabled')
        baos.toString().contains('Status: COMPLETED WITH WARNINGS')
        !baos.toString().contains('CSV read using ignore_errors=true:')

        cleanup:
        System.setOut(originalOut)
    }

    void "CLI --temp creates and uses the selected directory, then cleans its workspace"() {
        given:
        File source = new File(getClass().getResource('/csv-catalogues/sqlserver/metadata_simple').toURI())
        def root = Files.createTempDirectory('csv-cli-temp-option')
        def tempDirectory = root.resolve('chosen-temp')
        def output = root.resolve('catalogue.json')
        def logFile = root.resolve('catalogue.log')

        when:
        PicocliRunner.run(MauroPluginCSVCommand, ctx, [
            '--source', source.absolutePath,
            '--included-tables', 'organisation',
            '--temp', tempDirectory.toString(),
            '--log-file', logFile.toString(),
            '--quiet',
            '--output', output.toString()
        ] as String[])

        then:
        Files.exists(output)
        Files.readString(logFile).contains(tempDirectory.toString())
        Files.isDirectory(tempDirectory)
        Files.list(tempDirectory).toList().isEmpty()
    }

    void "unreadable CSV fails before creating the output catalogue"() {
        given:
        def root = Files.createTempDirectory('csv-cli-failure')
        def source = root.resolve('broken.csv')
        def output = root.resolve('catalogue.json')
        Files.writeString(source, 'one,two\n1,2\n')
        def command = new MauroPluginCSVCommand(
            csvDataModelImporter: ctx.getBean(CSVDataModelImporter),
            backwardsCompatibleJsonDataModelExporterPlugin: ctx.getBean(BackwardsCompatibleJsonDataModelExporterPlugin),
            jsonFolderExporterPlugin: ctx.getBean(org.maurodata.plugin.exporter.json.JsonFolderExporterPlugin),
            sourceFile: source.toString(), output: output,
            firstRowIsHeader: false, headers: 'single')

        when:
        command.run()

        then:
        thrown(Exception)
        !Files.exists(output)
    }

    void "CLI imports the checked-in SQL Server fixture with include and metadata options"() {
        given:
        File source = new File(getClass().getResource('/csv-catalogues/sqlserver/metadata_simple').toURI())
        def output = Files.createTempFile('csv-cli-sqlserver', '.json')

        when:
        PicocliRunner.run(MauroPluginCSVCommand, ctx, [
            '--source', source.absolutePath,
            '--included-tables', 'organisation',
            '--included-columns', 'org_type',
            '--metadata-namespace', 'urn:test:duckdb',
            '--output', output.toString()
        ] as String[])
        String json = Files.readString(output)

        then:
        json.contains('Organisation')
        json.contains('Org_Type')
        !json.contains('Sample_Int')
        json.contains('urn:test:duckdb')
    }

    void "CLI imports the checked-in Oracle fixture with table and column exclusion patterns"() {
        given:
        File source = new File(getClass().getResource('/csv-catalogues/oracle/metadata_simple').toURI())
        def output = Files.createTempFile('csv-cli-oracle', '.json')

        when:
        PicocliRunner.run(MauroPluginCSVCommand, ctx, [
            '--source', source.absolutePath,
            '--included-tables', 'organi%ation',
            '--excluded-columns', 'descrip%',
            '--output', output.toString()
        ] as String[])
        String json = Files.readString(output)

        then:
        json.contains('Organisation')
        json.contains('Org_Name')
        !json.contains('Description')
    }

    void "CLI emits a VersionedFolder bundle and honours explicit header settings on checked-in CSVs"() {
        given:
        File source = new File(getClass().getResource('/csv-catalogues/edge_cases').toURI())
        def output = Files.createTempFile('csv-cli-versioned-folder', '.json')

        when:
        PicocliRunner.run(MauroPluginCSVCommand, ctx, [
            '--source', source.absolutePath,
            '--output-structure', 'VERSIONED_FOLDER',
            '--included-tables', 'headerless',
            '--no-first-row-is-header',
            '--headers', 'code,value',
            '--output', output.toString()
        ] as String[])
        String json = Files.readString(output)
        def imported = ctx.getBean(JsonFolderImporterPlugin).importDomain(new FileImportParameters(
            importFile: new FileParameter('catalogue.json', 'application/json', Files.readAllBytes(output))))

        then:
        json.contains('VersionedFolder')
        json.contains('"folder"')
        !json.contains('"versionedFolder"')
        !json.contains('One')
        json.contains('Two')
        json.contains('Headerless')
        json.contains('Code')
        json.contains('Value')
        imported.size() == 1
        imported.first().domainType == 'VersionedFolder'
        imported.first().dataModels*.label == ['Two']
        imported.first().dataModels.find { it.label == 'Two' }.dataClasses.first().dataClasses.first().dataElements*.label.containsAll(['Code', 'Value'])
    }
}

