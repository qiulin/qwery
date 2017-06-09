package com.github.ldaniels528.qwery

import java.io.FileInputStream
import java.util.Properties

import com.github.ldaniels528.qwery.SQLLanguageParser.{SLPExtensions, die}
import com.github.ldaniels528.qwery.ops.NamedExpression._
import com.github.ldaniels528.qwery.ops.{Expression, _}
import com.github.ldaniels528.qwery.sources.DataResource
import com.github.ldaniels528.qwery.util.OptionHelper._
import com.github.ldaniels528.qwery.util.PeekableIterator
import com.github.ldaniels528.qwery.util.ResourceHelper._
import org.slf4j.LoggerFactory

import scala.io.Source
import scala.util.{Failure, Success, Try}

/**
  * SQL Language Parser
  * @author lawrence.daniels@gmail.com
  */
class SQLLanguageParser(stream: TokenStream) extends ExpressionParser {
  private val logger = LoggerFactory.getLogger(getClass)

  /**
    * Indicates whether the given stream matches the given template
    * @param template the given template
    * @return true, if the stream matches the template from its current position
    */
  def matches(template: String): Boolean = {
    stream.mark()
    try new SQLLanguageParser(stream).process(template).nonEmpty catch {
      case _: Throwable => false
    } finally stream.reset()
  }

  /**
    * Extracts the tokens that correspond to the given template
    * @param template the given template (e.g. "INSERT INTO @table ( %F:fields ) VALUES ( %o:values )")
    * @return a mapping of the extracted values
    */
  def process(template: String): SQLTemplateParams = {
    var results = SQLTemplateParams()
    val tags = new PeekableIterator(template.split("[ ]").map(_.trim))
    while (tags.hasNext) {
      processNextTag(tags.next(), tags) match {
        case Success(params) => results = results + params
        case Failure(e) => throw SyntaxException(e.getMessage, stream)
      }
    }
    results
  }

  /**
    * Extracts and evaluates the next tag in the sequence
    * @param aTag the given tag (e.g. "@fields")
    * @param tags the [[PeekableIterator iteration]] of tags
    * @return the option of the resultant [[SQLTemplateParams template parameters]]
    */
  private def processNextTag(aTag: String, tags: PeekableIterator[String]): Try[SQLTemplateParams] = aTag match {
    // repeat start/end tag? (e.g. "{{values VALUES ( %E:values ) }}" => "VALUES (123, 456) VALUES (345, 678)")
    case tag if tag.startsWith("{{") => processRepeatedSequence(name = tag.drop(2), tags)

    // atom? (e.g. "%a:table" => "'./tickers.csv'")
    case tag if tag.startsWith("%a:") => extractIdentifier(tag.drop(3))

    // conditional expression? (e.g. "%c:condition" => "x = 1 and y = 2")
    case tag if tag.startsWith("%c:") => extractCondition(tag.drop(3))

    // chooser? (e.g. "%C(mode,INTO,OVERWRITE)" => "INSERT INTO ..." || "INSERT OVERWRITE ...")
    case tag if tag.startsWith("%C(") & tag.endsWith(")") => extractChosenItem(tag.chooserParams)

    // assignable expression? (e.g. "%e:expression" => "2 * (x + 1)")
    case tag if tag.startsWith("%e:") => extractAssignableExpression(tag.drop(3))

    // expressions? (e.g. "%E:fields" => "field1, 'hello', 5 + now(), ..., fieldN")
    case tag if tag.startsWith("%E:") => extractListOfExpressions(tag.drop(3))

    // field names? (e.g. "%F:fields" => "field1, field2, ..., fieldN")
    case tag if tag.startsWith("%F:") => extractListOfFields(tag.drop(3))

    // numeric? (e.g. "%n:limit" => "100")
    case tag if tag.startsWith("%n:") => extractNumericValue(tag.drop(3))

    // ordered field list? (e.g. "%o:orderedFields" => "field1 DESC, field2 ASC")
    case tag if tag.startsWith("%o:") => extractOrderedColumns(tag.drop(3))

    // query or expression? (e.g. "%q:query" => "x + 1" | "( SELECT firstName, lastName FROM AddressBook )")
    case tag if tag.startsWith("%q:") => extractSubQueryOrExpression(tag.drop(3))

    // regular expression match? (e.g. "%r`\\d{3,4}S+`" => "123ABC")
    case tag if tag.startsWith("%r`") & tag.endsWith("`") => Try {
      val pattern = tag.drop(3).dropRight(1)
      if (stream.matches(pattern)) SQLTemplateParams() else die(s"Did not match the expected pattern '$pattern'")
    }

    // source/sub-query? (e.g. "%s:query" => "'AddressBook'" | "( SELECT firstName, lastName FROM AddressBook )")
    case tag if tag.startsWith("%s:") => extractSubQueryOrSource(tag.drop(3))

    // sub-query? (e.g. "%S:source" => "( SELECT firstName, lastName FROM AddressBook )")
    case tag if tag.startsWith("%S:") => extractSubQuery(tag.drop(3))

    // variable reference? (e.g. "%v:variable" => "SET @variable = 5")
    case tag if tag.startsWith("%v:") => extractVariableReference(tag.drop(3))

    // with hints? (e.g. "%w:hints" => "WITH JSON FORMAT")
    case tag if tag.startsWith("%w:") => extractWithClause(tag.drop(3))

    // optionally required atom? (e.g. "?ORDER +?BY +?%o:sortFields" => "ORDER BY Symbol DESC")
    case tag if tag.startsWith("+?") => processNextTag(aTag = tag.drop(2), tags)

    // optional tag? (e.g. "?LIMIT +?%n:limit" => "LIMIT 100")
    case tag if tag.startsWith("?") => extractOptional(tag.drop(1), tags)

    // must be literal text
    case text => extractKeyWord(text)
  }

  private def extractKeyWord(keyword: String) = Try {
    if (!stream.nextIf(keyword)) die(s"$keyword expected") else SQLTemplateParams()
  }

  /**
    * Extracts an assignable expression
    * @param name the given identifier name (e.g. "variable")
    * @return a [[SQLTemplateParams template]] representing the parsed outcome
    */
  private def extractAssignableExpression(name: String): Try[SQLTemplateParams] = Try {
    val expr = parseExpression(stream).getOrElse(die("Expression expected"))
    SQLTemplateParams(assignables = Map(name -> expr))
  }

  /**
    * Extracts an expression from the token stream
    * @param name the given identifier name (e.g. "condition")
    * @return a [[SQLTemplateParams template]] representing the parsed outcome
    */
  private def extractCondition(name: String): Try[SQLTemplateParams] = Try {
    val condition = parseCondition(stream)
    SQLTemplateParams(conditions = Map(name -> condition.getOrElse(die("Conditional expression expected"))))
  }

  /**
    * Extracts an enumerated item
    * @param values the values
    * @return a [[SQLTemplateParams template]] representing the parsed outcome
    */
  private def extractChosenItem(values: Seq[String]) = Try {
    def error[A](items: List[String]) = die[A](s"One of the following '${items.mkString(", ")}' identifiers is expected")

    values.toList match {
      case name :: items =>
        val item = stream.peek.map(_.text).getOrElse(error(items))
        if (!items.contains(item)) error(items)
        stream.next() // must skip the token
        SQLTemplateParams(atoms = Map(name -> item))
      case _ =>
        die(s"Unexpected template error: ${values.mkString(", ")}")
    }
  }

  /**
    * Extracts an identifier from the token stream
    * @param name the given identifier name (e.g. "source")
    * @return a [[SQLTemplateParams template]] representing the parsed outcome
    */
  private def extractIdentifier(name: String) = Try {
    val value = stream.peek.map(_.text).getOrElse(die(s"'$name' identifier expected"))
    stream.next()
    SQLTemplateParams(atoms = Map(name -> value))
  }

  /**
    * Extracts a numeric value from the token stream
    * @param name the given identifier name (e.g. "limit")
    * @return a [[SQLTemplateParams template]] representing the parsed outcome
    */
  private def extractNumericValue(name: String) = Try {
    val text = stream.peek.map(_.text).getOrElse(die(s"'$name' numeric expected"))
    Try(text.toDouble) match {
      case Success(value) =>
        stream.next()
        SQLTemplateParams(numerics = Map(name -> value))
      case Failure(_) => die(s"'$name' expected a numeric value")
    }
  }

  /**
    * Extracts a field list by name from the token stream
    * @param name the given identifier name (e.g. "fields")
    * @return a [[SQLTemplateParams template]] representing the parsed outcome
    */
  private def extractListOfFields(name: String) = Try {
    var fields: List[Field] = Nil
    do {
      fields = fields ::: stream.nextOption.map(t => Field(t.text)).getOrElse(die("Unexpected end of statement")) :: Nil
    } while (stream nextIf ",")
    SQLTemplateParams(fields = Map(name -> fields))
  }

  /**
    * Extracts a field argument list from the token stream
    * @param name the given identifier name (e.g. "customerId, COUNT(*)")
    * @return a [[SQLTemplateParams template]] representing the parsed outcome
    */
  private def extractListOfExpressions(name: String) = Try {

    def fetchNext(ts: TokenStream): Expression = {
      val expression = parseExpression(ts)
      val result = if (ts nextIf "AS") expression.map(parseNamedAlias(ts, _)) else expression
      result.getOrElse(die("Unexpected end of statement"))
    }

    var expressions: List[Expression] = Nil
    do expressions = expressions ::: fetchNext(stream) :: Nil while (stream nextIf ",")
    SQLTemplateParams(expressions = Map(name -> expressions))
  }

  /**
    * Extracts an optional tag expression
    * @param tag  the tag to be executed (e.g. "%a:name")
    * @param tags the [[PeekableIterator iterator]]
    */
  private def extractOptional(tag: String, tags: PeekableIterator[String]): Try[SQLTemplateParams] = Try {
    processNextTag(aTag = tag, tags) match {
      case Success(result) => result
      case Failure(e) =>
        while (tags.peek.exists(_.startsWith("+?"))) {
          //logger.info(s"${e.getMessage} skipping: ${tags.peek}")
          tags.next()
        }
        SQLTemplateParams()
    }
  }

  def extractSubQueryOption: Option[Executable] = stream match {
    case ts if ts is "SELECT" =>
      Option(SQLLanguageParser.parseSelect(stream))
    case ts if ts nextIf "(" =>
      val result = Option(SQLLanguageParser.parseNext(stream))
      stream expect ")"
      result
    case _ => None
  }

  /**
    * Parses a source expression; either a direct or via query
    * @param name the named identifier
    * @return the [[SQLTemplateParams SQL template parameters]]
    */
  private def extractSubQueryOrExpression(name: String) = Try {
    val expression = (extractSubQueryOption.map(_.toExpression) ?? parseExpression(stream))
      .getOrElse(die("Expression or sub-query was expected"))
    SQLTemplateParams(assignables = Map(name -> expression))
  }

  /**
    * Parses a source expression; either a direct or via query
    * @param name the named identifier
    * @return the [[SQLTemplateParams SQL template parameters]]
    */
  private def extractSubQueryOrSource(name: String) = Try {
    val executable = stream match {
      case ts if ts nextIf "(" =>
        val result = SQLLanguageParser.parseNext(stream)
        stream expect ")"
        result
      case ts if ts.isQuoted => DataResource(ts.next().text)
      case ts => die("Source or sub-query expected")
    }
    SQLTemplateParams(sources = Map(name -> executable))
  }

  /**
    * Parses a source expression; either a direct or via query
    * @param name the named identifier
    * @return the [[SQLTemplateParams SQL template parameters]]
    */
  private def extractSubQuery(name: String) = Try {
    val executable = stream match {
      case ts if ts is "SELECT" => SQLLanguageParser.parseSelect(ts)
      case ts if ts nextIf "(" =>
        val result = SQLLanguageParser.parseNext(stream)
        stream expect ")"
        result
      case _ => die("Sub-query expected")
    }
    SQLTemplateParams(sources = Map(name -> executable))
  }

  /**
    * Extracts a repeated sequence from the stream
    * @param name the named identifier
    * @param tags the [[PeekableIterator iterator]]
    * @return the [[SQLTemplateParams SQL template parameters]]
    */
  private def processRepeatedSequence(name: String, tags: PeekableIterator[String]) = Try {
    // extract the repeated sequence
    val repeatedTagsSeq = extractRepeatedSequence(tags)
    var paramSet: List[SQLTemplateParams] = Nil
    var done = false
    while (!done && stream.hasNext) {
      var result: Try[SQLTemplateParams] = Success(SQLTemplateParams())
      val count = paramSet.size
      val repeatedTags = new PeekableIterator(repeatedTagsSeq)
      while (repeatedTags.hasNext) {
        result = processNextTag(repeatedTags.next(), repeatedTags)
        result.foreach(params => paramSet = paramSet ::: params :: Nil)
      }

      // if we didn't add anything, stop.
      done = paramSet.size == count
    }
    SQLTemplateParams(repeatedSets = Map(name -> paramSet))
  }

  private def extractRepeatedSequence(tags: Iterator[String]) = {
    tags.takeWhile(_ != "}}").toSeq
  }

  /**
    * Extracts a list of sort columns from the token stream
    * @param name the given identifier name (e.g. "sortedColumns")
    * @return a [[SQLTemplateParams template]] representing the parsed outcome
    */
  private def extractOrderedColumns(name: String) = Try {
    var sortFields: List[OrderedColumn] = Nil
    do {
      val name = stream.nextOption.map(_.text).getOrElse(die("Unexpected end of statement"))
      val direction = stream match {
        case ts if ts nextIf "ASC" => true
        case ts if ts nextIf "DESC" => false
        case _ => true
      }
      sortFields = sortFields ::: OrderedColumn(name, direction) :: Nil
    } while (stream nextIf ",")
    SQLTemplateParams(orderedFields = Map(name -> sortFields))
  }

  private def extractVariableReference(name: String) = Try {
    val reference = stream match {
      case ts if ts nextIf "@" => VariableRef(ts.next().text)
      case ts => die("Variable expected")
    }
    SQLTemplateParams(variables = Map(name -> reference))
  }

  /**
    * Extracts WITH clauses
    * @example WITH CSV|PSV|TSV|JSON FORMAT
    * @example WITH DELIMITER ','
    * @example WITH QUOTED NUMBERS
    * @example WITH GZIP COMPRESSION
    * @param name the name of the collection
    * @return a [[SQLTemplateParams template]] representing the parsed outcome
    */
  private def extractWithClause(name: String) = Try {
    val withAvro = "WITH AVRO %a:avro"
    val withCompression = "WITH %C(compression,GZIP) COMPRESSION"
    val withDelimiter = "WITH DELIMITER %a:delimiter"
    val withFormat = "WITH %C(format,CSV,JSON,PSV,TSV) FORMAT"
    val withHeader = "WITH COLUMN %C(column,HEADERS)"
    val withProps = "WITH PROPERTIES %a:props"
    val withQuoted = "WITH QUOTED %C(quoted,NUMBERS,TEXT)"
    val parser = SQLLanguageParser(stream)
    var hints = Hints()

    def toOption(on: Boolean) = if (on) Some(true) else None

    while (stream.is("WITH")) {
      parser match {
        // WITH AVRO ['./twitter.avsc']
        case p if p.matches(withAvro) =>
          val params = p.process(withAvro)
          hints = hints.copy(avro = params.atoms.get("avro").map(getContents))
        // WITH COLUMN [HEADERS]
        case p if p.matches(withHeader) =>
          val params = p.process(withHeader)
          hints = hints.copy(headers = params.atoms.get("column").map(_.equalsIgnoreCase("HEADERS")))
        // WITH [GZIP] COMPRESSION
        case p if p.matches(withCompression) =>
          val params = p.process(withCompression)
          hints = hints.copy(gzip = params.atoms.get("compression").map(_.equalsIgnoreCase("GZIP")))
        // WITH DELIMITER [,]
        case p if p.matches(withDelimiter) =>
          val params = p.process(withDelimiter)
          hints = hints.copy(delimiter = params.atoms.get("delimiter"))
        // WITH PROPERTIES ['./settings.properties']
        case p if p.matches(withProps) =>
          val params = p.process(withProps)
          hints = hints.copy(properties = params.atoms.get("props").map(getProperties))
        // WITH QUOTED [NUMBERS|TEXT]
        case p if p.matches(withQuoted) =>
          val params = p.process(withQuoted)
          hints = hints.copy(
            quotedNumbers = toOption(params.atoms.get("quoted").exists(_.equalsIgnoreCase("NUMBERS"))) ?? hints.quotedNumbers,
            quotedText = toOption(params.atoms.get("quoted").exists(_.equalsIgnoreCase("TEXT"))) ?? hints.quotedText)
        // WITH [CSV|PSV|TSV|JSON] FORMAT
        case p if p.matches(withFormat) =>
          val params = p.process(withFormat)
          params.atoms.get("format").foreach(format => hints = hints.usingFormat(format = format))
        case _ =>
          die("Syntax error")
      }
    }
    SQLTemplateParams(hints = if (hints.nonEmpty) Map(name -> hints) else Map.empty)
  }

  private def getContents(path: String): String = Source.fromFile(path).mkString

  private def getProperties(path: String): Properties = {
    val props = new Properties()
    new FileInputStream(path) use props.load
    props
  }

}

/**
  * SQL Language Parser Singleton
  * @author lawrence.daniels@gmail.com
  */
object SQLLanguageParser {

  /**
    * Creates a new SQL Language Parser instance
    * @param ts the given [[TokenStream token stream]]
    * @return the [[SQLLanguageParser language parser]]
    */
  def apply(ts: TokenStream): SQLLanguageParser = new SQLLanguageParser(ts)

  /**
    * Parses the next query or statement from the stream
    * @param stream the given [[TokenStream token stream]]
    * @return an [[Executable]]
    */
  def parseNext(stream: TokenStream): Executable = {
    stream match {
      case ts if ts is "CONNECT" => parseConnect(ts)
      case ts if ts is "CREATE" => parseCreateView(ts)
      case ts if ts is "DECLARE" => parseDeclare(ts)
      case ts if ts is "DESCRIBE" => parseDescribe(ts)
      case ts if ts is "DISCONNECT" => parseDisconnect(ts)
      case ts if ts is "INSERT" => parseInsert(ts)
      case ts if ts is "SELECT" => parseSelect(ts)
      case ts if ts is "SET" => parseSet(ts)
      case ts if ts is "SHOW" => parseShow(ts)
      case ts => die("Unexpected end of line")
    }
  }

  private def die[A](message: String): A = throw new IllegalStateException(message)

  /**
    * Parses a CONNECT statement
    * @param ts the given [[TokenStream token stream]]
    * @return an [[Connect executable]]
    */
  private def parseConnect(ts: TokenStream): Connect = {
    val params = SQLTemplateParams(ts, "CONNECT TO %a:service %w:hints AS %a:name")
    Connect(name = params.atoms("name"), serviceName = params.atoms("service"), hints = params.hints.get("hints"))
  }

  /**
    * Parses a CREATE VIEW statement
    * @param ts the given [[TokenStream token stream]]
    * @return an [[View executable]]
    */
  private def parseCreateView(ts: TokenStream): View = {
    val params = SQLTemplateParams(ts, "CREATE VIEW %a:name AS %S:query")
    View(name = params.atoms("name"), query = params.sources("query"))
  }

  /**
    * Parses a DECLARE statement
    * @example {{{ DECLARE @counter DOUBLE }}}
    * @param ts the given [[TokenStream token stream]]
    * @return an [[Declare executable]]
    */
  private def parseDeclare(ts: TokenStream): Declare = {
    val params = SQLTemplateParams(ts, "DECLARE %v:name %a:type")
    val typeName = params.atoms("type")
    if (!Expression.isValidType(typeName)) die(s"Invalid type '$typeName'")
    Declare(variableRef = params.variables("name"), typeName = typeName)
  }

  /**
    * Parses a DESCRIBE statement
    * @example {{{ DESCRIBE './companylist.csv' }}}
    * @example {{{ DESCRIBE './companylist.csv' LIMIT 5 }}}
    * @param ts the given [[TokenStream token stream]]
    * @return an [[Describe executable]]
    */
  private def parseDescribe(ts: TokenStream): Describe = {
    val params = SQLTemplateParams(ts, "DESCRIBE %s:source ?LIMIT +?%n:limit")
    Describe(
      source = params.sources.getOrElse("source", die("No source provided")),
      limit = params.numerics.get("limit").map(_.toInt))
  }

  /**
    * Parses a Disconnect statement
    * @example {{{ DISCONNECT FROM 'weblogs' }}}
    * @param ts the given [[TokenStream token stream]]
    */
  private def parseDisconnect(ts: TokenStream): Disconnect = {
    val params = SQLTemplateParams(ts, "DISCONNECT FROM %a:handle")
    Disconnect(params.atoms("handle"))
  }

  /**
    * Parses an INSERT statement
    * @example
    * {{{
    * INSERT INTO './tickers.csv' (symbol, exchange, lastSale)
    * VALUES ('AAPL', 'NASDAQ', 145.67)
    * VALUES ('AMD', 'NYSE', 5.66)
    * }}}
    * @example
    * {{{
    * INSERT OVERWRITE './companyinfo.csv' (Symbol, Name, Sector, Industry, LastSale, MarketCap)
    * SELECT Symbol, Name, Sector, Industry, LastSale, MarketCap
    * FROM './companylist.csv' WHERE Industry = 'EDP Services'
    * }}}
    * @example
    * {{{
    * INSERT INTO 'companylist.json' WITH FORMAT JSON (Symbol, Name, Sector, Industry)
    * SELECT Symbol, Name, Sector, Industry, `Summary Quote`
    * FROM 'companylist.csv' WITH FORMAT CSV
    * WHERE Industry = 'Oil/Gas Transmission'
    * }}}
    * @param stream the given [[TokenStream token stream]]
    * @return an [[Insert executable]]
    */
  private def parseInsert(stream: TokenStream): Insert = {
    val parser = SQLLanguageParser(stream)
    val params = parser.process("INSERT %C(mode,INTO,OVERWRITE) %a:target %w:hints ( %F:fields )")
    val append = params.atoms("mode").equalsIgnoreCase("INTO")
    val hints = (params.hints.get("hints") ?? Option(Hints())).map(_.copy(append = Some(append)))
    Insert(
      target = DataResource(params.atoms("target"), hints),
      fields = params.fields("fields"),
      source = stream match {
        case ts if ts.is("VALUES") => parseInsertValues(params.fields("fields"), ts, parser)
        case ts => parseNext(ts)
      })
  }

  /**
    * Parses an INSERT VALUES clause
    * @param fields the corresponding fields
    * @param ts     the [[TokenStream token stream]]
    * @param parser the implicit [[SQLLanguageParser language parser]]
    * @return the resulting [[InsertValues modifications]]
    */
  private def parseInsertValues(fields: Seq[Field], ts: TokenStream, parser: SQLLanguageParser): InsertValues = {
    val valueSets = parser.process("{{valueSet VALUES ( %E:values ) }}").repeatedSets.get("valueSet") match {
      case Some(sets) => sets.flatMap(_.expressions.get("values"))
      case None =>
        throw SyntaxException("VALUES clause could not be parsed", ts)
    }
    if (!valueSets.forall(_.size == fields.size))
      throw SyntaxException("The number of fields must match the number of values", ts)
    InsertValues(fields, valueSets)
  }

  /**
    * Parses a SELECT query
    * @example
    * {{{
    * SELECT symbol, exchange, lastSale FROM './EOD-20170505.txt'
    * WHERE exchange = 'NASDAQ'
    * LIMIT 5
    * }}}
    * @param stream the given [[TokenStream token stream]]
    * @return an [[Select executable]]
    */
  private def parseSelect(stream: TokenStream): Executable = {
    val params = SQLTemplateParams(stream,
      """
        |SELECT ?TOP +?%n:top %E:fields
        |?%C(mode,INTO,OVERWRITE) +?%a:target +?%w:targetHints
        |?FROM +?%s:source +?%w:sourceHints
        |?WHERE +?%c:condition
        |?GROUP +?BY +?%F:groupBy
        |?ORDER +?BY +?%o:orderBy
        |?LIMIT +?%n:limit""".stripMargin)

    // create the SELECT statement
    val select = Select(
      fields = params.expressions("fields"),
      source = params.sources.get("source").map(_.withHints(params.hints.get("sourceHints"))),
      condition = params.conditions.get("condition"),
      groupFields = params.fields.getOrElse("groupBy", Nil),
      orderedColumns = params.orderedFields.getOrElse("orderBy", Nil),
      limit = (params.numerics.get("limit") ?? params.numerics.get("top")).map(_.toInt))

    // determine whether 'SELECT INTO' or 'SELECT OVERWRITE' was requested
    params.atoms.get("mode") match {
      case Some(mode) =>
        val append = mode.equalsIgnoreCase("INTO")
        val hints = params.hints.get("targetHints") ?? Option(Hints()) map (_.copy(append = Some(append)))
        Insert(
          target = DataResource(params.atoms("target"), hints),
          source = select,
          fields = params.expressions("fields") map {
            case field: Field => field
            case named: NamedExpression => Field(named.name)
            case expr => Field(expr.getName)
          })
      case None => select
    }
  }

  /**
    * Parses a variable assignment
    * @example {{{ SET @counter = 2 * x + 5 }}}
    * @example {{{ SET @counter = SELECT 1 }}}
    * @param ts the given [[TokenStream token stream]]
    * @return a [[Assignment variable assignment]]
    */
  private def parseSet(ts: TokenStream): Assignment = {
    val params = SQLTemplateParams(ts, "SET %v:name = %q:expression")
    Assignment(variableRef = params.variables("name"), value = params.assignables("expression"))
  }

  /**
    * Parses a SHOW command
    * @example {{{ SHOW VIEWS }}}
    * @param ts the given [[TokenStream token stream]]
    * @return a [[Show executable]]
    */
  private def parseShow(ts: TokenStream): Show = {
    val params = SQLTemplateParams(ts, "SHOW %a:entityType")
    val entityType = params.atoms("entityType")
    if (!Show.isValidEntityType(entityType)) die(s"Invalid entity type '$entityType'")
    Show(entityType)
  }

  /**
    * SQL Language Parser Extensions
    * @param tag the given tag
    */
  final implicit class SLPExtensions(val tag: String) extends AnyVal {

    /**
      * Extracts the chooser parameters (e.g. "%C(mode,INTO,OVERWRITE)" => ["mode", "INTO", "OVERWRITE"])
      */
    @inline
    def chooserParams: Array[String] = tag.drop(3).dropRight(1).filterNot(_ == ' ').split(',').map(_.trim)
  }

}
