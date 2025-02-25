package mauro.plugin.duckdb

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import groovy.transform.CompileStatic
import jakarta.inject.Singleton
import uk.ac.ox.softeng.mauro.domain.datamodel.DataModel
import uk.ac.ox.softeng.mauro.export.ExportModel
import uk.ac.ox.softeng.mauro.plugin.exporter.json.JsonDataModelExporterPlugin

@Singleton
@CompileStatic
class BackwardsCompatibleJsonDataModelExporterPlugin extends JsonDataModelExporterPlugin {

    @Override
    byte[] exportModel(DataModel dataModel) {
        ExportModel exportModel = new ExportModel(this)
        exportModel.dataModel = dataModel
//        objectMapper.writeValueAsBytes(exportModel)

        JsonNode exportModelNode = objectMapper.valueToTree(exportModel)
        JsonNode dataModelNode = exportModelNode.get('dataModel')
        JsonNode dataClassesNode = dataModelNode.get('dataClasses')
        ((ObjectNode) dataModelNode).remove('dataClasses')
        ((ObjectNode) dataModelNode).set('childDataClasses', dataClassesNode)

        objectMapper.writeValueAsBytes(exportModelNode)
    }

    @Override
    byte[] exportModels(Collection<DataModel> dataModels) {
        throw new UnsupportedOperationException('Not implemented')
    }
}
