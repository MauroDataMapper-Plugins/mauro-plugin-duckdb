package mauro.plugin.duckdb

import spock.lang.Specification
import org.maurodata.plugin.importer.FileParameter
import java.nio.file.Files

class CSVImportConfigurationSpec extends Specification {

    def "SQL LIKE patterns match schema, table and column identifiers"() {
        expect:
        CSVDataModelImporter.matchesAny(value, patterns) == expected

        where:
        value          | patterns          || expected
        'patient_table'| ['patient%']      || true
        'PatientTable' | ['patient%']      || true
        'patient_table'| ['patient______'] || true
        'patient_table'| ['%_table']       || true
        'patient_table'| ['patientXtable'] || false
    }

    def "matcher treats non-LIKE metacharacters and path-looking strings as plain SQL identifier text"() {
        expect:
        CSVDataModelImporter.matchesAny(identifier, patterns) == expected

        where:
        identifier         | patterns             || expected
        'file.csv'         | ['file.csv']         || true
        'fileXcsv'         | ['file.csv']         || false
        'file.csv'         | ['file*.csv']        || false
        'file*.csv'        | ['file*.csv']        || true
        'dir/table.csv'    | ['dir/*.csv']        || false
        'dir/table.csv'    | ['dir/%csv']         || true
        '../secret.csv'    | ['../%']             || true
        '/etc/passwd'      | ['../../etc/%']      || false
        '../../etc/passwd'| ['../../etc/%']      || true
        'patient[1]'       | ['patient[1]']       || true
        'patient1'         | ['patient[1]']       || false
    }

    def "empty include list means include all and excludes remain independent"() {
        expect:
        CSVDataModelImporter.includedBy('anything', [])
        !CSVDataModelImporter.matchesAny('anything', [])
        CSVDataModelImporter.matchesAny('legacy_table', ['legacy%'])
    }

    def "DuckDB metadata namespace has a stable default and accepts an override"() {
        expect:
        CSVDataModelImporter.namespace(new CSVImportParams()) == 'org.maurodata.plugins.duckdb'
        CSVDataModelImporter.namespace(new CSVImportParams(metadataNamespace: 'urn:customer:csv')) == 'urn:customer:csv'
    }

    def "directory imports retain table and column source identifiers and support VersionedFolder model boundaries"() {
        given:
        def root = Files.createTempDirectory('csv-import-spec')
        def one = Files.createDirectories(root.resolve('One/Clinical'))
        def two = Files.createDirectories(root.resolve('Two/Reference'))
        Files.writeString(one.resolve('people.csv'), 'patient_id,status\n1,active\n2,inactive\n')
        Files.writeString(two.resolve('codes.csv'), 'code,label\nA,Alpha\n')
        def importer = new CSVDataModelImporter()

        when: 'the default mode imports one model for the whole source'
        def single = importer.importDomain(new CSVImportParams(importFile: new FileParameter(root.toString(), '', new byte[0])))

        then:
        single.size() == 1
        single.first().dataClasses*.label.containsAll(['One', 'Two'])
        allClasses(single.first())*.label.containsAll(['People', 'Codes'])
        allClasses(single.first()).find { it.label == 'People' }.dataElements*.label.containsAll(['Patient_Id', 'Status'])
        allClasses(single.first()).find { it.label == 'Codes' }.dataElements*.label.containsAll(['Code', 'Label'])

        when: 'VersionedFolder mode imports only immediate child directories as models'
        def versioned = importer.importDomain(new CSVImportParams(
            importFile: new FileParameter(root.toString(), '', new byte[0]), outputStructure: 'VERSIONED_FOLDER'))

        then:
        versioned*.label.containsAll(['One', 'Two'])
        versioned.size() == 2
        versioned.find { it.label == 'One' }.dataClasses*.label == ['Clinical']
        versioned.find { it.label == 'One' }.dataClasses.first().dataClasses*.label == ['People']
        versioned.find { it.label == 'One' }.dataClasses.first().dataClasses.first().dataElements*.label.containsAll(['Patient_Id', 'Status'])

        when: 'SQL LIKE selectors filter schema, table and column identifiers while retaining their source names'
        def filtered = importer.importDomain(new CSVImportParams(
            importFile: new FileParameter(root.toString(), '', new byte[0]),
            includeSchemas: ['One'], includeTablesLike: ['peo%'], includeColumnsLike: ['sta%'], metadataNamespace: 'urn:customer:csv'))

        then:
        filtered.first().dataClasses*.label == ['One']
        filtered.first().dataClasses.first().dataClasses.first().dataClasses*.label == ['People']
        filtered.first().dataClasses.first().dataClasses.first().dataClasses.first().dataElements*.label == ['Status']
        filtered.first().dataClasses.first().dataClasses.first().dataClasses.first().dataElements.first().metadata.any { it.namespace == 'urn:customer:csv' && it.key == 'sourceIdentifier' && it.value.toString().endsWith('/status') }

        when: 'exclude selectors remove matching schemas and tables'
        def excluded = importer.importDomain(new CSVImportParams(
            importFile: new FileParameter(root.toString(), '', new byte[0]),
            excludeSchemas: ['Two'], excludeTablesLike: ['peo%']))

        then:
        excluded == []

        when: 'column excludes leave the other source columns in place'
        def columnExcluded = importer.importDomain(new CSVImportParams(
            importFile: new FileParameter(root.toString(), '', new byte[0]),
            includeTablesLike: ['codes'], excludeColumnsLike: ['co%']))

        then:
        allClasses(columnExcluded.first()).find { it.label == 'Codes' }.dataElements*.label == ['Label']
    }

    def "empty and filtered directories are pruned and temp files stay under the requested directory"() {
        given:
        def root = Files.createTempDirectory('csv-pruned-tree')
        def temp = root.resolve('work-area')
        def included = Files.createDirectories(root.resolve('Included'))
        Files.createDirectories(root.resolve('Empty'))
        Files.createDirectories(root.resolve('Excluded'))
        Files.writeString(included.resolve('readable.csv'), 'id,value\n1,ok\n')
        Files.writeString(root.resolve('Excluded/filtered.csv'), 'id,value\n2,no\n')

        when:
        def models = new CSVDataModelImporter().importDomain(new CSVImportParams(
            importFile: new FileParameter(root.toString(), '', new byte[0]),
            tempDirectory: temp,
            includeSchemas: ['Included']))

        then:
        models.size() == 1
        models.first().dataClasses*.label == ['Included']
        allClasses(models.first())*.label.contains('Readable')
        Files.isDirectory(temp)
        Files.list(temp).toList().isEmpty()
    }

    def "VersionedFolder omits immediate directories with no included tables"() {
        given:
        def root = Files.createTempDirectory('csv-empty-versioned')
        def populated = Files.createDirectories(root.resolve('Populated/Schema'))
        Files.createDirectories(root.resolve('NoTables/Schema'))
        Files.writeString(populated.resolve('included.csv'), 'id,value\n1,ok\n')
        Files.writeString(populated.resolve('excluded.csv'), 'id,value\n2,no\n')

        when:
        def models = new CSVDataModelImporter().importDomain(new CSVImportParams(
            importFile: new FileParameter(root.toString(), '', new byte[0]),
            outputStructure: 'VERSIONED_FOLDER',
            includeTablesLike: ['included']))

        then:
        models*.label == ['Populated']
    }

    def "ignore-errors CSV parser fallback is only present when enabled"() {
        when:
        def strict = CSVDataModelImporter.csvReadSniffs('/extract.csv', 'header=true, auto_detect=true', false)
        def lenient = CSVDataModelImporter.csvReadSniffs('/extract.csv', 'header=true, auto_detect=true', true)

        then:
        strict.size() == 2
        strict.every { !it.contains('ignore_errors=true') }
        lenient.size() == 3
        lenient.take(2) == strict
        lenient.last() == "('/extract.csv', header=true, auto_detect=true, quote='\"', escape='\"', sample_size=2048000, ignore_errors=true)"
        !new CSVImportParams().ignoreCsvErrors
    }

    def "SQL fixture shaped extracts produce enumeration and numeric/date map summaries"() {
        given: 'CSV equivalents of the SQL organisation and sample fixtures'
        def root = Files.createTempDirectory('sql-fixture-csv')
        def schema = Files.createDirectories(root.resolve('dbo'))
        def organisation = new StringBuilder('id,org_name,org_type,org_code,description,org_char\n')
        (1..40).each { int id ->
            String type = id <= 12 ? 'TYPEA' : id <= 30 ? 'TYPEB' : 'TYPEC'
            String code = id <= 7 ? 'CODEY' : id <= 19 ? 'CODEZ' : id <= 39 ? 'CODEX' : 'CODER'
            String charValue = id <= 7 ? 'CHAR1' : id <= 19 ? 'CHAR2' : 'CHAR3'
            organisation.append("${id},ORG${id},${type},${code},Description ${id},${charValue}\n")
        }
        Files.writeString(schema.resolve('organisation.csv'), organisation.toString())

        def sample = new StringBuilder('sample_smallint,sample_int,sample_decimal,sample_date,sample_long_date\n')
        (0..200).each { int offset ->
            def date = java.time.LocalDate.parse('2020-09-01').plusDays(offset)
            def longDate = java.time.LocalDate.parse('1900-01-01').plusDays(offset * 365L)
            sample.append("${offset - 100},${offset * offset},${(offset * 0.125d).toString()},${date},${longDate}\n")
        }
        Files.writeString(schema.resolve('sample.csv'), sample.toString())

        when:
        def models = new CSVDataModelImporter().importDomain(new CSVImportParams(
            importFile: new FileParameter(root.toString(), '', new byte[0])))
        def classes = allClasses(models.first())
        def organisationClass = classes.find { it.label == 'Organisation' }
        def sampleClass = classes.find { it.label == 'Sample' }
        def orgType = organisationClass.dataElements.find { it.label == 'Org_Type' }
        def numeric = sampleClass.dataElements.find { it.label == 'Sample_Int' }
        def date = sampleClass.dataElements.find { it.label == 'Sample_Date' }
        def longDate = sampleClass.dataElements.find { it.label == 'Sample_Long_Date' }
        def dateHistogram = new groovy.json.JsonSlurper().parseText(date.summaryMetadata.first().summaryMetadataReports.first().reportValue)

        then:
        models.size() == 1
        organisationClass.dataElements.size() == 6
        orgType.dataType.dataTypeKind == org.maurodata.domain.datamodel.DataType.DataTypeKind.ENUMERATION_TYPE
        orgType.dataType.enumerationValues*.value.containsAll(['TYPEA', 'TYPEB', 'TYPEC'])
        orgType.summaryMetadata.any { it.summaryMetadataType == org.maurodata.domain.facet.SummaryMetadataType.MAP }
        organisationClass.summaryMetadata.any { it.label == 'Org_Type' }
        numeric.summaryMetadata.any { it.summaryMetadataType == org.maurodata.domain.facet.SummaryMetadataType.MAP }
        date.summaryMetadata.any { it.summaryMetadataType == org.maurodata.domain.facet.SummaryMetadataType.MAP }
        numeric.metadata.any { it.key == 'distinctValuesCount' && it.value.toString() == '200' }
        date.summaryMetadata.first().summaryMetadataReports.first().reportValue.contains('2020-09')
        longDate.summaryMetadata.first().summaryMetadataReports.first().reportValue.contains('1900-1999')
        dateHistogram.keySet().toList() == ['2020-09', '2020-10', '2020-11', '2020-12', '2021-01', '2021-02', '2021-03']
    }

    def "singleton numeric bins use scalar labels and cleaned near-unique columns are not enumerations"() {
        given:
        File source = new File(getClass().getResource('/csv-catalogues/edge_cases').toURI())

        when:
        def model = new CSVDataModelImporter().importDomain(new CSVImportParams(
            importFile: new FileParameter(source.toString(), '', new byte[0]))).first()
        def patientExtract = allClasses(model).find { it.label == 'Patient_Extract' }
        def patientId = patientExtract.dataElements.find { it.label == 'Patient_Id' }
        def patientHistogram = new groovy.json.JsonSlurper().parseText(patientId.summaryMetadata.first().summaryMetadataReports.first().reportValue)
        def quality = allClasses(model).find { it.label == 'Enumeration_Quality' }
        def measurement = quality.dataElements.find { it.label == 'Measurement' }
        def code = quality.dataElements.find { it.label == 'Code' }
        def uniqueValue = quality.dataElements.find { it.label == 'Unique_Value' }
        def recordNumber = quality.dataElements.find { it.label == 'Record_Number' }
        def recordNumberHistogram = new groovy.json.JsonSlurper().parseText(recordNumber.summaryMetadata.first().summaryMetadataReports.first().reportValue)
        def decimalCode = quality.dataElements.find { it.label == 'Decimal_Code' }
        def decimalCodeHistogram = new groovy.json.JsonSlurper().parseText(decimalCode.summaryMetadata.first().summaryMetadataReports.first().reportValue)

        then:
        patientId.dataType.dataTypeKind == org.maurodata.domain.datamodel.DataType.DataTypeKind.PRIMITIVE_TYPE
        patientHistogram == ['1': 1, '2': 1, '3': 1, '4': 1]
        measurement.dataType.dataTypeKind == org.maurodata.domain.datamodel.DataType.DataTypeKind.PRIMITIVE_TYPE
        measurement.metadata.any { it.key == 'distinct_values_count' && it.value.toString() == '12' }
        uniqueValue.metadata.any { it.key == 'distinct_values_count' && it.value.toString() == '12' }
        recordNumberHistogram.keySet().toList() == (1..16).collect { it.toString() }
        code.dataType.dataTypeKind == org.maurodata.domain.datamodel.DataType.DataTypeKind.ENUMERATION_TYPE
        decimalCode.dataType.dataTypeKind == org.maurodata.domain.datamodel.DataType.DataTypeKind.ENUMERATION_TYPE
        decimalCodeHistogram.keySet().toList() == ['2.5', '10.5']
        uniqueValue.dataType.dataTypeKind == org.maurodata.domain.datamodel.DataType.DataTypeKind.PRIMITIVE_TYPE
    }

    private static List allClasses(org.maurodata.domain.datamodel.DataModel model) {
        List result = []
        Closure visit
        visit = { collection ->
            collection.each { item ->
                result << item
                visit(item.dataClasses ?: [])
            }
        }
        visit(model.dataClasses)
        result
    }
}
