package mauro.plugin.duckdb

import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j
import io.micronaut.context.annotation.Bean
import org.maurodata.domain.datamodel.DataClass
import org.maurodata.domain.datamodel.DataElement
import org.maurodata.domain.datamodel.DataModel
import org.maurodata.domain.datamodel.DataModelType
import org.maurodata.domain.datamodel.DataType
import org.maurodata.domain.datamodel.EnumerationValue
import org.maurodata.domain.facet.Metadata
import org.maurodata.domain.facet.SummaryMetadata
import org.maurodata.domain.facet.SummaryMetadataReport
import org.maurodata.domain.facet.SummaryMetadataType
import org.maurodata.domain.model.AdministeredItem
import org.maurodata.plugin.importer.DataModelImporterPlugin
import org.maurodata.plugin.importer.FileParameter

import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

@Slf4j
@Bean
class CSVDataModelImporter implements DataModelImporterPlugin<CSVImportParams> {

    static final String NAMESPACE_ME='org.maurodata.plugins.duckdb';
    static final String NAMESPACE_EXPLORER='uk.ac.ox.softeng.maurodatamapper.plugins.explorer.research';
    static final String NAMESPACE_EXPLORER_QUERY='uk.ac.ox.softeng.maurodatamapper.plugins.explorer.querybuilder';

    @Override
    List<DataModel> importDomain(CSVImportParams params) {

        final ArrayList<File> filesToCleanUp = new ArrayList<>(10);
        Connection connection=null;
        DataModel dataModel=null;
        List<DataModel> importedModels = []

        try
        {
            // Generalised version of the parameters into a map
            /*
            Map<String, Object> generalParameters = new LinkedHashMap<>(10);

            params.properties.each { Map.Entry<Object, Object> it -> generalParameters.put(String.valueOf(it.getKey()), String.valueOf(it.getValue())); }

            System.out.println(generalParameters)

             */

            FileParameter fileParameter = params.getImportFile();
            if (fileParameter == null) {
                throw new IllegalArgumentException("The file parameter has not been set");
            }

            // Currently, if the FileParameter is produced via the backend and not via the command line
            // the whole file is loaded into memory and is accessible via byte[] getFileContents() and the byte[] wrapper InputStream getInputStream()
            // This is very obviously undesirable behaviour and is likely to be changed in future.
            // Therefore, for future proofing, that is not used here.

            // Get the file

            final File fileParameterPointsTo = new File(fileParameter.fileName);
            if (!fileParameterPointsTo.exists()) {
                throw new NoSuchFileException(fileParameter.fileName)
            }
            if (!fileParameterPointsTo.canRead()) {
                throw new AccessDeniedException(fileParameter.fileName)
            }
            if (params.outputStructure && !(params.outputStructure.equalsIgnoreCase('DATA_MODEL') || params.outputStructure.equalsIgnoreCase('VERSIONED_FOLDER'))) {
                throw new IllegalArgumentException("Unsupported output structure '${params.outputStructure}'; use DATA_MODEL or VERSIONED_FOLDER")
            }
            if (params.outputStructure?.equalsIgnoreCase('VERSIONED_FOLDER') && !fileParameterPointsTo.isDirectory()) {
                throw new IllegalArgumentException('VERSIONED_FOLDER output requires a directory source')
            }

            if (fileParameterPointsTo.isFile() && !hasSuffix(fileParameterPointsTo.getName(), (dataSuffixes + archiveSuffixes))) {
                throw new IOException("Unsupported format: " + fileParameterPointsTo.getName() + " : expecting one of: " + (dataSuffixes + archiveSuffixes).join(", "))
            }

            // The file parameter can mean one of three things:
            // 1. Points directly to a .csv file - a singleton table in the data model
            // 2. Points to a directory containing .csv files - the directory's descendant directories are folders and the files are tables
            // 3. Points to a zip file containing .csv files - an archived version of (2)

            // Aim: To hold a list of direct child files and folders of the model
            // and recurse through them

            // The model name is the name of the file minus any suffixes, or the given model name
            final String modelName;
            if ((params.getModelName() != null && !params.getModelName().trim().isEmpty())) {
                modelName = params.getModelName();
            } else {
                modelName = removeFileNameSuffixes(fileParameterPointsTo.getName())
            }

            log.info("Model name: " + modelName)

            final ArrayList<Path> modelChildren;


            // If it's a file
            if (fileParameterPointsTo.isFile() && hasSuffix(fileParameterPointsTo.getName(), dataSuffixes)) {
                modelChildren = new ArrayList<>(1);
                modelChildren.add(fileParameterPointsTo.toPath())
            } else
            // if it's a directory of files/directories
            if (fileParameterPointsTo.isDirectory()) {
                final File[] files = fileParameterPointsTo.listFiles();
                modelChildren = new ArrayList<>(files.length);
                for (int p = 0; p < files.length; p++) {
                    modelChildren.add(files[p].toPath())
                }
            } else {
                // An archive. Will need to be unpacked
                // Create a temp directory to unpack the files into

                final File modelDir = File.createTempDir("duck_db_", "_mauro");

                log.info("Unpacking zip file to: " + modelDir.getCanonicalPath());

                final byte[] buffer = new byte[8192];
                final ZipInputStream zis = new ZipInputStream(new FileInputStream(fileParameterPointsTo))

                ZipEntry zipEntry;
                while ((zipEntry = zis.getNextEntry()) != null) {
                    final File newFile = newFile(modelDir, zipEntry);

                    if (zipEntry.isDirectory()) {
                        continue
                    }
                    if (newFile.isHidden()) {
                        log.warn('Skipping [{}], is a hidden file', zipEntry.getName())
                        continue
                    }
                    if (!hasSuffix(zipEntry.getName(), dataSuffixes)) {
                        log.warn('Skipping [{}], is not a known data file: ' + dataSuffixes.join(", "), zipEntry.getName())
                        continue
                    }

                    // If the file is in directory, create the directory for it
                    final File parent = newFile.getParentFile()
                    if (!parent.isDirectory() && !parent.mkdirs()) {
                        throw new IOException("Failed to create directory " + parent);
                    }

                    final FileOutputStream fos = new FileOutputStream(newFile);
                    int len;
                    while ((len = zis.read(buffer)) > 0) {
                        fos.write(buffer, 0, len)
                    }
                    fos.close()
                }
                zis.closeEntry()
                zis.close()

                // Point to the newly created directory contents

                final File[] files = modelDir.listFiles();
                modelChildren = new ArrayList<>(files.length);
                for (int p = 0; p < files.length; p++) {
                    modelChildren.add(files[p].toPath())
                }

                // Remember to delete it later on

                filesToCleanUp << modelDir
            }

            // Let's get quacking; prime the duck
            // Load the driver
            Class.forName("org.duckdb.DuckDBDriver");

            boolean useMemory = false;

            // Set up properties
            Properties properties = new Properties();
            properties.setProperty("enable_external_access", "true")
            properties.setProperty("access_mode", "READ_WRITE")


            if (useMemory) {
                connection = DriverManager.getConnection("jdbc:duckdb:", properties);
                log.info("DuckDB using in memory database")
            } else {
                // Set up temp dir and file for the database
                final File tempDir = File.createTempDir("duck_db_mauro_", "");
                final File tempFile = File.createTempFile("duck_db_mauro_", "", tempDir);
                log.info("DuckDB file: " + tempFile)
                tempFile.deleteOnExit();
                tempDir.deleteOnExit();

                filesToCleanUp << tempFile
                filesToCleanUp << tempDir

                if (tempFile.exists()) {
                    tempFile.delete();
                }

                properties.setProperty("temp_directory", tempDir.getCanonicalPath());
                connection = DriverManager.getConnection("jdbc:duckdb:" + tempFile.getCanonicalPath(), properties);
            }

            // Do a check on the connection
            checkConnection(connection)

            // Recurse down the directory
            // Breadth first, ordered alphabetically
            // run the files, then the directories
            // for each csv file, load the csv in as a table - doesn't matter what it is called - and run
            // the metadata stuff on it
            // for each directory create a DataClass and add it to the parent, recurse

            // The root is represented by a DataModel, and has the CSVImportParams parameters added as DataTypes

            if (params.outputStructure?.equalsIgnoreCase('VERSIONED_FOLDER') && fileParameterPointsTo.isDirectory()) {
                Path sourceRoot = fileParameterPointsTo.toPath()
                modelChildren.findAll { Files.isDirectory(it) }.sort { it.fileName.toString() }.each { Path modelPath ->
                    String modelIdentifier = modelPath.fileName.toString()
                    DataModel childModel = new DataModel(modelType: DataModelType.DATA_ASSET, label: Util.sanitiseForMauroLabel(Util.normaliseLabelCase(modelIdentifier)))
                    addMetadata(childModel.metadata, new Metadata(namespace: namespace(params), key: 'sourceIdentifier', value: sourceRoot.relativize(modelPath).toString()))
                    List<Path> modelPathChildren = modelPath.toFile().listFiles()?.collect { it.toPath() } ?: []
                    recurseDirectory(childModel, new ArrayList<Path>(modelPathChildren), childModel, connection, params, childModel, modelIdentifier, modelPath)
                    importedModels << childModel
                }
            } else {
                dataModel = new DataModel(modelType: DataModelType.DATA_ASSET, label: Util.sanitiseForMauroLabel(Util.normaliseLabelCase(modelName)))
                addMetadata(dataModel.metadata, new Metadata(namespace: namespace(params), key: 'sourceIdentifier', value: fileParameterPointsTo.name))
                recurseDirectory(dataModel, modelChildren, dataModel, connection, params, dataModel, modelName, fileParameterPointsTo.isDirectory() ? fileParameterPointsTo.toPath() : null)
                importedModels << dataModel
            }
        }
        finally
        {
            // Tidying up
            log.info("Tidying up...")

            // Close database
            if(connection!=null) {
                try {
                    connection.close();
                }
                catch (SQLException sqlet) {

                }
            }

            // Delete database files
            // There should be 3 files to delete:
            // A temp directory, two database files
            // Plus any directories created by unpacking zip files
            if (filesToCleanUp.size() > 0) {
                for (int f = 0; f < filesToCleanUp.size(); f++) {
                    final File toDelete = filesToCleanUp.get(f);

                    if (toDelete.isFile()) {
                        log.info("X " + toDelete.getCanonicalPath())
                        if (!toDelete.delete()) {
                            log.warn("Failed to remove all temporary files");
                            break
                        }
                    } else if (toDelete.isDirectory()) {
                        final files = toDelete.listFiles();
                        if (files != null && files.length > 0) {
                            filesToCleanUp.remove(f)
                            f--;
                            filesToCleanUp.addAll(files)
                            filesToCleanUp.add(toDelete)
                        } else {
                            log.info("X " + toDelete.getCanonicalPath())
                            if (!toDelete.delete()) {
                                log.warn("Failed to remove all temporary files");
                                break
                            }

                        }
                    }
                }
            }
        }
        importedModels
    }

    private static void addClass(final Object parent, final DataClass child)
    {
        if(parent instanceof DataModel)
        {
            ((DataModel) parent).dataClasses  << child
        }
        else
        {
            child.parentDataClass = ((DataClass) parent)
            ((DataClass) parent).dataClasses << child
        }
    }

    private static void recurseDirectory(final Object parent, final ArrayList<Path> children, final DataModel dataModel, final Connection connection, final CSVImportParams params, final DataModel rootModel, final String rootName, final Path sourceRoot)
    {
        // Sort by filename
        children.sort {it.getFileName().toString() }
        log.info(children.toString())

        for(Path child : children)
        {
            if(Files.isDirectory(child))
            {
                continue
            }
            final String filename=child.getFileName().toString()

            if(!hasSuffix(filename,dataSuffixes))
            {
                continue
            }

            final String originalTableName=removeFileNameSuffixes(filename)
            if (!includedBy(originalTableName, params.includeTablesLike) || matchesAny(originalTableName, params.excludeTablesLike)) continue
            final String tableName=Util.sanitiseForMauroLabel(Util.normaliseLabelCase(originalTableName))

            final DataClass dataClass = new DataClass(label: tableName)
            addMetadata(dataClass.metadata, new Metadata(namespace: namespace(params), key: 'sourceIdentifier', value: sourceRoot == null ? filename : sourceRoot.relativize(child).toString()))
            addClass(parent,dataClass)

            // Using a large sample size to get autodetect to fail
            // If it doesn't succeed, try being more specific by setting the quotes and escape quotes

            if (params.firstRowIsHeader == false && !params.headers?.trim()) throw new IllegalArgumentException("Headers must be supplied when the first row is not a header")
            String csvOptions = "header=${params.firstRowIsHeader != false}, ${params.detectTypes == false ? 'all_varchar=true' : 'auto_detect=true'}, normalize_names=false"
            if (params.headers?.trim()) csvOptions += ", names=${toDuckList(params.headers.split(',').collect { it.trim() })}"
            final String csvPath = child.toAbsolutePath().toString().replace("'", "''")
            final String[] sniffs=new String[]
            {
                    "('"+csvPath+"', "+csvOptions+", sample_size=2048000)",
                    "('"+csvPath+"', "+csvOptions+", quote='\"', escape='\"', sample_size=2048000)",
                    "('"+csvPath+"', "+csvOptions+", quote='\"', escape='\"', sample_size=2048000, ignore_errors=true)"
            };

            boolean anyWorked=false;

            for(String sniff : sniffs)
            {
                // quote='"', escape='"',
                final String DDL = "CREATE TABLE \"" + tableName + "\" AS SELECT * FROM read_csv"+sniff;
                PreparedStatement readCSVStatement = null;

                boolean worked = false;

                try {
                    log.trace(DDL)
                    readCSVStatement = connection.prepareStatement(DDL);

                    // There is a bug where this returns false even though it succeeds
                    readCSVStatement.execute();

                    {
                        try {
                            readCSVStatement.close();
                        }
                        catch (SQLException sqle2) {
                            log.warn(sqle2.toString())
                        }
                    }


                    ResultSet rows = null;
                    try {
                        // See if it actually worked or not by issuing a SELECT over the table

                        final String didItWork = "SELECT * FROM \"" + tableName + "\" LIMIT 1";

                        rows = connection.prepareStatement(didItWork).executeQuery();
                        rows.next();
                        worked = true;
                        rows.close();
                    }
                    catch (SQLException sqle) {
                        // Nothing to do
                        log.error(sqle.toString())
                    }
                    finally {
                        if (rows != null) {
                            try {
                                rows.close();
                                rows = null;
                            }
                            catch (SQLException sqle2) {
                            }
                        }

                    }
                }
                catch (SQLException sqlPrepare) {
                    log.warn(sqlPrepare.toString())
                }
                finally {
                    if (readCSVStatement != null) {
                        try {
                            readCSVStatement.close();
                        }
                        catch (SQLException sqle2) {
                            log.warn(sqle2.toString())
                        }
                    }
                }

                if (worked) {
                    log.info("Created table " + tableName + " from " + child.toString())
                    anyWorked=true;
                    break;
                }
            }
            if(!anyWorked)
            {
                throw new Exception("Failed to create table " + tableName + " from " + child.toString())
            }

            // Import columns as DataElements

            String columnsStatementQuery=
                    """\
                    SELECT *
                    FROM system.information_schema.columns
                    WHERE
                        table_name=?
                    ORDER BY table_schema, table_name, ordinal_position;
                    """

            PreparedStatement columnsStatement = connection.prepareStatement(columnsStatementQuery)
            columnsStatement.setString(1, tableName)

            List<Map<String, Object>> columnResults = Util.resultSetToList(columnsStatement.executeQuery()).findAll { Map column ->
                includedBy(column.column_name as String, params.includeColumnsLike) && !matchesAny(column.column_name as String, params.excludeColumnsLike)
            }
            if (columnResults.isEmpty()) {
                connection.prepareStatement("DROP TABLE IF EXISTS \"${tableName}\"").execute()
                continue
            }


            // Import DataTypes to the top-level DataModel
            List<Object> dataTypeLabels = columnResults.collect { it.data_type}.unique()

            log.info(columnResults.toString())
            log.info(dataTypeLabels.toString())

            Map<String, DataType> dataTypeMap = [:]
            dataTypeLabels.each {label ->
                String labelString=(String) label

                DataType dataType = new DataType(dataTypeKind: DataType.DataTypeKind.PRIMITIVE_TYPE, label: labelString)
                addMetadata(dataType.metadata,new Metadata(namespace: NAMESPACE_EXPLORER_QUERY, key: 'querybuildertype', value: Util.getMauroDataType(labelString)))

                log.info(labelString+" -> "+Util.getMauroDataType(labelString));

                if((dataModel.dataTypes.findAll {it.label==dataType.label}).isEmpty())
                {
                    dataModel.dataTypes << dataType
                }
                dataTypeMap.put(labelString, dataType)
            }

            // // Import DataElements
            columnResults.each {columnMap ->
                DataElement dataElement = new DataElement(label: Util.sanitiseForMauroLabel(Util.normaliseLabelCase((String) columnMap.column_name)), minMultiplicity: columnMap.is_nullable ? 0 : 1, maxMultiplicity: 1, dataType: dataTypeMap[columnMap.data_type], description: columnMap.comment, order: ((String) columnMap.ordinal_position).toInteger())

                addDuckResultsAsMetadata(dataElement, columnMap, params);
                addMetadata(dataElement.metadata, new Metadata(namespace: namespace(params), key: 'sourceIdentifier', value: "${sourceRoot == null ? filename : sourceRoot.relativize(child).toString()}/${columnMap.column_name}"))
                addMetadata(dataElement.metadata, new Metadata(namespace: namespace(params), key: 'duckdbColumnName', value: columnMap.column_name))

                dataClass.dataElements << dataElement

                log.info "table: [${columnMap.table_name}], column: [${columnMap.column_name}]"
            }

            importDuckRowCounts(dataClass, tableName, connection, namespace(params));
            if (params.detectEnumerations != false) importDuckEnumerationValues(dataModel,dataClass, tableName, connection, params);
            if (params.generateSummaryMetadata != false) {
                importDuckSummaryMetadataForEnumerations(dataClass,tableName,connection);
                importDuckSummaryMetadataForDatesAndNumbers(dataClass,tableName,connection);
            }

            // Make sure every element has a suggestion score for the explorer, if it hasn't been
            for(DataElement dataElement : dataClass.dataElements)
            {
                final Metadata suggestionIndexMetaData=Util.getMetadata(dataElement.metadata,NAMESPACE_EXPLORER,'suggestionIndex');
                if(suggestionIndexMetaData==null)
                {
                    // Use the distinct count and the number of rows to create a basic measure of entropy - and this will become the suggestionIndex
                    // imbalance would be 1.0-entropy, but it would be such a crude measure, there is no reason to include it.

                    final Metadata distinctValuesCount=Util.getMetadata(dataElement.metadata, NAMESPACE_EXPLORER, 'distinctValuesCount');
                    final Metadata rowCount=Util.getMetadata(dataElement.metadata,NAMESPACE_EXPLORER,'rowCount');

                    if(distinctValuesCount!=null && rowCount!=null)
                    {
                        final float entropy=Util.calculateBasicEntropy(Long.parseLong(distinctValuesCount.value,10), Long.parseLong(rowCount.value,10))
                        addMetadata(dataElement.metadata,new Metadata(namespace: NAMESPACE_EXPLORER, key: 'entropy', value: entropy))
                        addMetadata(dataElement.metadata,new Metadata(namespace: NAMESPACE_EXPLORER, key: 'suggestionIndex', value: entropy))
                    }

                }
            }

            // Remove meta data for numeric and date max and min values

            for (DataElement dataElement : dataClass.dataElements) {
                Metadata minMetadata=dataElement.metadata.find {it.key == 'min_value'}
                Metadata maxMetadata=dataElement.metadata.find {it.key == 'max_value'}
                if(minMetadata) {
                    dataElement.metadata.remove(minMetadata)
                }
                if(maxMetadata) {
                    dataElement.metadata.remove(maxMetadata)
                }
            }

            // Drop the table - avoids naming clashes

            boolean dropped=false;
            final String drop="DROP TABLE \""+tableName+"\";"
            final PreparedStatement dropStatement = connection.prepareStatement(drop);

            try
            {
                log.info(drop)
                dropped=dropStatement.execute()
            }
            catch(SQLException sqled)
            {

            }
            finally
            {
                try
                {
                    dropStatement.close();
                }
                catch(SQLException sqle2){}
            }
            if(!dropped)
            {
                log.warn("Failed to drop table: "+tableName)
            }

        }

        for(Path child : children)
        {
            if (!Files.isDirectory(child))
            {
                continue
            }
            final String filename=child.getFileName().toString()
            final String folderName=Util.normaliseLabelCase(removeFileNameSuffixes( filename  ))

            boolean schemaDirectory = sourceRoot != null && child.getParent() == sourceRoot
            if (schemaDirectory && (!includedBy(folderName, params.includeSchemas) || matchesAny(folderName, params.excludeSchemas))) continue
            final DataClass dataClass = new DataClass(label: Util.sanitiseForMauroLabel(folderName))
            addMetadata(dataClass.metadata, new Metadata(namespace: namespace(params), key: 'sourceIdentifier', value: sourceRoot == null ? filename : sourceRoot.relativize(child).toString()))
            addClass(parent,dataClass)

            final File[] files=child.toFile().listFiles();
            final ArrayList<Path> myChildren=new ArrayList<>(files.length);
            for(int p=0;p<files.length;p++)
            {
                myChildren.add(files[p].toPath())
            }
            recurseDirectory(dataClass, myChildren, dataModel, connection, params, rootModel, rootName, sourceRoot)
        }
    }

    private static String[] dataSuffixes=new String[]
    {
            ".csv",
            ".tsv",
            ".psv",
            ".tab"
    };

    private static String[] archiveSuffixes=new String[]
    {
            ".zip"
    }

    static String namespace(CSVImportParams params) {
        params.metadataNamespace?.trim() ? params.metadataNamespace.trim() : NAMESPACE_ME
    }

    static String toDuckList(List<String> values) {
        '[' + values.collect { "'${it.replace("'", "''")}'" }.join(', ') + ']'
    }

    /** SQL LIKE semantics: percent matches any sequence and underscore matches one character. */
    static boolean matchesAny(String identifier, List<String> patterns) {
        if (!patterns) return false
        patterns.any { String pattern ->
            StringBuilder regex = new StringBuilder('(?is)^')
            pattern.toCharArray().each { char ch ->
                if (ch == '%') regex.append('.*')
                else if (ch == '_') regex.append('.')
                else regex.append(java.util.regex.Pattern.quote(String.valueOf(ch)))
            }
            regex.append('$')
            identifier ==~ regex.toString()
        }
    }

    static boolean includedBy(String identifier, List<String> patterns) {
        !patterns || matchesAny(identifier, patterns)
    }

    static String sourceColumn(DataElement element) {
        element.metadata.find { it.key == 'duckdbColumnName' }?.value?.toString() ?: element.label
    }

    static String quotedIdentifier(String identifier) {
        '"' + identifier.replace('"', '""') + '"'
    }

    private static boolean hasSuffix(final String fileName, final String[] suffixes)
    {
        final lc_fileName=fileName.toLowerCase()
        for(String suffix : suffixes)
        {
            if(lc_fileName.endsWith(suffix)){return true;}
        }

        return false
    }

    private static String removeFileNameSuffixes(String fileName) {

        return removeFileNameSuffixes(removeFileNameSuffixes(fileName,archiveSuffixes),dataSuffixes);
    }

    private static String removeFileNameSuffixes(String fileName, final String[] suffixes)
    {
        pruning:
        for(;;)
        {
            final lc_fileName=fileName.toLowerCase()

            for(String suffix : suffixes)
            {
                if(lc_fileName.endsWith(suffix))
                {
                    fileName=fileName.take(fileName.length()-suffix.length())
                    continue pruning
                }
            }

            break
        }

        fileName
    }

    @Override
    Boolean handlesContentType(String contentType) {
        false
    }

    @Override
    Class<CSVImportParams> importParametersClass() {
        CSVImportParams
    }

    static void importDuckRowCounts(final DataClass dataClass, final String tableName, final Connection connection, String namespace = NAMESPACE_ME)
    {
        final String countAll="count(*) __count_all";
        final String countDistinct=dataClass.dataElements.collect {
            String col = quotedIdentifier(sourceColumn(it))
            String validValueFilter = "${col} IS NOT NULL ${Util.isString(it) ? "AND ${col} <> '' AND ${col} <> '<null>' AND ${col} <> '<no data>' AND ${col} <> '<blank>'" : ''} ${Util.isNumeric(it) ? "AND ${col} <> 0" : ''}"
            "count(distinct ${col}) FILTER (WHERE ${validValueFilter}) ${quotedIdentifier(it.label.toLowerCase())}"
        }.join(', ')+"\n";
        final String countMinMax=dataClass.dataElements.findAll {Util.isDate(it) || Util.isNumeric(it)}.collect {"min(${quotedIdentifier(sourceColumn(it))}) ${quotedIdentifier(it.label.toLowerCase() + '_min')}, max(${quotedIdentifier(sourceColumn(it))}) ${quotedIdentifier(it.label.toLowerCase() + '_max')}"}.join(', ')+"\n";
        final String countMaxLen=dataClass.dataElements.findAll {Util.isString(it) }.collect {"max(len(${quotedIdentifier(sourceColumn(it))})) ${quotedIdentifier(it.label.toLowerCase() + '_max_len')} "}.join(', ')+"\n";
        final String countNotNull=dataClass.dataElements.collect{
            String col = quotedIdentifier(sourceColumn(it))
            "count(${col}) FILTER (WHERE ${col} IS NOT NULL "+
                    "${Util.isString(it)?" AND ${col} <> '' AND ${col} <> '<null>' AND ${col} <> '<no data>' AND ${col} <> '<blank>' ":""}"+
                    "${Util.isNumeric(it)?" AND ${col} <> 0 ":""}"+
                    ") ${quotedIdentifier(it.label.toLowerCase() + '_not_null')}"
        }.join(', ')+"\n";

        String query ='select \n' + [countAll,countDistinct,countMinMax,countMaxLen,countNotNull].findAll{!it.trim().isEmpty()}.join(" ,\n") + "\nfrom \"${tableName}\"";

        log.info(query)

        PreparedStatement countsStatement = connection.prepareStatement(query)
        Map<String, Object> counts = Util.resultSetToList(countsStatement.executeQuery()).first()


        addMetadata(dataClass.metadata,new Metadata(namespace: namespace, key: 'row_count', value: counts['__count_all']))
        dataClass.dataElements.each {DataElement dataElement ->
            addMetadata(dataElement.metadata,new Metadata(namespace: namespace, key: 'distinct_values_count', value: counts[dataElement.label.toLowerCase()]))
            addMetadata(dataElement.metadata,new Metadata(namespace: NAMESPACE_EXPLORER, key: 'distinctValuesCount', value: counts[dataElement.label.toLowerCase()] ))
            addMetadata(dataElement.metadata,new Metadata(namespace: NAMESPACE_EXPLORER, key: 'rowCount', value: counts['__count_all'] ))
            addMetadata(dataElement.metadata,new Metadata(namespace: NAMESPACE_EXPLORER, key: 'notNullValuesCount', value: counts[ (dataElement.label.toLowerCase() + '_not_null') ] ))


        }
        dataClass.dataElements.findAll {Util.isDate(it) || Util.isNumeric(it)}.each {DataElement dataElement ->
            addMetadata(dataElement.metadata,new Metadata(namespace: namespace, key: 'min_value', value: counts[(dataElement.label.toLowerCase() + '_min') ]))
            addMetadata(dataElement.metadata,new Metadata(namespace: namespace, key: 'max_value', value: counts[(dataElement.label.toLowerCase() + '_max') ]))
        }
        dataClass.dataElements.findAll {Util.isString(it) }.each {DataElement dataElement ->
            Object max_len=counts[(dataElement.label.toLowerCase() + '_max_len') ];
            if(max_len==null)
            {
                max_len=0L;
            }
            addMetadata(dataElement.metadata,new Metadata(namespace: namespace, key: 'max_string_length', value: max_len))
        }
    }

    static void importDuckEnumerationValues(final DataModel dataModel, final DataClass dataClass, final String tableName, final Connection connection, CSVImportParams params = new CSVImportParams())
    {
        // Values can be enumerated if the number of distinct values is less than or equals to Util.MAX_ENUMERATION_VALUES
        // and for VARCHAR, the maximum length is less than or equals to Util.MAX_ENUMERATION_VALUE_LENGTH

        if(dataClass.dataElements==null || dataClass.dataElements.size()==0)
        {
            log.warn(dataClass.label+" has no data elements!")
            return;
        }

        List<DataElement> enumerationColumns = []

        for(DataElement column : dataClass.dataElements)
        {
            boolean isEnumerationColumn = false
            boolean excludedFromEnumeration = (params.enumIgnoreColumn ?: []).any { column.label.equalsIgnoreCase(it) } ||
                (params.enumIgnoreColumnLike ?: []).any { column.label.toLowerCase().contains(it.toLowerCase()) }
            final Metadata distinctValuesMetadata = column.metadata.find {it.key == 'distinct_values_count'}
            final Metadata notNullValuesCountMetadata = column.metadata.find {it.key == 'notNullValuesCount'}

            if ((params.enumGivenAdornment ?: []).any { column.label.toLowerCase().endsWith(it.toLowerCase()) } ||
                (params.enumLookupAdornment ?: []).any { column.label.toLowerCase().endsWith(it.toLowerCase()) }) isEnumerationColumn = true

            if (!isEnumerationColumn && !excludedFromEnumeration && distinctValuesMetadata != null && distinctValuesMetadata.value.toLong() <= (params.maxEnumerations ?: 80)) isEnumerationColumn = true
            if (excludedFromEnumeration) isEnumerationColumn = false
            if (isEnumerationColumn && notNullValuesCountMetadata != null && distinctValuesMetadata != null) {
                final long notNullValuesCount = notNullValuesCountMetadata.value.toLong()
                long distinctValues = distinctValuesMetadata.value.toLong()

                if (notNullValuesCount == 0) {
                    isEnumerationColumn = false
                    log.warn('excluding {}.{} as enumeration, column is all null', dataClass.label, column.label)
                    addMetadata(column.metadata, new Metadata(namespace: NAMESPACE_EXPLORER, key: 'allNull', value: true))
                } else if (notNullValuesCount < 3 * distinctValues) {
                    isEnumerationColumn = false
                    log.warn('excluding {}.{} as enumeration, values are too distinct', dataClass.label, column.label)
                } else {
                    Metadata rowCountMetadata = column.metadata.find { it.namespace == NAMESPACE_EXPLORER && it.key == 'rowCount' }
                    if (rowCountMetadata != null && notNullValuesCount / (double) rowCountMetadata.value.toLong() < 0.33d * (distinctValues / (double) (distinctValues + 1))) {
                        addMetadata(column.metadata, new Metadata(namespace: NAMESPACE_EXPLORER, key: 'mostlyNull', value: true))
                    }
                }
            }

            // Strings have a maximum length
            if(Util.isString(column))
            {
                final Metadata maxStringLengthObjectMetadata=column.metadata.find {it.key == 'max_string_length'}
                if(maxStringLengthObjectMetadata!=null) {
                    final Object maxStringLengthObject = maxStringLengthObjectMetadata.value;
                    if (maxStringLengthObject != null) {
                        long maxStringLength = maxStringLengthObject.toLong()

                        if (maxStringLength > 100) {
                            isEnumerationColumn = false
                            log.info(column.label + " has a maximum string length greater than " + Util.MAX_ENUMERATION_VALUE_LENGTH);
                        }
                        if (maxStringLength == 0) {
                            isEnumerationColumn = false
                            log.info(column.label + " appears to be empty");
                        }
                    }
                }

            }

            // Only strings, numbers, or date types
            if(!Util.isString(column) && !Util.isNumeric(column) && !Util.isDate(column)){
                isEnumerationColumn = false
                log.info(column.label+" is not a string, int nor date: "+column.dataType.label)
            }

            // Add it to the list if it passes

            if (isEnumerationColumn){enumerationColumns << column}

        }

        if (!enumerationColumns) {log.trace("No enumerations detected in "+tableName); return }

        log.trace("Enumerations found in "+tableName+":");
        enumerationColumns.forEach{log.trace(it.label) }
        log.trace("")

        // Get histogram maps for each column

        List<String> subqueries=new ArrayList<>(enumerationColumns.size());
        for(DataElement column : enumerationColumns)
        {
            subqueries.add("histogram(${quotedIdentifier(sourceColumn(column))}) ${quotedIdentifier(column.label.toLowerCase())}");
        }

        String query = 'select ' + subqueries.join(', ') + ' from \"'+tableName+'\"'

        PreparedStatement enumerationValuesStatement = connection.prepareStatement(query)
        List<Map<String,Object>> listOfEnumerationValuesMaps=Util.resultSetToList(enumerationValuesStatement.executeQuery())

        Map<String,Object> enumerationValuesMaps = listOfEnumerationValuesMaps.first()

        log.info("enumerationValuesMaps")
        log.info(enumerationValuesMaps.toString())

        for(DataElement column : enumerationColumns)
        {
            DataType enumerationType = new DataType(label: Util.sanitiseForMauroLabel("${tableName}.${column.label}"))
            enumerationType.domainType = DataType.DataTypeKind.ENUMERATION_TYPE

            Map<Object,Object> valueMap=(Map<Object,Object>) enumerationValuesMaps[column.label.toLowerCase()];
            if(valueMap==null)
            {
                continue
            }
            if (valueMap.size() > 20000) {
                log.warn('Skipping enumeration {}.{} because it exceeds the SQL importer maximum value count', dataClass.label, column.label)
                continue
            }

            Iterator<Object> valueMapKeysIterator=valueMap.keySet().iterator();
            int idx=0;
            while(valueMapKeysIterator.hasNext())
            {
                final Object keyObject=valueMapKeysIterator.next();

                String key=keyObject.toString()
                String str = key.replaceAll("[^\\p{Print}]", "�")

                if (key != str) {
                    log.warn "importEnumerationValues - non printable character(s) removed from enumeration value string for column [${tableName}.${column.label}]"
                    log.trace(key)
                    log.trace(str)
                }
                if (str) {
                    enumerationType.enumerationValues << new EnumerationValue(key: Util.sanitiseForMauroLabel(str), value: str, order: idx)
                } else {
                    log.warn "enumeration value is null/blank! str: [$str], for column [${tableName}.${column.label}]"
                }

                idx++;
            }

            if (enumerationType.enumerationValues) {
                if (enumerationType.enumerationValues.key.size() == enumerationType.enumerationValues.key.toSet().size()) {
                    dataModel.dataTypes << enumerationType
                    column.dataType = enumerationType
                } else {
                    log.warn "Skipping EnumerationType [$enumerationType.label] because keys are not unique"
                }

            } else {
                column.maxMultiplicity = 0
            }
        }

    }

    static void importDuckSummaryMetadataForEnumerations(final DataClass dataClass, final String tableName, final Connection connection) {

        List<DataElement> enumerationColumns = dataClass.dataElements.findAll {it.dataType.dataTypeKind == DataType.DataTypeKind.ENUMERATION_TYPE}

        if (!enumerationColumns) return
        log.trace("enumerationColumns.size() "+enumerationColumns.size());
        log.trace(enumerationColumns.toString())

        List<String> subqueries = enumerationColumns.collect {DataElement column ->
            String columnName = column.label
            String AS=columnName.toLowerCase()

            """\
            (
                SELECT CAST( to_json(histogram("$columnName")) AS VARCHAR ) FROM "$tableName"
            ) "$AS"
            """.toString()
        }

        String query = "SELECT ${subqueries.join(', ')}"

        PreparedStatement summaryMetadataStatement = connection.prepareStatement(query)
        List<Map<String, Object>> summaryMetadataJsonList=Util.resultSetToList(summaryMetadataStatement.executeQuery())
        Map<String, Object> summaryMetadataJson = summaryMetadataJsonList.first()

        for(DataElement it : enumerationColumns)
        {
            final String AS=it.label.toLowerCase()
            final Object reportValueObject=summaryMetadataJson[AS];

            if(reportValueObject==null)
            {
                log.error("Metadata for enumerations: failed to look up "+tableName+"."+it.label);
                throw new Exception("Metadata for enumerations: failed to look up "+tableName+"."+it.label)
            }

            log.trace("reportValueObject")
            log.trace(reportValueObject.class.getName())

            String reportValue=reportValueObject.toString();

            SummaryMetadata summaryMetadata = new SummaryMetadata(label: it.label, summaryMetadataType: SummaryMetadataType.MAP, summaryMetadataReports: [])
            summaryMetadata.summaryMetadataReports << new SummaryMetadataReport(reportValue: reportValue, reportDate: Instant.now())
            it.summaryMetadata << summaryMetadata
            dataClass.summaryMetadata << summaryMetadata

            JsonSlurper slurper=new JsonSlurper()
            addMetadata(it.metadata,new Metadata(namespace: NAMESPACE_EXPLORER, key: 'entropy', value: Util.calculateEntropy( (Map) slurper.parseText(reportValue) ) ))
            addMetadata(it.metadata,new Metadata(namespace: NAMESPACE_EXPLORER, key: 'imbalance', value: Util.calculateImbalance( (Map) slurper.parseText(reportValue) ) ))
            addMetadata(it.metadata,new Metadata(namespace: NAMESPACE_EXPLORER, key: 'suggestionIndex', value: Util.calculateSuggestionIndex( (Map) slurper.parseText(reportValue) ) ))
        }
    }

    static void importDuckSummaryMetadataForDatesAndNumbers( final DataClass dataClass, final String tableName, final Connection connection) {
        List<DataElement> dateAndNumericElements = dataClass.dataElements.findAll {Util.isDate(it) || Util.isNumeric(it)}

        List<String> histogramSelects = dateAndNumericElements.collect { DataElement dataElement ->
            String columnSql = quotedIdentifier(sourceColumn(dataElement))
            if (Util.isDate(dataElement)) {

                Metadata minMetadata=dataElement.metadata.find {it.key == 'min_value'}
                Metadata maxMetadata=dataElement.metadata.find {it.key == 'max_value'}
                if(minMetadata !=null && maxMetadata!=null) {

                    String minValue = minMetadata.value
                    String maxValue = maxMetadata.value
                    if (minValue && maxValue) {

                        try {
                            LocalDate minDate = Util.parseISO_LOCAL_DATE(minValue);
                            LocalDate maxDate = Util.parseISO_LOCAL_DATE(maxValue);
                            long days = ChronoUnit.DAYS.between(minDate, maxDate)

                            String keyExpression
                            if (days > 70000) {
                                keyExpression = "concat(CAST(CAST(floor(extract('year' from ${columnSql}) / 100) * 100 AS INTEGER) AS VARCHAR), '-', CAST(CAST(floor(extract('year' from ${columnSql}) / 100) * 100 + 99 AS INTEGER) AS VARCHAR))"
                            } else if (days > 7000) {
                                keyExpression = "concat(CAST(CAST(floor(extract('year' from ${columnSql}) / 10) * 10 AS INTEGER) AS VARCHAR), '-', CAST(CAST(floor(extract('year' from ${columnSql}) / 10) * 10 + 9 AS INTEGER) AS VARCHAR))"
                            } else if (days > 700) {
                                keyExpression = "CAST(extract('year' from ${columnSql}) AS VARCHAR)"
                            } else if (days > 70) {
                                keyExpression = "strftime(${columnSql}, '%Y-%m')"
                            } else {
                                keyExpression = "strftime(${columnSql}, '%Y-%m-%d')"
                            }
                            """\
                            (
                              SELECT CAST(to_json(map_from_entries(array_agg(row(keyName, valueCount) ORDER BY keyName))) AS VARCHAR)
                              FROM (
                                SELECT $keyExpression AS keyName, count(*) AS valueCount
                                FROM "$tableName"
                                WHERE ${columnSql} IS NOT NULL
                                GROUP BY keyName
                              )
                            ) "${dataElement.label.toLowerCase()}"
                            """
                        }
                        catch (DateTimeParseException dtpe) {
                            log.error("Histogram. Date parse error in " + tableName + "." + dataElement.label);
                            log.error("minValue=" + minValue);
                            log.error("maxValue=" + maxValue);
                        }
                    }
                }
            }
            else
            if(Util.isInteger(dataElement)) {
                Metadata minMetadata = dataElement.metadata.find { it.key == 'min_value' }
                Metadata maxMetadata = dataElement.metadata.find { it.key == 'max_value' }
                Metadata distinctValuesCountMetadata = dataElement.metadata.find { it.key == 'distinct_values_count' }

                if (minMetadata != null && maxMetadata != null && distinctValuesCountMetadata != null) {
                    String minValue = minMetadata.value
                    String maxValue = maxMetadata.value
                    String distinctValuesCount = distinctValuesCountMetadata.value

                    if (minValue && maxValue) {
                        final long minLong = Long.parseLong(minValue, 10);
                        final long maxLong = Long.parseLong(maxValue, 10);

                        final long interval = maxLong - minLong;

                        long distinctValuesCountLong;
                        if (distinctValuesCount) {
                            distinctValuesCountLong = Long.parseLong(distinctValuesCount, 10);
                        } else {
                            distinctValuesCountLong = interval;
                        }

                        long lowestBinValue = Util.makeLowestIntervalValueForBin(minLong);
                        long highestBinValue = Util.makeHighestIntervalValueForBin(maxLong);
                        long binInterval = Util.makeBinInterval(distinctValuesCountLong, lowestBinValue, highestBinValue);

                        /*log.trace("min .. max {} .. {}",minLong,maxLong)
                    log.trace("lowestBinValue {}",lowestBinValue);
                    log.trace("highestBinValue {}",highestBinValue);
                    log.trace("distinctValuesCountLong {}",distinctValuesCountLong);
                    log.trace("binInterval {}",binInterval);
                     */

                        // group by binInterval
                        // The SQL needs to convert the value to a bin: lowestBinValue + (floor(value / binInterval) * binInterval)
                        // The interval is binStart to binStart + binInterval -1

                        String binLabelExpression = binInterval == 1 ? 'CAST(binStart AS VARCHAR)' : "concat(binStart,'-',binStart+${binInterval - 1})"
                        """\
                    (
                    SELECT CAST(to_json(map_from_entries(array_agg(row(keyName,count) ORDER BY binStart))) AS VARCHAR)
                    FROM
                    (
                        SELECT binStart, ${binLabelExpression} keyName , count
                        FROM
                        (
                            SELECT binStart, count(*) count
                            FROM
                            (
                                SELECT $lowestBinValue + CAST(floor((${columnSql} - $lowestBinValue)/$binInterval)*$binInterval AS BIGINT) AS binStart
                                FROM "$tableName"
                                WHERE binStart IS NOT NULL
                                ORDER BY binStart
                            )
                            GROUP BY binStart
                            ORDER BY binStart
                        )
                    )
                    ) "${dataElement.label.toLowerCase()}"
                    """
                    }
                }
            }
        }.findAll() as List<String>

        if (!histogramSelects) return

        String histogramQuery = 'select ' + histogramSelects.join(', ');

        log.trace("importDuckSummaryMetadataForDatesAndNumbers")
        log.trace(histogramQuery)

        PreparedStatement histogramStatement = connection.prepareStatement(histogramQuery)
        List<Map<String, Object>> histogramJsonList=Util.resultSetToList(histogramStatement.executeQuery());
        Map<String, Object> histogramJson = histogramJsonList.first()

        log.trace(histogramJson.toString())

        dateAndNumericElements.findAll {histogramJson[it.label.toLowerCase()]}.each {
            Object reportValueObject=histogramJson[it.label.toLowerCase()]
            String reportValue=reportValueObject.toString();
            SummaryMetadata summaryMetadata = new SummaryMetadata(label: it.label, summaryMetadataType: SummaryMetadataType.MAP, summaryMetadataReports: [])
            summaryMetadata.summaryMetadataReports << new SummaryMetadataReport(reportValue: reportValue, reportDate: Instant.now())
            it.summaryMetadata << summaryMetadata
            dataClass.summaryMetadata << summaryMetadata

            JsonSlurper slurper=new JsonSlurper()
            addMetadata(it.metadata,new Metadata(namespace: NAMESPACE_EXPLORER, key: 'entropy', value: Util.calculateEntropy( (Map) slurper.parseText(reportValue) ) ))
            addMetadata(it.metadata,new Metadata(namespace: NAMESPACE_EXPLORER, key: 'imbalance', value: Util.calculateImbalance( (Map) slurper.parseText(reportValue) ) ))
            addMetadata(it.metadata,new Metadata(namespace: NAMESPACE_EXPLORER, key: 'suggestionIndex', value: Util.calculateSuggestionIndex( (Map) slurper.parseText(reportValue) ) ))
        }
    }

    private static AdministeredItem addDuckResultsAsMetadata(AdministeredItem item, Map<String, Object> results, CSVImportParams params) {
        results.findAll {it.key && it.value}.each {
            if (it.key != 'table_catalog' && it.key != 'table_schema') {
                addMetadata(item.metadata,new Metadata(namespace: namespace(params), key: Util.sanitiseForMauroLabel(it.key), value: it.value))
            }
        }
        item
    }

    private static File newFile(final File destinationDir, final ZipEntry zipEntry) throws IOException
    {
        final File destFile = new File(destinationDir, zipEntry.getName())

        final String destDirPath = destinationDir.getCanonicalPath()
        final String destFilePath = destFile.getCanonicalPath()

        if (!destFilePath.startsWith(destDirPath + File.separator)) {
            throw new IOException("Entry is outside of the target dir: " + zipEntry.getName())
        }

        return destFile;
    }

    private static void checkConnection(Connection connection)
    {
        log.info 'Connection open'
        log.info 'Testing connection...'

        String testSql = 'select 1+NULL, 1=1'
        PreparedStatement testStatement = connection.prepareStatement(testSql)
        Map<String, Object> testResults = Util.resultSetToList(testStatement.executeQuery()).first()
        assert testResults.values().toList() == [null, Boolean.TRUE]

        log.info('Connection OK')

                {
                    PreparedStatement databasesStatement = connection.prepareStatement('SHOW DATABASES;')
                    Map<String, Object> databaseResults = Util.resultSetToList(databasesStatement.executeQuery()).first()
                    log.info(databaseResults.toString())
                }

        log.info('Testing if DDL works...')

                {
                    String ddlSql = 'CREATE TABLE TestTable (i INTEGER);'
                    PreparedStatement testDDLStatement = connection.prepareStatement(ddlSql)
                    assert testDDLStatement.execute()
                }
    }

    private static void addMetadata(final List<Metadata> list, final Metadata metadata)
    {
        if(list==null){return;}
        if(metadata==null){return;}
        if(metadata.value==null){return;}

        list.add(metadata)
    }


}
