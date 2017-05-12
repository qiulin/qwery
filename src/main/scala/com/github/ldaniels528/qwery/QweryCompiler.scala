package com.github.ldaniels528.qwery

import com.github.ldaniels528.qwery.ops._
import com.github.ldaniels528.qwery.sources._

import scala.util.{Failure, Success, Try}

/**
  * Qwery Compiler
  * @author lawrence.daniels@gmail.com
  */
class QweryCompiler {

  /**
    * Compiles the given query (or statement) into an executable
    * @param query the given query string (e.g. "SELECT * FROM './companylist.csv'")
    * @return an [[Executable executable]]
    */
  def apply(query: String): Executable = compile(query)

  /**
    * Compiles the given query (or statement) into an executable
    * @param query the given query string (e.g. "SELECT * FROM './companylist.csv'")
    * @return an [[Executable executable]]
    */
  def compile(query: String): Executable = parseNext(TokenStream(query))

  /**
    * Parses the next query or statement from the stream
    * @param stream the given [[TokenStream token stream]]
    * @return an [[Executable]]
    */
  def parseNext(stream: TokenStream): Executable = {
    stream match {
      case ts if ts.is("DESCRIBE") => parseDescribe(ts)
      case ts if ts.is("INSERT") => parseInsert(ts)
      case ts if ts.is("SELECT") => parseSelect(ts)
      case ts => die("Unexpected end of line", ts)
    }
  }

  private def die[A](message: String, ts: TokenStream): A = {
    throw new SyntaxException(message, ts.peek.orNull)
  }

  /**
    * Parses a DESCRIBE statement
    * @example {{{ DESCRIBE './companylist.csv' }}}
    * @example {{{ DESCRIBE './companylist.csv' LIMIT 5 }}}
    * @param ts the given [[TokenStream token stream]]
    * @return an [[Describe executable]]
    */
  private def parseDescribe(ts: TokenStream): Describe = {
    val params = TemplateParser(ts).extract("DESCRIBE @source ?LIMIT @limit")
    Describe(
      source = params.identifiers.get("source").flatMap(DataSourceFactory.getInputSource)
        .getOrElse(die("No source provided", ts)),
      limit = params.identifiers.get("limit").map(parseLimit))
  }

  /**
    * Parses an INSERT statement
    * @example
    * {{{
    * INSERT INTO './tickers.csv' (symbol, exchange, lastSale)
    * SELECT symbol, exchange, lastSale FROM './EOD-20170505.txt' WHERE exchange = 'NASDAQ'
    * }}}
    * @example
    * {{{
    * INSERT OVERWRITE './tickers.csv' (symbol, exchange, lastSale)
    * SELECT symbol, exchange, lastSale FROM './EOD-20170505.txt' WHERE exchange = 'NASDAQ'
    * }}}
    * @example
    * {{{
    * INSERT OVERWRITE './companyinfo.csv' WITH HINTS (DELIMITER ',', HEADERS ON, QUOTES ON)
    * (Symbol, Name, Sector, Industry, LastSale, MarketCap)
    * SELECT Symbol, Name, Sector, Industry, LastSale, MarketCap
    * FROM './companylist.csv' WHERE Industry = 'EDP Services'
    * }}}
    * @param stream the given [[TokenStream token stream]]
    * @return an [[Insert executable]]
    */
  private def parseInsert(stream: TokenStream): Insert = {
    val parser = TemplateParser(stream)
    val params = parser.extract("INSERT @|mode|INTO|OVERWRITE| @target ( @(fields) )")
    val target = params.identifiers.get("target")
      .flatMap(DataSourceFactory.getOutputSource)
      .getOrElse(throw new SyntaxException("Output source is missing"))
    val fields = params.fields
      .getOrElse("fields", die("Field arguments missing", stream))
    val append = params.identifiers.get("mode").exists(_.equalsIgnoreCase("INTO"))
    val source = stream match {
      case ts if ts.is("VALUES") => parseInsertValues(fields, ts, parser)
      case ts => parseNext(ts)
    }
    Insert(target, fields, source, Hints(append = append))
  }

  /**
    * Parses an INSERT VALUES clause
    * @param fields the corresponding fields
    * @param ts     the [[TokenStream token stream]]
    * @param parser the implicit [[TemplateParser template parser]]
    * @return the resulting [[InsertValues modifications]]
    */
  private def parseInsertValues(fields: Seq[Field], ts: TokenStream, parser: TemplateParser): InsertValues = {
    var valueSets: List[Seq[Expression]] = Nil
    while (ts.hasNext) {
      val params = parser.extract("VALUES ( @{values} )")
      params.expressions.get("values") foreach { values =>
        valueSets = valueSets ::: values :: Nil
      }
    }
    InsertValues(fields, valueSets)
  }

  /**
    * Parses a SELECT query
    * @example
    * {{{
    * SELECT symbol, exchange, lastSale FROM './EOD-20170505.txt'
    * WHERE exchange = 'NASDAQ'
    * }}}
    * @param ts the given [[TokenStream token stream]]
    * @return an [[Select executable]]
    */
  private def parseSelect(ts: TokenStream): Select = {
    val params = TemplateParser(ts).extract(
      "SELECT @{fields} ?FROM @source ?WHERE @<condition> ?GROUP +?BY @(groupBy) ?ORDER +?BY @[orderBy] ?LIMIT @limit")
    Select(
      fields = params.expressions.getOrElse("fields", die("Field arguments missing", ts)),
      source = params.identifiers.get("source").flatMap(DataSourceFactory.getInputSource),
      condition = params.conditions.get("condition"),
      groupFields = params.fields.get("groupBy"),
      sortFields = params.sortFields.get("orderBy"),
      limit = params.identifiers.get("limit").map(parseLimit))
  }

  /**
    * Parses the given text value into an integer
    * @param value the given text value
    * @return an integer value
    */
  private def parseLimit(value: String): Int = {
    Try(value.toInt) match {
      case Success(limit) => limit
      case Failure(e) =>
        throw new SyntaxException("Numeric value expected for LIMIT", cause = e)
    }
  }

}

/**
  * Qwery Compiler Singleton
  * @author lawrence.daniels@gmail.com
  */
object QweryCompiler extends QweryCompiler