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
        json.contains('One')
        json.contains('Two')
        json.contains('Headerless')
        json.contains('Code')
        json.contains('Value')
        imported.size() == 1
        imported.first().domainType == 'VersionedFolder'
        imported.first().dataModels*.label.containsAll(['One', 'Two'])
        imported.first().dataModels.find { it.label == 'Two' }.dataClasses.first().dataClasses.first().dataElements*.label.containsAll(['Code', 'Value'])
    }
}

