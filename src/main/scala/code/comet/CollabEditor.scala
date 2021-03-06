package code.comet

import code.util._

import net.liftweb._
import http.{ jquery => _, _ }
import actor._
import util._
import js._ 
import JsCmds._
import jquery.JqJsCmds._
import JE._
import S._
import Helpers._

import scala.xml._

import java.util.concurrent.atomic._
import java.io._

case class CodeUpdate(from: String, newCode: String)

case object GetCode
case class CodeResp(curCode: String)

case class ConsoleAppend(contents: String)

object CollabEditor extends LiftActor with ListenerManager {
  private var codeUpdate: Option[CodeUpdate] = None
  def createUpdate = codeUpdate.getOrElse(null)
  override def lowPriority = {
    case c: CodeUpdate =>
      //println("Got code update to: " + c.newCode)
      codeUpdate = Some(c)
      updateListeners()
    case GetCode =>
      reply(CodeResp(codeUpdate.map(_.newCode).getOrElse(JavacUtil.defaultCode)))
  }
}

class InputStreamReader(inputStream: InputStream, respondTo: CollabEditor) extends Thread {
  override def run() {
    var cur = inputStream.read()
    val buf = new ByteArrayOutputStream(1024)
    while (cur != -1) {
      buf.write(cur)
      if (inputStream.available == 0) {
        // next invocation of read blocks
        respondTo ! ConsoleAppend(new String(buf.toByteArray))
        buf.reset()
      }
      cur = inputStream.read()
    }
    inputStream.close()
  }
}

class ProcessWaiter(process: Process, callback: () => Unit) extends Thread {
  override def run() {
    process.waitFor()
    callback()
  }
}

class CollabEditor extends CometActor with CometListener {
  def registerWith = CollabEditor

  val buildDir = FileUtils.newTempDir

  //override def shouldUpdate: PartialFunction[Any, Boolean] = {
  //  case CodeUpdate(otherId, _) if otherId != uniqueId => true
  //  case _ => false
  //}

  override def lowPriority = {
    case CodeUpdate(thisId, newCode) =>
      //println("calling editAreaLoader.setValue, from: " + thisId)
      if (thisId != uniqueId) // only update if it came from someone else
        partialUpdate(Seq[JsCmd](
          Call("editAreaLoader.setValue", Str("editorpane"), Str(newCode)),
          compile(newCode)))
      else // otherwise just run compilation task
        partialUpdate(compile(newCode))
    case ConsoleAppend(contents) =>
      //println("got append: " + contents)
      partialUpdate(AppendHtml("console", <pre>{contents}</pre>))
  }

  def compile(newCode: String): JsCmd = {
    def renderDiags(diags: List[CompileDiagnostic]): Seq[JsCmd] = {
      diags.map(diag => diag match {
        case CompileError(lineNum, columnNum, error) =>
          Call("highlightLine", Str(lineNum.toString), Str(error)).cmd
        case CompileWarning(lineNum, columnNum, warning) =>
          Call("highlightWarningLine", Str(lineNum.toString), Str(warning)).cmd
      }).toSeq
    }
    def renderMethods(methods: List[(Int, String)]): NodeSeq = {
      //methods.map(kv => Call("addMethodView", Str(kv._2), Str(kv._1.toString)).cmd).toSeq
      <ul>
      {
        methods.map(kv => <li onclick="jumpToMethod(this)" title={kv._1}>{kv._2}</li>)
      }
      </ul>
    }
    JavacUtil.compile(buildDir, newCode) match {
      case CompileResult(true, Nil, methods) =>
        Seq[JsCmd](
          SetHtml("console", Text("Successful compliation")), 
          Call("clearLines"),
          SetHtml("method-list", renderMethods(methods)))
      case CompileResult(true, diags, methods) =>
        Seq[JsCmd](
          SetHtml("console", Text("Successful compliation with warnings") ++ <br/> ++ 
            diags.map(diag => 
              <div onclick="jumpToMethod(this)" title={diag.lineNum}>{diag.message}</div>)),
          Call("clearLines"),
          SetHtml("method-list", renderMethods(methods))) ++ 
        renderDiags(diags)
      case CompileResult(false, diags, _) =>
        Seq[JsCmd](
          SetHtml("console", Text("Compilation failure") ++ <br/> ++ 
            diags.map(diag => 
              <div onclick="jumpToMethod(this)" title={diag.lineNum}>{diag.message}</div>)),
          Call("clearLines")) ++ renderDiags(diags)
    }
  }

  @volatile var currentPrintWriter: Option[PrintWriter] = None

  def render = {
    val code = CollabEditor !? GetCode match { case CodeResp(c) => c }
    bind("e",
      "theID" -> <div id="__ID__">{uniqueId}</div>,
      "textbox" -> SHtml.textarea(code, (c: String) => {}, "id" -> "editorpane"),
      "run" -> SHtml.ajaxButton(<img src="/images/car.png" class="icon"/> ++ Text("Run"), () => { 
        val mainClass = FileUtils.mainClassFromBuildDir(buildDir)
        mainClass match {
          case Some(main) =>
            val proc = JavacUtil.run(buildDir, main)
            currentPrintWriter = Some(new PrintWriter(proc.getOutputStream))
            new InputStreamReader(new BufferedInputStream(proc.getInputStream), this).start()
            new InputStreamReader(new BufferedInputStream(proc.getErrorStream), this).start()
            new ProcessWaiter(proc, () => currentPrintWriter = None).start()
            SetHtml("console", Text(""))
          case None =>
            SetHtml("console", Text("Error: Could not find main class"))
        }
      }),
      "stdin" -> SHtml.ajaxText("", (cmd: String) => { 
        currentPrintWriter.foreach(w => { w.println(cmd); w.flush() })
        SetValById("stdin", Str(""))
      }, "id" -> "stdin")
    )
  } 
}
