package vork.markdown.processors

import scalafix.v0._
import scala.meta._
import java.io.File
import java.net.URL
import java.net.URLClassLoader
import java.net.URLDecoder
import scala.reflect.internal.util.AbstractFileClassLoader
import scala.reflect.internal.util.BatchSourceFile
import scala.tools.nsc.Global
import scala.tools.nsc.Settings
import scala.tools.nsc.io.AbstractFile
import scala.tools.nsc.io.VirtualDirectory
import scala.tools.nsc.reporters.StoreReporter
import scala.meta.inputs.Input
import scala.meta.inputs.Position
import vork.Logger
import vork.runtime.Document
import vork.runtime.DocumentBuilder
import vork.runtime.Macros
import vork.runtime.PositionedException
import vork.runtime.Section

object MarkdownCompiler {

  case class EvaluatedDocument(sections: List[EvaluatedSection])
  object EvaluatedDocument {
    def apply(document: Document, trees: List[SectionInput]): EvaluatedDocument =
      EvaluatedDocument(
        document.sections.zip(trees).map {
          case (a, b) => EvaluatedSection(a, b.source, b.mod)
        }
      )
  }
  case class EvaluatedSection(section: Section, source: Source, mod: FencedCodeMod) {
    def out: String = section.statements.map(_.out).mkString
  }

  def fromClasspath(cp: String): MarkdownCompiler = {
    val prefix = if (cp.isEmpty) "" else cp + File.pathSeparator
    val runtimeOnly = defaultClasspath { path =>
      Set(
        "scala-library",
        "scala-reflect",
        "pprint",
        "vork-runtime"
      ).contains(path)
    }
    val finalRuntime =
      if (runtimeOnly.isEmpty) defaultClasspath
      else runtimeOnly
    new MarkdownCompiler(prefix + finalRuntime)
  }

  def default(): MarkdownCompiler = fromClasspath("")
  def render(
      sections: List[Input],
      logger: Logger,
      compiler: MarkdownCompiler
  ): EvaluatedDocument = {
    render(sections, compiler, logger, "<input>")
  }

  def render(
      sections: List[Input],
      compiler: MarkdownCompiler,
      logger: Logger,
      filename: String
  ): EvaluatedDocument = {
    renderInputs(
      sections.map(s => SectionInput(s, dialects.Sbt1(s).parse[Source].get, FencedCodeMod.Default)),
      compiler,
      logger,
      filename
    )
  }

  case class SectionInput(input: Input, source: Source, mod: FencedCodeMod)

  def renderInputs(
      sections: List[SectionInput],
      compiler: MarkdownCompiler,
      logger: Logger,
      filename: String
  ): EvaluatedDocument = {
    val instrumented = instrumentSections(sections)
    val inputs = sections.map(_.input)
    val doc = document(compiler, inputs, instrumented, logger, filename)
    val evaluated = EvaluatedDocument(doc, sections)
    evaluated
  }

  def renderEvaluatedSection(section: EvaluatedSection, logger: Logger): String = {
    val sb = new StringBuilder
    var first = true
    section.section.statements.zip(section.source.stats).foreach {
      case (statement, tree) =>
        if (first) {
          first = false
        } else {
          sb.append("\n")
        }
        sb.append("@ ")
          .append(tree.syntax)
        if (statement.out.nonEmpty) {
          sb.append("\n").append(statement.out)
        }
        if (sb.charAt(sb.size - 1) != '\n') {
          sb.append("\n")
        }

        statement.binders.foreach { binder =>
          section.mod match {
            case FencedCodeMod.Fail =>
              binder.value match {
                case Macros.TypecheckedOK(code, tpe) =>
                  // TODO(olafur) retrieve original position of source code
                  logger.error(
                    s"Expected compile error but the statement type-checked successfully to type $tpe:\n$code"
                  )
                  sb.append(s"// $tpe")
                case Macros.ParseError(msg) =>
                  sb.append(msg)
                case Macros.TypeError(msg) =>
                  sb.append(msg)
                case _ =>
                  val obtained = pprint.PPrinter.BlackWhite.apply(binder).toString()
                  throw new IllegalArgumentException(
                    s"Expected Macros.CompileResult." +
                      s"Obtained $obtained"
                  )
              }
            case _ =>
              statement.binders.foreach { binder =>
                sb.append(binder.name)
                  .append(": ")
                  .append(binder.tpe.render)
                  .append(" = ")
                  .append(pprint.PPrinter.BlackWhite.apply(binder.value))
                  .append("\n")
              }
          }
        }
    }
    if (sb.nonEmpty && sb.last == '\n') sb.setLength(sb.length - 1)
    sb.toString()
  }

  def document(
      compiler: MarkdownCompiler,
      original: List[Input],
      instrumented: String,
      logger: Logger,
      filename: String
  ): Document = {
    val wrapped =
      s"""
         |package repl
         |class Session extends _root_.vork.runtime.DocumentBuilder {
         |  def app(): Unit = {
         |$instrumented
         |  }
         |}
      """.stripMargin
    compiler.compile(Input.VirtualFile(filename, wrapped), logger) match {
      case Some(loader) =>
        val cls = loader.loadClass(s"repl.Session")
        val doc = cls.newInstance().asInstanceOf[DocumentBuilder].$doc
        try {
          doc.build()
        } catch {
          case e: PositionedException =>
            val input = original(e.section - 1)
            val pos =
              if (e.pos.isEmpty) {
                Position.Range(input, 0, 0)
              } else {
                val slice = Position.Range(
                  input,
                  e.pos.startLine,
                  e.pos.startColumn,
                  e.pos.endLine,
                  e.pos.endColumn
                )
                input match {
                  case Input.Slice(underlying, a, b) =>
                    Position.Range(underlying, a + slice.start, a + slice.end)
                  case _ => slice
                }
              }
            logger.error(pos, e.getCause)
            Document.empty
        }
      case None =>
        // An empty document will render as the original markdown
        Document.empty
    }
  }

  // Copy paste from scalafix
  def defaultClasspath: String = defaultClasspath(_ => true)
  def defaultClasspath(filter: String => Boolean): String = {
    getClass.getClassLoader match {
      case u: URLClassLoader =>
        val paths = u.getURLs.iterator
          .map(sanitizeURL)
          .filter(path => filter(path))
          .toList
        paths.mkString(File.pathSeparator)
      case _ => ""
    }
  }

  def sanitizeURL(u: URL): String = {
    if (u.getProtocol.startsWith("bootstrap")) {
      import java.io._
      import java.nio.file._
      val stream = u.openStream
      val tmp = File.createTempFile("bootstrap-" + u.getPath, ".jar")
      Files
        .copy(stream, Paths.get(tmp.getAbsolutePath), StandardCopyOption.REPLACE_EXISTING)
      tmp.getAbsolutePath
    } else {
      URLDecoder.decode(u.getPath, "UTF-8")
    }
  }
  def instrumentSections(sections: List[SectionInput]): String = {
    var counter = 0
    val totalStats = sections.map(_.source.stats.length).sum
    val mapped = sections.map { section =>
      val (instrumentedSection, nextCounter) = instrument(section, counter)
      counter = nextCounter
      WIDTH_INDENT +
        "; $doc.section {\n" +
        instrumentedSection
    }
    val join = """
                 |// =======
                 |// Section
                 |// =======
                 |""".stripMargin
    val end = "\n" + ("}" * (totalStats + sections.length))
    mapped.mkString("", join, end)
  }

  object Binders {
    def binders(pat: Pat): List[Name] =
      pat.collect { case m: Member => m.name }
    def unapply(tree: Tree): Option[List[Name]] = tree match {
      case Defn.Val(_, pats, _, _) => Some(pats.flatMap(binders))
      case Defn.Var(_, pats, _, _) => Some(pats.flatMap(binders))
      case _: Defn => Some(Nil)
      case _: Import => Some(Nil)
      case _ => None
    }
  }

  def literal(string: String): String = {
    import scala.meta.internal.prettyprinters._
    enquote(string, DoubleQuotes)
  }

  private val WIDTH = 100
  private val WIDTH_INDENT = " " * WIDTH
  private val VORK = "/* $vork */"
  def stripVorkSuffix(message: String): String = {
    val idx = message.indexOf(VORK)
    if (idx < 0) message
    else message.substring(0, idx)
  }

  def position(pos: Position): String = {
    s"${pos.startLine}, ${pos.startColumn}, ${pos.endLine}, ${pos.endColumn}"
  }

  def instrument(section: SectionInput, n: Int): (String, Int) = {
    var counter = n
    val source = section.source
    val stats = source.stats
    val ctx = RuleCtx(source)
    def freshBinder(): String = {
      val name = "res" + counter
      counter += 1
      name
    }
    val rule = Rule.syntactic("Vork") { ctx =>
      val patches = stats.map { stat =>
        val (names, freshBinderPatch) = Binders.unapply(stat) match {
          case Some(b) if section.mod != FencedCodeMod.Fail =>
            b -> Patch.empty
          case _ =>
            val name = freshBinder()
            List(Term.Name(name)) -> ctx.addLeft(stat, s"${WIDTH_INDENT}val $name = \n")
        }
        val failPatch = section.mod match {
          case FencedCodeMod.Fail =>
            val newCode =
              s"_root_.vork.runtime.Macros.fail(${literal(stat.syntax)}); "
            ctx.replaceTree(stat, newCode)
          case _ =>
            Patch.empty
        }
        val rightIndent = " " * (WIDTH - stat.pos.endColumn)
        val positionPatch =
          if (section.mod.isDefault) {
            val statPosition = s"$$doc.position(${position(stat.pos)}); \n"
            ctx.addLeft(stat, statPosition)
          } else {
            Patch.empty
          }

        val binders = names
          .map(name => s"$$doc.binder($name, ${position(name.pos)})")
          .mkString(rightIndent + VORK + "; ", "; ", s"; $$doc.statement { ")
        failPatch +
          positionPatch +
          freshBinderPatch +
          ctx.addRight(stat, binders)
      }
      val patch = patches.asPatch
      patch
    }
    val out = rule.apply(ctx)
    out -> counter
  }
}

class MarkdownCompiler(
    classpath: String,
    target: AbstractFile = new VirtualDirectory("(memory)", None)
) {
  private val settings = new Settings()
  settings.deprecation.value = true // enable detailed deprecation warnings
  settings.unchecked.value = true // enable detailed unchecked warnings
  settings.outputDirs.setSingleOutput(target)
  settings.classpath.value = classpath
  lazy val reporter = new StoreReporter
  private val global = new Global(settings, reporter)
  private val appClasspath: Array[URL] = classpath
    .split(File.pathSeparator)
    .map(path => new File(path).toURI.toURL)
  private val appClassLoader = new URLClassLoader(
    appClasspath,
    this.getClass.getClassLoader
  )
  private def classLoader = new AbstractFileClassLoader(target, appClassLoader)

  private def clearTarget(): Unit = target match {
    case vdir: VirtualDirectory => vdir.clear()
    case _ =>
  }

  def compile(input: Input, logger: Logger): Option[ClassLoader] = {
    clearTarget()
    reporter.reset()
    val run = new global.Run
    val label = input match {
      case Input.File(path, _) => path.toString()
      case Input.VirtualFile(path, _) => path
      case _ => "(input)"
    }
    run.compileSources(List(new BatchSourceFile(label, new String(input.chars))))
    if (!reporter.hasErrors) {
      Some(classLoader)
    } else {
      reporter.infos.foreach {
        case reporter.Info(pos, msg, severity) =>
          // We don't include line:column because we'd need to source-map
          // instrumented code positions to the original source.
          val formatted = s"""|${input.syntax} $msg
                              |${MarkdownCompiler.stripVorkSuffix(pos.lineContent)}
                              |${pos.lineCaret}""".stripMargin
          severity match {
            case reporter.ERROR => logger.error(formatted)
            case reporter.INFO => logger.info(formatted)
            case reporter.WARNING => logger.warning(formatted)
          }
        case _ =>
      }
      None
    }
  }
}
