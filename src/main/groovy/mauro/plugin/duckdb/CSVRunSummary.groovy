package mauro.plugin.duckdb

class CSVRunSummary {
    String sourcePath
    String outputPath
    String outputStructure
    int filesCatalogued
    int modelsCatalogued
    int tablesCatalogued
    int columnsCatalogued
    Set<String> cataloguedModelIdentifiers = new LinkedHashSet<>()
    List<String> skippedFiles = []
    List<String> csvFilesReadIgnoringErrors = []
    List<String> omittedModels = []
    List<String> omittedDirectories = []
    List<String> warnings = []
    String error
    boolean catalogueWritten

    String status() {
        if (error) return 'FAILED'
        if (!catalogueWritten && tablesCatalogued == 0) return 'EMPTY (no catalogue written)'
        if (warnings) return 'COMPLETED WITH WARNINGS'
        skippedFiles ? 'SUCCESS WITH SKIPS' : 'SUCCESS'
    }
}
