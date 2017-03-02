import java.net.URI
import java.nio.file.Paths

import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.concurrent.ExecutionContext.Implicits.global
import slick.backend.DatabaseConfig
import slick.codegen.SourceCodeGenerator
import slick.driver.JdbcProfile

trait TableFileGenerator { self: SourceCodeGenerator =>
  def writeTablesToFile(profile: Option[String],
                        folder: String,
                        pkg: String,
                        fileName: String): Unit
}

trait RowFileGenerator { self: SourceCodeGenerator =>
  def writeRowsToFile(folder: String, pkg: String, fileName: String): Unit
}

object Generator {

  private def outputSchemaCode(schemaName: String,
                               profile: Option[String],
                               folder: String,
                               pkg: String,
                               tableGen: TableFileGenerator,
                               rowGen: RowFileGenerator): Unit = {
    val camelSchemaName = schemaName.split('_').map(_.capitalize).mkString("")

    tableGen.writeTablesToFile(profile = profile,
                               folder = folder,
                               pkg = pkg,
                               fileName = s"${camelSchemaName}Tables.scala")
    rowGen.writeRowsToFile(folder = folder,
                           pkg = pkg,
                           fileName = s"${camelSchemaName}Rows.scala")
  }

  def run(uri: URI,
          pkg: String,
          schemaNames: Option[List[String]],
          outputPath: String,
          manualForeignKeys: Map[(String, String), (String, String)],
          parentType: Option[String],
          idType: Option[String],
          header: String,
          schemaImports: List[String],
          typeReplacements: Map[String, String]) = {
    val dc: DatabaseConfig[JdbcProfile] =
      DatabaseConfig.forURI[JdbcProfile](uri)
    val parsedSchemasOpt: Option[Map[String, List[String]]] =
      schemaNames.map(SchemaParser.parse)
    val imports = schemaImports.map("import " + _).mkString("\n")

    try {
      val dbModel: slick.model.Model = Await.result(
        dc.db.run(SchemaParser.createModel(dc.driver, parsedSchemasOpt)),
        Duration.Inf)

      parsedSchemasOpt.getOrElse(Map.empty).foreach {
        case (schemaName, tables) =>
          val profile = Option.empty[String]
          // TODO: retrieve profile later via configuration
          // s"""slick.backend.DatabaseConfig.forConfig[slick.driver.JdbcProfile]("${uri
          //   .getFragment()}").driver"""

          val schemaOnlyModel = Await.result(
            dc.db.run(
              SchemaParser.createModel(dc.driver,
                                       Some(Map(schemaName -> tables)))),
            Duration.Inf)

          val rowGenerator = new RowSourceCodeGenerator(
            model = schemaOnlyModel,
            headerComment = header,
            imports = imports,
            schemaName = schemaName,
            fullDatabaseModel = dbModel,
            idType,
            manualForeignKeys,
            typeReplacements
          )

          val tableGenerator =
            new TableSourceCodeGenerator(schemaOnlyModel = schemaOnlyModel,
                                         headerComment = header,
                                         imports = imports,
                                         schemaName = schemaName,
                                         fullDatabaseModel = dbModel,
                                         pkg = pkg,
                                         manualForeignKeys,
                                         parentType = parentType,
                                         idType,
                                         typeReplacements)

          outputSchemaCode(schemaName = schemaName,
                           profile = profile,
                           folder = outputPath,
                           pkg = pkg,
                           tableGen = tableGenerator,
                           rowGen = rowGenerator)
      }
    } finally {
      dc.db.close()
    }
  }
}
