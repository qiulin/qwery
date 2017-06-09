package com.github.ldaniels528.qwery.sources

import com.github.ldaniels528.qwery.devices.{InputDevice, Record}
import com.github.ldaniels528.qwery.ops.{Hints, NamedExpression, Row, Scope}
import com.github.ldaniels528.qwery.util.JSONHelper._
import net.liftweb.json.JsonAST.{JArray, JObject}
import net.liftweb.json.parse
import org.slf4j.LoggerFactory

/**
  * JSON Input Source
  * @author lawrence.daniels@gmail.com
  */
case class JSONInputSource(device: InputDevice, hints: Option[Hints]) extends InputSource {
  private lazy val logger = LoggerFactory.getLogger(getClass)
  private var buffer: List[Row] = Nil
  private val jsonPath = hints.map(_.jsonPath).getOrElse(Nil)

  override def read(scope: Scope): Option[Row] = {
    device.read() match {
      case Some(Record(bytes, _, _)) =>
        var json = parse(new String(bytes))
        for {
          path <- jsonPath
          elem <- path.getAsString(scope)
        } json = json \ elem

        val rows = json match {
          case jo: JObject => List(unwrap(jo))
          case ja: JArray => unwrap(ja)
          case jv =>
            logger.info(s"Unhandled JSON value '$jv' (${jv.getClass.getName})")
            Seq(NamedExpression.randomName() -> unwrap(jv)) :: Nil
        }

        buffer = buffer ::: rows
        if (buffer.isEmpty) None else {
          val row = buffer.headOption
          buffer = buffer.tail
          row
        }
      case None => None
    }
  }

}

/**
  * JSON Input Source Companion
  * @author lawrence.daniels@gmail.com
  */
object JSONInputSource extends InputSourceFactory {

  override def findInputSource(device: InputDevice, hints: Option[Hints]): Option[InputSource] = {
    if (hints.exists(_.isJson.contains(true))) Option(JSONInputSource(device, hints)) else None
  }

}