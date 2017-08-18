package io.iohk.ethereum.ets.common

import java.io.File

import io.iohk.ethereum.utils.Logger
import org.apache.commons.io.FileUtils

import scala.collection.JavaConverters._
import scala.io.Source


object ScenarioLoader extends Logger {

  def load[T](path: String, options: TestOptions): List[ScenarioGroup[T]] = {
    val testDir = new File(getClass.getClassLoader.getResource("ets/VMTests").toURI)
    val files = FileUtils.listFiles(testDir, Array("json"), true).asScala.toList

    files.flatMap { file =>
      val name = file.getAbsolutePath.drop(testDir.getAbsolutePath.length + 1).dropRight(".json".length)

      if (!options.isGroupIncluded(name))
        None
      else {
        log.debug(s"Loading test scenarios from: $file")
        val text = Source.fromFile(file).getLines.mkString
        val scenarios = ScenarioParser.parse[T](text)
        Some(ScenarioGroup(name, scenarios))
      }
    }
  }
}
